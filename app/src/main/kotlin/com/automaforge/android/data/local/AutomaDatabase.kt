package com.automaforge.android.data.local

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Stable indexed columns plus a versioned JSON document for each game's varying schema. */
@Entity(tableName = "games")
data class GameRecord(
    @PrimaryKey val code: String,
    val name: String,
    val revision: Int,
    val updatedAt: String,
    val publicJson: String,
)

@Entity(tableName = "legacy_imports")
data class LegacyImportRecord(
    @PrimaryKey val file: String,
    val importedAt: String,
    val error: String?,
)

@Dao
interface GameDao {
    @Query("SELECT * FROM games WHERE code = :code") fun find(code: String): GameRecord?

    @Query("SELECT * FROM games ORDER BY updatedAt DESC") fun observeGames(): Flow<List<GameRecord>>

    @Query("SELECT * FROM games WHERE code = :code")
    fun observeGame(code: String): Flow<GameRecord?>

    @Upsert fun save(record: GameRecord)

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun importIfMissing(record: GameRecord): Long

    @Query("SELECT * FROM legacy_imports WHERE file = :file")
    fun imported(file: String): LegacyImportRecord?

    @Upsert fun recordImport(record: LegacyImportRecord)

    @Query("SELECT * FROM legacy_imports WHERE error IS NOT NULL")
    fun importErrors(): List<LegacyImportRecord>
}

@Database(
    entities = [GameRecord::class, LegacyImportRecord::class],
    version = 1,
    exportSchema = true,
)
abstract class AutomaDatabase : RoomDatabase() {
    abstract fun games(): GameDao

    companion object {
        fun open(context: Context): AutomaDatabase =
            Room.databaseBuilder(
                    context.applicationContext,
                    AutomaDatabase::class.java,
                    "automaforge.db",
                )
                .build() // No destructive fallback and no main-thread query exemption.
    }
}
