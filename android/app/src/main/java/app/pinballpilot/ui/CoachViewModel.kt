package app.pinballpilot.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pinballpilot.data.*
import app.pinballpilot.domain.*
import app.pinballpilot.voice.Narrator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

data class CoachState(val packs: List<MachinePack> = emptyList(), val pack: MachinePack? = null, val objective: String = "scoring", val advanced: Boolean = false, val completed: Set<String> = emptySet(), val selected: String? = null, val games: List<GameEntity> = emptyList(), val learned: Int = 0, val autoNarrate: Boolean = true, val prompts: Boolean = false, val favourite: Boolean = false, val message: String = "", val busy: Boolean = false, val answer: String = "", val observation: JsonObject? = null, val active: Boolean = false, val gameState: GameState = emptyMap(), val now: Long = System.currentTimeMillis(), val scanContext: ScanContext? = null, val scanCapture: Boolean = false, val scanResult: ScanResult? = null) {
    val recommendations get() = pack?.let { StrategyEngine().rank(it, objective, advanced, completed, gameState, now) }.orEmpty()
    val next get() = recommendations.firstOrNull()
}

@Singleton class CoachSession @Inject constructor(private val repo: PilotRepository, val api: PilotApi, val narrator: Narrator) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(CoachState())
    val state = mutable.asStateFlow()
    init {
        scope.launch { repo.seed() }
        scope.launch { combine(repo.packs, repo.games, repo.learning, repo.preferences, repo.archives) { packs, games, learning, prefs, archives ->
            mutable.update { old ->
                val latest = packs.find { it.variantId == (prefs["selectedMachine"] ?: old.pack?.variantId) } ?: packs.firstOrNull()
                val game = games.firstOrNull()?.takeIf { it.variantId == latest?.variantId && !it.ended && System.currentTimeMillis()-it.updatedAt < 30*60_000 }
                val pack = if (game?.packVersion != null) archives.find { it.variantId == game.variantId && it.version == game.packVersion } else latest
                old.copy(packs = packs, pack = pack, games = games, completed = game?.let { Json.decodeFromString<List<String>>(it.completed).toSet() }.orEmpty(), gameState = game?.let { Json.decodeFromString<GameState>(it.state) } ?: StateEngine.initial(pack?.stateVariables.orEmpty(), System.currentTimeMillis()), learned = learning.count { it.variantId == pack?.variantId }, autoNarrate = prefs["autoNarrate"] != "false", prompts = prefs["prompts"] == "true", favourite = prefs["favourite:${pack?.variantId}"] == "true")
            }
        }.collect() }
        scope.launch { while (isActive) { delay(1000); mutable.update { it.copy(now = System.currentTimeMillis()) } } }
        scope.launch { state.map { Triple(it.active && it.autoNarrate, it.next?.rule?.id, it.next?.rule?.let { r -> "${r.instruction} ${r.why}" }) }.distinctUntilChanged().collect { (enabled, _, text) -> if (enabled && text != null) narrator.speak(text) } }
    }
    fun open(id: String) { launch {
        val previous = state.value.games.firstOrNull()
        if (previous == null || previous.variantId != id || previous.ended || System.currentTimeMillis()-previous.updatedAt >= 30*60_000) repo.newGame(id)
        val (p,game) = repo.activeSession(id)
        mutable.update { it.copy(pack = p, completed = Json.decodeFromString<List<String>>(game.completed).toSet(), gameState = Json.decodeFromString<GameState>(game.state), selected = null, active = true, answer = "") }
        // Re-emit the selected game so its pinned guide/state wins over catalogue defaults.
        repo.preference("selectedMachine", id)
    } }
    fun active(value: Boolean) { mutable.update { it.copy(active = value) } }
    fun objective(value: String) { mutable.update { it.copy(objective = value, selected = null) } }
    fun advanced(value: Boolean) { mutable.update { it.copy(advanced = value, selected = null) } }
    fun select(value: String?) { mutable.update { it.copy(selected = value) } }
    fun message(value: String) { mutable.update { it.copy(message = value) } }
    fun preference(key: String, value: String) { scope.launch { repo.preference(key, value) } }
    fun speakNext() { val next = state.value.next; narrator.speak(next?.let { "${it.rule.instruction} ${it.rule.why}" } ?: "No verified next step is available from the current progress. Check the machine and your progress before continuing.") }
    fun complete() { launch { val s = state.value; val next = s.next ?: return@launch; repo.record(s.pack!!.variantId, "progress", next.rule.instruction, next.rule.outcome, next.rule.id, s.pack.version) } }
    fun endBall() { launch { repo.endBall(state.value.pack?.variantId ?: return@launch); message("Ball ended. Ball progress has reset; game progress is retained.") } }
    fun undoProgress() { launch { repo.undoProgress(state.value.pack?.variantId ?: return@launch); message("Latest progress report corrected.") } }
    fun confirmState(variable: String, value: JsonPrimitive?) { launch { repo.confirmState(state.value.pack?.variantId ?: return@launch, variable, value) } }
    fun newGame() { launch { val id = state.value.pack?.variantId ?: return@launch; repo.newGame(id); message("Fresh game. Your learning history is retained.") } }
    fun saveScore(game: GameEntity, score: Long) { launch { repo.saveScore(game, score) } }
    fun signIn(email: String) { launch { api.signIn(email); message("Open the email link on this phone to sign in.") } }
    fun callback(uri: Uri) { launch { api.callback(uri); message("Signed in. You can now sync your private history.") } }
    fun sync() { launch { repo.sync(api); message("Games, learning and private activity synced.") } }
    fun updatePacks() { launch {repo.updatePacks(api);message("Your machine packs are up to date.")} }
    fun ask(question: String) { launch {
        val id = state.value.pack?.variantId ?: return@launch
        val result = api.ask(id, question, state.value.completed.toList(), state.value.pack?.version, state.value.gameState)
        val kind = result["kind"]?.jsonPrimitive?.content ?: "unknown"
        val answer = result["answer"]?.jsonPrimitive?.content ?: "I do not have enough verified information."
        val label = if (kind == "ai_suggestion") "AI suggestion: " else ""
        mutable.update { it.copy(answer = label + answer, selected = result["shotIds"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content) }
        repo.record(id, "question", question); repo.record(id, "advice", label + answer)
        if (state.value.autoNarrate) narrator.speak(label + answer)
    } }
    fun analyse(photos: List<String>) { launch {
        // Identification is not confirmation: never attach an unknown machine to the open game.
        val result=api.analyse(photos)
        mutable.update { it.copy(observation = result) }
    } }
    fun beginScan() { launch {
        check(api.configured) { "Scanning needs a connected backend. You can update progress manually." }
        val id = state.value.pack?.variantId ?: error("Open a machine guide first")
        val (pack, game) = repo.activeSession(id)
        narrator.stop()
        mutable.update { it.copy(scanContext = ScanContext(game.id, id, pack.version, game.updatedAt, System.currentTimeMillis()), scanCapture = true, scanResult = null) }
    } }
    fun scanCaptured(photos: List<String>) { launch {
        val context = state.value.scanContext ?: return@launch
        mutable.update { it.copy(scanCapture = false) }
        val result = api.scan(context.variantId, context.packVersion, photos)
        val (pack, _) = repo.activeSession(context.variantId)
        ProgressScan.validate(result.readings, pack.stateVariables)
        if (state.value.scanContext == context) mutable.update { it.copy(scanResult = result) }
    } }
    fun confirmScan(readings: List<ScanReading>) { launch {
        val s = state.value
        val result = s.scanResult ?: error("Scan again")
        check(!result.machineMismatch && s.pack?.variantId == s.scanContext?.variantId) { "Scan this machine again before confirming." }
        check(readings.all { r -> result.readings.any { it.key == r.key } })
        repo.confirmScan(s.scanContext ?: error("Scan again"), readings, result.readings)
        dismissScan()
        message("Selected readings saved. Use Ball ended if you still need to reset ball progress.")
    } }
    fun dismissScan() { mutable.update { it.copy(scanContext = null, scanResult = null, scanCapture = false) } }
    fun confirmMachine(name:String) {
        val pack=state.value.packs.find {it.name.equals(name,ignoreCase=true)}
        if(pack==null) message("No approved downloaded guide for $name yet. Download guide updates after its content is published.") else open(pack.variantId)
        dismissObservation()
    }
    fun dismissObservation() { mutable.update { it.copy(observation = null) } }
    fun voice(text: String) {
        when (VoiceCommands.parse(text)) {
            VoiceCommand.NEXT, VoiceCommand.REPEAT -> speakNext()
            VoiceCommand.COMPLETE -> complete()
            VoiceCommand.NEW_GAME -> newGame()
            VoiceCommand.MULTIBALL -> objective("multiball")
            VoiceCommand.SCORING -> objective("scoring")
            VoiceCommand.STOP -> narrator.stop()
            VoiceCommand.QUESTION -> ask(text)
        }
    }
    private fun launch(block: suspend () -> Unit) { scope.launch {
        mutable.update { it.copy(busy = true, message = "") }
        try { block() } catch (e: Exception) { if (e is CancellationException) throw e; message(e.message ?: "Could not complete this action. Your offline guide is still available.") }
        finally { mutable.update { it.copy(busy = false) } }
    } }
}

@HiltViewModel class CoachViewModel @Inject constructor(val session: CoachSession) : ViewModel() { val state = session.state }
