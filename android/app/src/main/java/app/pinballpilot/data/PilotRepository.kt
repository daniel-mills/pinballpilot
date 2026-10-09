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
    val archives = dao.archives().map { rows -> rows.map { json.decodeFromString<MachinePack>(it.json) } }
    val learning = dao.learning()
    val preferences = dao.preferences().map { entries -> entries.associate { it.key to it.value } }
    suspend fun seed() = withContext(Dispatchers.IO) {
        if (dao.pack("workshop-demo") == null) {
            val raw = context.assets.open("demo-pack.json").bufferedReader().use { it.readText() }
            val pack = json.decodeFromString<MachinePack>(raw)
            dao.putPack(PackEntity(pack.variantId, pack.version, raw))
            dao.archive(ArchivedPack(pack.variantId, pack.version, raw))
        }
    }
    suspend fun install(raw: String) {
        val p = json.decodeFromString<MachinePack>(raw)
        PackValidation.validate(p)
        db.withTransaction {
            val old = dao.pack(p.variantId)
            require(old == null || p.version > old.version) { "This pack version is already installed or is older." }
            dao.putPack(PackEntity(p.variantId, p.version, raw))
            dao.archive(ArchivedPack(p.variantId, p.version, raw))
        }
    }
    suspend fun preference(key: String, value: String) = dao.putPreference(PreferenceEntity(key, value))
    suspend fun newGame(variantId: String): GameEntity = db.withTransaction {
        dao.latestGame()?.let { if (!it.ended) dao.putGame(it.copy(ended = true)) }
        val now = System.currentTimeMillis()
        createGame(variantId, now).also { dao.putGame(it) }
    }
    private suspend fun createGame(variantId: String, now: Long): GameEntity {
        val row = dao.pack(variantId) ?: error("Download a guide first")
        val pack = json.decodeFromString<MachinePack>(row.json)
        dao.archive(ArchivedPack(variantId, pack.version, row.json))
        return GameEntity(UUID.randomUUID().toString(), variantId, now, now, packVersion = pack.version, state = json.encodeToString(StateEngine.initial(pack.stateVariables, now)))
    }
    private suspend fun gamePack(game: GameEntity): MachinePack {
        val raw = game.packVersion?.let { dao.archived(game.variantId, it)?.json } ?: if (game.packVersion == null) dao.pack(game.variantId)?.json else null
        return json.decodeFromString(raw ?: error("Download the guide version used by this game before continuing"))
    }
    suspend fun activeSession(variantId: String): Pair<MachinePack, GameEntity> {
        val game = dao.latestGame()?.takeIf { it.variantId == variantId && !it.ended } ?: error("Start a game first")
        return gamePack(game) to game
    }
    suspend fun record(variantId: String, kind: String, text: String, outcome: String? = null, ruleId: String? = null, expectedPackVersion: Int? = null): GameEntity = db.withTransaction {
        val now = System.currentTimeMillis(); val previous = dao.latestGame()
        val startsNew = GameGrouping.startsNew(previous?.let { GameActivity(it.variantId, it.updatedAt, confirmedGameOver = it.ended) }, GameActivity(variantId, now))
        val game = if (startsNew) createGame(variantId, now) else previous!!
        val completed = json.decodeFromString<List<String>>(game.completed).toMutableSet()
        val before = json.decodeFromString<GameState>(game.state)
        val pack = gamePack(game)
        require(expectedPackVersion == null || pack.version == expectedPackVersion) { "The guide changed. Start a fresh game and check the next step." }
        val rule = ruleId?.let { id -> pack.rules.firstOrNull { it.id == id } ?: error("Rule no longer matches this game") }
        if (rule != null) require((rule.repeatable || rule.outcome !in completed) && rule.prerequisites.all(completed::contains) && StateEngine.eligible(rule.conditions, before, now)) { "Progress changed; check the next step again" }
        val after = StateEngine.apply(pack.stateVariables, before, rule?.effects.orEmpty(), now)
        outcome?.let { completed += it; dao.putLearning(LearningEntity(variantId, it, now)) }
        val updated = game.copy(updatedAt = now, completed = json.encodeToString(completed.toList()), state = json.encodeToString(after))
        dao.putGame(updated)
        val payload = buildJsonObject {
            if (ruleId != null) put("ruleId", ruleId)
            put("origin", "player"); put("before", json.encodeToJsonElement(before)); put("after", json.encodeToJsonElement(after)); put("completedBefore", Json.parseToJsonElement(game.completed))
        }
        dao.putEvent(EventEntity(UUID.randomUUID().toString(), game.id, now, kind, text, payload = payload.toString()))
        updated
    }
    suspend fun endBall(variantId: String) = db.withTransaction {
        val game = dao.latestGame()?.takeIf { it.variantId == variantId && !it.ended } ?: error("Start a game first")
        val pack = gamePack(game); val now = System.currentTimeMillis()
        val before = json.decodeFromString<GameState>(game.state)
        val after = StateEngine.reset(pack.stateVariables, before, "ball", now)
        dao.putGame(game.copy(state = json.encodeToString(after), updatedAt = now))
        val payload = buildJsonObject { put("origin", "player"); put("before", json.encodeToJsonElement(before)); put("after", json.encodeToJsonElement(after)); put("completedBefore", Json.parseToJsonElement(game.completed)) }
        dao.putEvent(EventEntity(UUID.randomUUID().toString(), game.id, now, "ball", "Player reported ball end", payload = payload.toString()))
    }
    suspend fun confirmState(variantId: String, variable: String, value: JsonPrimitive?) = db.withTransaction {
        val game = dao.latestGame()?.takeIf { it.variantId == variantId && !it.ended } ?: error("Start a game first")
        val pack = gamePack(game); val def = pack.stateVariables.firstOrNull { it.id == variable } ?: error("Unknown progress item")
        require(def.type != "timer" && StateEngine.valid(def, value)) { "Check the progress value" }
        val now = System.currentTimeMillis()
        val before = json.decodeFromString<GameState>(game.state)
        val after = before + (variable to StateCell(value, "player", now))
        dao.putGame(game.copy(state = json.encodeToString(after), updatedAt = now))
        val payload = buildJsonObject { put("origin", "player"); put("before", json.encodeToJsonElement(before)); put("after", json.encodeToJsonElement(after)); put("completedBefore", Json.parseToJsonElement(game.completed)) }
        dao.putEvent(EventEntity(UUID.randomUUID().toString(), game.id, now, "progress", "Confirmed ${def.label}", payload = payload.toString()))
    }
    suspend fun confirmScan(scan: ScanContext, readings: List<ScanReading>, detectedReadings: List<ScanReading>) = db.withTransaction {
        val game = dao.latestGame()?.takeIf { !it.ended } ?: error("Start a game and scan again")
        val pack = gamePack(game); val now = System.currentTimeMillis()
        val before = json.decodeFromString<GameState>(game.state)
        val update = ProgressScan.confirm(scan, game.id, game.variantId, pack.version, game.updatedAt, now, readings, pack.stateVariables, before, game.score)
        dao.putGame(game.copy(state = json.encodeToString(update.state), score = update.score, updatedAt = now))
        val payload = buildJsonObject {
            put("origin", "player"); put("before", json.encodeToJsonElement(before)); put("after", json.encodeToJsonElement(update.state))
            put("completedBefore", Json.parseToJsonElement(game.completed))
            put("scoreBefore", game.score?.let(::JsonPrimitive) ?: JsonNull); put("scoreAfter", update.score?.let(::JsonPrimitive) ?: JsonNull)
            put("scan", buildJsonObject { put("capturedAt", scan.capturedAt); put("packVersion", scan.packVersion); put("readings", json.encodeToJsonElement(readings)); put("detectedReadings", json.encodeToJsonElement(detectedReadings)) })
        }
        dao.putEvent(EventEntity(UUID.randomUUID().toString(), game.id, now, "progress", "Confirmed display scan: " + readings.joinToString { "${it.variableId ?: it.target} = ${it.value.content}" }, payload = payload.toString()))
    }
    suspend fun undoProgress(variantId: String) = db.withTransaction {
        val game = dao.latestGame()?.takeIf { it.variantId == variantId && !it.ended } ?: error("No active game")
        val event = dao.lastProgress(game.id)?.takeIf { it.kind != "correction" } ?: error("No recent progress to undo on this device")
        val payload = Json.parseToJsonElement(event.payload).jsonObject
        val before = payload["before"] ?: error("Older progress has no recoverable state")
        val completed = payload["completedBefore"] ?: error("Older progress has no recoverable state")
        // Do not undo across a newer state received from another device.
        check(payload["after"] == Json.parseToJsonElement(game.state)) { "Progress changed on another device; review it before correcting" }
        if (payload.containsKey("scoreAfter")) check(payload["scoreAfter"] == (game.score?.let(::JsonPrimitive) ?: JsonNull)) { "Score changed; review it before correcting" }
        val priorCompleted = json.decodeFromJsonElement<List<String>>(completed).toSet()
        val ruleId = payload["ruleId"]?.jsonPrimitive?.content
        val expectedCompleted = if (ruleId != null) priorCompleted + gamePack(game).rules.first { it.id == ruleId }.outcome else priorCompleted
        check(json.decodeFromString<List<String>>(game.completed).toSet() == expectedCompleted) { "Progress changed; cannot undo this report" }
        val now = System.currentTimeMillis()
        val restoredScore = if (payload.containsKey("scoreBefore")) payload["scoreBefore"]?.jsonPrimitive?.longOrNull else game.score
        dao.putGame(game.copy(state = before.toString(), completed = completed.toString(), score = restoredScore, updatedAt = now))
        dao.putEvent(EventEntity(UUID.randomUUID().toString(), game.id, now, "correction", "Undid latest progress report", payload = buildJsonObject { put("origin", "player"); put("supersedes", event.id); put("before", Json.parseToJsonElement(game.state)); put("after", before); put("completedBefore", Json.parseToJsonElement(game.completed)) }.toString()))
    }
    suspend fun saveScore(game: GameEntity, score: Long) { require(score >= 0); dao.putGame(game.copy(score = score, ended = true, updatedAt = System.currentTimeMillis())) }
    suspend fun sync(api: PilotApi) {
        val events=dao.unsynced().take(500)
        val payload=buildJsonObject {
            put("games",JsonArray(dao.allGames().map { g -> buildJsonObject {put("id",g.id);put("variantId",g.variantId);put("startedAt",g.startedAt);put("updatedAt",g.updatedAt);put("score",g.score?.let(::JsonPrimitive)?:JsonNull);put("ended",g.ended);put("completed",Json.parseToJsonElement(g.completed));put("packVersion",g.packVersion?.let(::JsonPrimitive)?:JsonNull);put("state",Json.parseToJsonElement(g.state))} }))
            put("events",JsonArray(events.map { e -> buildJsonObject {put("id",e.id);put("gameId",e.gameId);put("timestamp",e.timestamp);put("kind",e.kind);put("text",e.text);put("payload",Json.parseToJsonElement(e.payload))} }))
            put("learning",JsonArray(dao.allLearning().map { l -> buildJsonObject {put("variantId",l.variantId);put("outcome",l.outcome);put("learnedAt",l.learnedAt)} }))
            put("favourites",JsonArray(dao.allPreferences().filter {it.key.startsWith("favourite:")&&it.value=="true"}.map {JsonPrimitive(it.key.removePrefix("favourite:"))}))
        }
        val result=api.sync(payload)
        db.withTransaction {
            for(item in result["games"]?.jsonArray.orEmpty()) {
                val g=item.jsonObject;val id=g.getValue("id").jsonPrimitive.content
                val remote=GameEntity(id,g.getValue("variantId").jsonPrimitive.content,g.getValue("startedAt").jsonPrimitive.long,g.getValue("updatedAt").jsonPrimitive.long,g.getValue("completed").toString(),g.getValue("ended").jsonPrimitive.boolean,g["score"]?.jsonPrimitive?.longOrNull,g["packVersion"]?.jsonPrimitive?.intOrNull,g["state"]?.toString()?:"{}")
                val local=dao.game(id);if(local==null||remote.updatedAt>local.updatedAt) dao.putGame(remote)
            }
            for(item in result["learning"]?.jsonArray.orEmpty()) {val l=item.jsonObject;dao.putLearning(LearningEntity(l.getValue("variantId").jsonPrimitive.content,l.getValue("outcome").jsonPrimitive.content,l.getValue("learnedAt").jsonPrimitive.long))}
            for(item in result["favourites"]?.jsonArray.orEmpty()) dao.putPreference(PreferenceEntity("favourite:${item.jsonPrimitive.content}","true"))
            events.forEach {dao.markSynced(it.id)}
        }
        for (g in dao.allGames()) {
            val version = g.packVersion ?: continue
            if (dao.archived(g.variantId, version) == null) {
                val pack = downloadPack(api, g.variantId, version)
                dao.archive(ArchivedPack(g.variantId, version, json.encodeToString(pack)))
            }
        }
    }
    suspend fun updatePacks(api: PilotApi) = withContext(Dispatchers.IO) {
        for(entry in api.catalogue()) {
            val id=entry.jsonObject.getValue("variantId").jsonPrimitive.content
            val version=entry.jsonObject.getValue("version").jsonPrimitive.int
            if((dao.pack(id)?.version?:0)>=version) continue
            val local=downloadPack(api,id,version)
            install(json.encodeToString(local))
        }
    }
    private suspend fun downloadPack(api: PilotApi, id: String, version: Int): MachinePack = withContext(Dispatchers.IO) {
        require(id.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,99}")))
        val remote = json.decodeFromString<MachinePack>(api.pack(id,version))
        PackValidation.validate(remote)
        require(remote.variantId == id && remote.version == version)
        if(remote.demo) remote else {
            val image=api.playfield(remote.image.path)
            val file=File(context.filesDir,"playfields/$id-$version.img");file.parentFile?.mkdirs();file.writeBytes(image)
            remote.copy(image=remote.image.copy(path=UriFile.path(file)))
        }
    }
}
private object UriFile { fun path(file: File): String = android.net.Uri.fromFile(file).toString() }
