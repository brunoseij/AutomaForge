package com.automaforge.android

import androidx.room.Room
import com.automaforge.android.core.GameJson
import com.automaforge.android.data.game.GameStore
import com.automaforge.android.data.local.AutomaDatabase
import com.automaforge.android.data.repository.RoomGameLibraryRepository
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real Room/SQLite implementation on the host JVM, never Android instrumentation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomPersistenceTest {
    private lateinit var db: AutomaDatabase
    private lateinit var store: GameStore
    private val context
        get() = (RuntimeEnvironment.getApplication() as AutomaForgeApplication)

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, AutomaDatabase::class.java).build()
        store = GameStore(context, db)
    }

    @After
    fun close() {
        db.close()
    }

    private fun legacy(code: String): File {
        val folder = File(context.filesDir, "rooms").apply { mkdirs() }
        return File(folder, "$code.json").apply {
            writeText(
                GameJson.obj(
                        "code",
                        code,
                        "game_name",
                        "Legacy",
                        "turn",
                        3,
                        "phase",
                        "Play",
                        "custom_variable",
                        17,
                        "actions",
                        JSONArray(),
                        "history",
                        JSONArray(),
                        "rule_pages",
                        JSONArray(),
                        "rules_text",
                        "preserved rules",
                    )
                    .toString()
            )
        }
    }

    @Test
    fun legacyImportPreservesUnknownFieldsOriginalFilesAndPhotoPaths() = runBlocking {
        val file = legacy("ABCDEFGH")
        val original = file.readBytes()
        val folder = File(file.parentFile, "ABCDEFGH").apply { mkdirs() }
        val photo = File(folder, "board.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        store.importLegacy()
        val room = store.get("ABCDEFGH")
        assertEquals(17, room.getInt("custom_variable"))
        assertEquals(3, room.getInt("turn"))
        assertEquals("preserved rules", room.getString("rules_text"))
        assertArrayEquals(original, file.readBytes())
        assertEquals(photo, store.boardPhoto("ABCDEFGH"))
        assertTrue(room.has("game_state"))
    }

    @Test
    fun restartingAndReimportingDoesNotReplaceNewerRoomData() {
        legacy("ABCDEFGH")
        store.importLegacy()
        val room = store.get("ABCDEFGH")
        room.put("custom_variable", 29)
        store.save(room, true)
        val restarted = GameStore(context, db)
        restarted.importLegacy()
        assertEquals(29, restarted.get("ABCDEFGH").getInt("custom_variable"))
        assertEquals(1, restarted.get("ABCDEFGH").getInt("revision"))
    }

    @Test
    fun corruptLegacyFileIsIsolatedAndRetainedForRecovery() {
        legacy("ABCDEFGH")
        val bad = legacy("JKLMNPQR").apply { writeText("broken json") }
        store.importLegacy()
        assertEquals("ABCDEFGH", store.get("ABCDEFGH").getString("code"))
        assertEquals("broken json", bad.readText())
        assertEquals(1, store.importErrors().size)
    }

    @Test
    fun privateCardMemoryNeverEntersRoomAndDoesNotSurviveRepositoryRecreation() = runBlocking {
        val room = store.create("Public game")
        val code = room.getString("code")
        store.privateCards(code).put(GameJson.obj("secret", "private-sentinel"))
        val mutable = store.get(code).put("private_cards", JSONArray().put("private-sentinel"))
        store.save(mutable, true)
        val row = withContext(Dispatchers.IO) { db.games().find(code)!! }
        assertFalse(row.publicJson.contains("private-sentinel"))
        assertFalse(store.publicRoom(mutable).has("private_cards"))
        assertEquals(
            0,
            GameStore(context, db)
                .publicRoom(GameStore(context, db).get(code))
                .getInt("private_card_count"),
        )
    }

    @Test
    fun roomFlowReflectsCommittedChangesAndApplicationGraphUsesSingletonStorage() = runBlocking {
        val room = store.create("Initial")
        val repository = RoomGameLibraryRepository(store)
        repository.initialize()
        assertEquals(
            "Initial",
            withTimeout(5000) { repository.observeGames().first() }.single().name,
        )
        room.put("game_name", "Updated")
        store.save(room, true)
        assertEquals(
            "Updated",
            withTimeout(5000) { repository.observeGames().first { it.single().name == "Updated" } }
                .single()
                .name,
        )
        assertSame(context.graph.database(), context.graph.database())
        assertSame(context.graph.store(), context.graph.store())
    }

    @Test
    fun legacyGenericProposalStaysUnapprovableAfterMigration() {
        val file = legacy("ABCDEFGH")
        file.writeText(
            JSONObject(file.readText())
                .put("proposal", GameJson.obj("action", "generic intent"))
                .toString()
        )
        store.importLegacy()
        val room = store.get("ABCDEFGH")
        assertTrue(room.isNull("proposal"))
        assertTrue(room.has("legacy_proposal"))
    }
}
