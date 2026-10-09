package app.pinballpilot.data

import android.content.Context
import androidx.room.withTransaction
import app.pinballpilot.domain.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton class PilotRepository @Inject constructor(@ApplicationContext private val context: Context, private val db: PilotDatabase) {
    private val dao get() = db.dao()
    private val json = Json { ignoreUnknownKeys = true }
    val packs = dao.packs().map { rows -> rows.map { json.decodeFromString<MachinePack>(it.json) } }
    val games = dao.games()
    val learning = dao.learning()
    val preferences = dao.preferences().map { entries -> entries.associate { it.key to it.value } }
    suspend fun seed() = withContext(Dispatchers.IO) {
        if (dao.pack("workshop-demo") == null) {
            val raw = context.assets.open("demo-pack.json").bufferedReader().use { it.readText() }
            val pack = json.decodeFromString<MachinePack>(raw)
            dao.putPack(PackEntity(pack.variantId, pack.version, raw))
        }
    }
    suspend fun install(raw: String) {
        val p = json.decodeFromString<MachinePack>(raw)
        require(p.schemaVersion == 1 && p.image.rightsConfirmed) { "Unsupported or unlicensed pack." }
        require(p.claims.all { it.status == "approved" && it.reviewedRevision == it.revision && it.variantId == p.variantId }) { "Pack contains unreviewed evidence." }
        require(p.shots.all { it.geometry.point.x in 0f..1f && it.geometry.point.y in 0f..1f }) { "Invalid shot geometry." }
        db.withTransaction {
            val old = dao.pack(p.variantId)
            require(old == null || p.version > old.version) { "This pack version is already installed or is older." }
            dao.putPack(PackEntity(p.variantId, p.version, raw))
        }
    }
    suspend fun preference(key: String, value: String) = dao.putPreference(PreferenceEntity(key, value))
    suspend fun newGame(variantId: String): GameEntity = db.withTransaction {
        dao.latestGame()?.let { if (!it.ended) dao.putGame(it.copy(ended = true)) }
        val now = System.currentTimeMillis()
        GameEntity(UUID.randomUUID().toString(), variantId, now, now).also { dao.putGame(it) }
    }
    suspend fun record(variantId: String, kind: String, text: String, outcome: String? = null): GameEntity = db.withTransaction {
        val now = System.currentTimeMillis(); val previous = dao.latestGame()
        val startsNew = GameGrouping.startsNew(previous?.let { GameActivity(it.variantId, it.updatedAt, confirmedGameOver = it.ended) }, GameActivity(variantId, now))
        val game = if (startsNew) GameEntity(UUID.randomUUID().toString(), variantId, now, now) else previous!!
        val completed = json.decodeFromString<List<String>>(game.completed).toMutableSet()
        outcome?.let { completed += it; dao.putLearning(LearningEntity(variantId, it, now)) }
        val updated = game.copy(updatedAt = now, completed = json.encodeToString(completed.toList()))
        dao.putGame(updated)
        dao.putEvent(EventEntity(UUID.randomUUID().toString(), game.id, now, kind, text))
        updated
    }
    suspend fun saveScore(game: GameEntity, score: Long) { require(score >= 0); dao.putGame(game.copy(score = score, ended = true, updatedAt = System.currentTimeMillis())) }
    suspend fun sync(api: PilotApi) {
        val events=dao.unsynced().take(500)
        val payload=buildJsonObject {
            put("games",JsonArray(dao.allGames().map { g -> buildJsonObject {put("id",g.id);put("variantId",g.variantId);put("startedAt",g.startedAt);put("updatedAt",g.updatedAt);put("score",g.score?.let(::JsonPrimitive)?:JsonNull);put("ended",g.ended);put("completed",Json.parseToJsonElement(g.completed))} }))
            put("events",JsonArray(events.map { e -> buildJsonObject {put("id",e.id);put("gameId",e.gameId);put("timestamp",e.timestamp);put("kind",e.kind);put("text",e.text)} }))
            put("learning",JsonArray(dao.allLearning().map { l -> buildJsonObject {put("variantId",l.variantId);put("outcome",l.outcome);put("learnedAt",l.learnedAt)} }))
            put("favourites",JsonArray(dao.allPreferences().filter {it.key.startsWith("favourite:")&&it.value=="true"}.map {JsonPrimitive(it.key.removePrefix("favourite:"))}))
        }
        val result=api.sync(payload)
        db.withTransaction {
            for(item in result["games"]?.jsonArray.orEmpty()) {
                val g=item.jsonObject;val id=g.getValue("id").jsonPrimitive.content
                val remote=GameEntity(id,g.getValue("variantId").jsonPrimitive.content,g.getValue("startedAt").jsonPrimitive.long,g.getValue("updatedAt").jsonPrimitive.long,g.getValue("completed").toString(),g.getValue("ended").jsonPrimitive.boolean,g["score"]?.jsonPrimitive?.longOrNull)
                val local=dao.game(id);if(local==null||remote.updatedAt>local.updatedAt) dao.putGame(remote)
            }
            for(item in result["learning"]?.jsonArray.orEmpty()) {val l=item.jsonObject;dao.putLearning(LearningEntity(l.getValue("variantId").jsonPrimitive.content,l.getValue("outcome").jsonPrimitive.content,l.getValue("learnedAt").jsonPrimitive.long))}
            for(item in result["favourites"]?.jsonArray.orEmpty()) dao.putPreference(PreferenceEntity("favourite:${item.jsonPrimitive.content}","true"))
            events.forEach {dao.markSynced(it.id)}
        }
    }
    suspend fun updatePacks(api: PilotApi) = withContext(Dispatchers.IO) {
        for(entry in api.catalogue()) {
            val id=entry.jsonObject.getValue("variantId").jsonPrimitive.content
            val version=entry.jsonObject.getValue("version").jsonPrimitive.int
            if((dao.pack(id)?.version?:0)>=version) continue
            val remote=json.decodeFromString<MachinePack>(api.pack(id))
            val local=if(remote.demo) remote else {
                val image=api.playfield(remote.image.path)
                val file=File(context.filesDir,"playfields/$id-$version.img");file.parentFile?.mkdirs();file.writeBytes(image)
                remote.copy(image=remote.image.copy(path=UriFile.path(file)))
            }
            install(json.encodeToString(local))
        }
    }
}
private object UriFile { fun path(file: File): String = android.net.Uri.fromFile(file).toString() }
