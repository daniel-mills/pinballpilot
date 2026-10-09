package app.pinballpilot.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "packs") data class PackEntity(@PrimaryKey val variantId: String, val version: Int, val json: String)
@Entity(tableName = "pack_archive", primaryKeys = ["variantId", "version"]) data class ArchivedPack(val variantId: String, val version: Int, val json: String)
@Entity(tableName = "games") data class GameEntity(@PrimaryKey val id: String, val variantId: String, val startedAt: Long, val updatedAt: Long, val completed: String = "[]", val ended: Boolean = false, val score: Long? = null, val packVersion: Int? = null, @ColumnInfo(defaultValue = "'{}'") val state: String = "{}")
@Entity(tableName = "learning", primaryKeys = ["variantId", "outcome"]) data class LearningEntity(val variantId: String, val outcome: String, val learnedAt: Long)
@Entity(tableName = "events", foreignKeys = [ForeignKey(entity = GameEntity::class, parentColumns = ["id"], childColumns = ["gameId"], onDelete = ForeignKey.CASCADE)], indices = [Index("gameId")])
data class EventEntity(@PrimaryKey val id: String, val gameId: String, val timestamp: Long, val kind: String, val text: String, val synced: Boolean = false, @ColumnInfo(defaultValue = "'{}'") val payload: String = "{}")
@Entity(tableName = "preferences") data class PreferenceEntity(@PrimaryKey val key: String, val value: String)

@Dao interface PilotDao {
    @Query("SELECT * FROM packs ORDER BY variantId") fun packs(): Flow<List<PackEntity>>
    @Query("SELECT * FROM packs WHERE variantId = :id") suspend fun pack(id: String): PackEntity?
    @Upsert suspend fun putPack(pack: PackEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun archive(pack: ArchivedPack)
    @Query("SELECT * FROM pack_archive") fun archives(): Flow<List<ArchivedPack>>
    @Query("SELECT * FROM pack_archive WHERE variantId=:id AND version=:version") suspend fun archived(id: String, version: Int): ArchivedPack?
    @Query("SELECT * FROM games ORDER BY updatedAt DESC") fun games(): Flow<List<GameEntity>>
    @Query("SELECT * FROM games ORDER BY updatedAt DESC LIMIT 1") suspend fun latestGame(): GameEntity?
    @Query("SELECT * FROM games WHERE id=:id") suspend fun game(id: String): GameEntity?
    @Query("SELECT * FROM games ORDER BY updatedAt DESC LIMIT 500") suspend fun allGames(): List<GameEntity>
    @Upsert suspend fun putGame(game: GameEntity)
    @Query("SELECT * FROM learning") fun learning(): Flow<List<LearningEntity>>
    @Upsert suspend fun putLearning(learning: LearningEntity)
    @Query("SELECT * FROM learning") suspend fun allLearning(): List<LearningEntity>
    @Insert suspend fun putEvent(event: EventEntity)
    @Query("SELECT * FROM events WHERE gameId=:id AND kind IN ('progress','ball','correction') ORDER BY timestamp DESC, rowid DESC LIMIT 1") suspend fun lastProgress(id: String): EventEntity?
    @Query("SELECT * FROM events WHERE synced = 0 ORDER BY timestamp") suspend fun unsynced(): List<EventEntity>
    @Query("UPDATE events SET synced = 1 WHERE id = :id") suspend fun markSynced(id: String)
    @Query("SELECT * FROM preferences") fun preferences(): Flow<List<PreferenceEntity>>
    @Query("SELECT * FROM preferences") suspend fun allPreferences(): List<PreferenceEntity>
    @Upsert suspend fun putPreference(preference: PreferenceEntity)
}
@Database(entities = [PackEntity::class, ArchivedPack::class, GameEntity::class, LearningEntity::class, EventEntity::class, PreferenceEntity::class], version = 2, exportSchema = true)
abstract class PilotDatabase : RoomDatabase() {
    abstract fun dao(): PilotDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN packVersion INTEGER")
                db.execSQL("ALTER TABLE games ADD COLUMN state TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE events ADD COLUMN payload TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("CREATE TABLE pack_archive (variantId TEXT NOT NULL, version INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY(variantId, version))")
                db.execSQL("INSERT INTO pack_archive SELECT variantId,version,json FROM packs")
                // Old sessions have no provable pack version; leave it unknown.
            }
        }
    }
}
