package com.automaforge.android.data.game

import android.content.Context
import com.automaforge.android.AutomaForgeApplication
import com.automaforge.android.core.*
import com.automaforge.android.data.local.*
import com.automaforge.android.domain.game.*
import java.io.File
import java.security.SecureRandom
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.*

/** Serialized mutable session workspace; Room is the durable source of public game data. */
@Singleton
class GameStore @Inject constructor(context: Context, private val database: AutomaDatabase) {
    constructor(
        context: Context
    ) : this(context, (context.applicationContext as AutomaForgeApplication).graph.database())

    private val directory = File(context.filesDir, "rooms").apply { check(exists() || mkdirs()) }
    private val cache = mutableMapOf<String, JSONObject>()
    // No image, card text or any sensitive interpretation written to disk.
    private val privateCards = mutableMapOf<String, JSONArray>()
    private val random = SecureRandom()
    private var imported = false
    private val dao
        get() = database.games()

    private fun <T> io(block: () -> T): T = runBlocking(Dispatchers.IO) { block() }

    @Synchronized
    fun importLegacy() {
        if (imported) return
        io {
            directory
                .listFiles()
                .orEmpty()
                .filter { it.name.matches(Regex("[A-HJ-NP-Z2-9]{8}\\.json")) }
                .forEach { file ->
                    if (dao.imported(file.name)?.error == null && dao.imported(file.name) != null)
                        return@forEach
                    try {
                        val room = JSONObject(file.readText())
                        require(room.getString("code") == file.nameWithoutExtension) {
                            "Código não corresponde ao arquivo."
                        }
                        prepare(room)
                        database.runInTransaction {
                            // Never replace a newer Room session with an old JSON backup.
                            dao.importIfMissing(record(room))
                            dao.recordImport(LegacyImportRecord(file.name, GameJson.now(), null))
                        }
                    } catch (failure: Exception) {
                        dao.recordImport(
                            LegacyImportRecord(
                                file.name,
                                GameJson.now(),
                                "Arquivo legado inválido; original preservado.",
                            )
                        )
                    }
                }
        }
        imported = true // Original files and photo directories remain available; no delete/rename.
    }

    fun observeGames() = dao.observeGames()

    fun observeGame(code: String) = dao.observeGame(code)

    fun importErrors(): List<LegacyImportRecord> = io { dao.importErrors() }

    private fun prepare(room: JSONObject) {
        room.remove("private_cards")
        GameEngine.state(room)
        GameKnowledge.ensure(room)
        AutoState.defaults(room)
    }

    private fun record(room: JSONObject): GameRecord {
        val public =
            JSONObject(room.toString()).apply {
                remove("private_cards")
                remove("private_card_count")
            }
        return GameRecord(
            room.getString("code"),
            room.optString("game_name"),
            room.optInt("revision"),
            GameJson.now(),
            public.toString(),
        )
    }

    @Synchronized
    @Throws(Exception::class)
    fun create(name: String): JSONObject {
        importLegacy()
        var code: String
        do {
            code = (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        } while (io { dao.find(code) != null } || File(directory, "$code.json").exists())
        val room =
            GameJson.obj(
                "code",
                code,
                "game_name",
                GameJson.clip(name.ifBlank { "Jogo de tabuleiro" }, 100),
                "created_at",
                GameJson.now(),
                "revision",
                0,
                "rules_text",
                "",
                "setup",
                "",
                "turn_flow",
                "",
                "actions",
                JSONArray(),
                "uncertainties",
                JSONArray(),
                "rule_pages",
                JSONArray(),
                "rules_draft",
                JSONObject.NULL,
                "turn",
                1,
                "phase",
                "Preparação",
                "variables",
                JSONObject(),
                "board_notes",
                "",
                "board_observation",
                JSONObject.NULL,
                "board_version",
                0,
                "proposal",
                JSONObject.NULL,
                "history",
                JSONArray(),
                "last_announcement",
                JSONObject.NULL,
            )
        prepare(room)
        cache[code] = room
        privateCards[code] = JSONArray()
        save(room, false)
        return publicRoom(room)
    }

    @Synchronized
    @Throws(Exception::class)
    fun get(rawCode: String): JSONObject {
        val code = rawCode.uppercase(Locale.ROOT)
        if (!code.matches(Regex("[A-HJ-NP-Z2-9]{8}")))
            throw ApiException(404, "Sala não encontrada.")
        importLegacy()
        cache[code]?.let {
            return it
        }
        val record = io { dao.find(code) } ?: throw ApiException(404, "Sala não encontrada.")
        return JSONObject(record.publicJson).also {
            prepare(it)
            cache[code] = it
            privateCards[code] = JSONArray()
        }
    }

    @Synchronized
    fun privateCards(code: String): JSONArray = privateCards.getOrPut(code) { JSONArray() }

    @Synchronized
    fun clearPrivate(code: String) {
        privateCards[code] = JSONArray()
    }

    @Synchronized
    fun clearAllPrivate() {
        privateCards.clear()
    }

    @Synchronized
    @Throws(Exception::class)
    fun publicRoom(room: JSONObject): JSONObject =
        JSONObject(room.toString()).apply {
            remove("private_cards")
            put("private_card_count", privateCards(getString("code")).length())
        }

    @Synchronized
    @Throws(Exception::class)
    fun save(room: JSONObject, revision: Boolean) {
        if (revision) room.put("revision", room.optInt("revision") + 1)
        val next = record(room)
        io { database.runInTransaction { dao.save(next) } }
    }

    @Throws(Exception::class)
    fun photoDir(code: String, child: String): File {
        require(code.matches(Regex("[A-HJ-NP-Z2-9]{8}")))
        return File(File(directory, code), child).apply { check(exists() || mkdirs()) }
    }

    @Throws(Exception::class)
    fun boardPhoto(code: String): File = File(photoDir(code, ""), "board.jpg")

    companion object {
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
