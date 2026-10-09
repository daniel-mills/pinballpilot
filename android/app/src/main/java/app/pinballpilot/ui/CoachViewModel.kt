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

data class CoachState(val packs: List<MachinePack> = emptyList(), val pack: MachinePack? = null, val objective: String = "scoring", val advanced: Boolean = false, val completed: Set<String> = emptySet(), val selected: String? = null, val games: List<GameEntity> = emptyList(), val learned: Int = 0, val autoNarrate: Boolean = true, val prompts: Boolean = false, val favourite: Boolean = false, val message: String = "", val busy: Boolean = false, val answer: String = "", val observation: JsonObject? = null, val active: Boolean = false) {
    val recommendations get() = pack?.let { StrategyEngine().rank(it, objective, advanced, completed) }.orEmpty()
    val next get() = recommendations.firstOrNull()
}

@Singleton class CoachSession @Inject constructor(private val repo: PilotRepository, val api: PilotApi, val narrator: Narrator) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(CoachState())
    val state = mutable.asStateFlow()
    init {
        scope.launch { repo.seed() }
        scope.launch { combine(repo.packs, repo.games, repo.learning, repo.preferences) { packs, games, learning, prefs ->
            mutable.update { old ->
                val pack = packs.find { it.variantId == old.pack?.variantId } ?: packs.firstOrNull()
                val game = games.firstOrNull()?.takeIf { it.variantId == pack?.variantId && !it.ended && System.currentTimeMillis()-it.updatedAt < 30*60_000 }
                old.copy(packs = packs, pack = pack, games = games, completed = game?.let { Json.decodeFromString<List<String>>(it.completed).toSet() }.orEmpty(), learned = learning.count { it.variantId == pack?.variantId }, autoNarrate = prefs["autoNarrate"] != "false", prompts = prefs["prompts"] == "true", favourite = prefs["favourite:${pack?.variantId}"] == "true")
            }
        }.collect() }
        scope.launch { state.map { Triple(it.active && it.autoNarrate, it.next?.rule?.id, it.next?.rule?.let { r -> "${r.instruction} ${r.why}" }) }.distinctUntilChanged().collect { (enabled, _, text) -> if (enabled && text != null) narrator.speak(text) } }
    }
    fun open(id: String) { mutable.update { it.copy(pack = it.packs.find { p -> p.variantId == id }, selected = null, active = true, answer = "") } }
    fun active(value: Boolean) { mutable.update { it.copy(active = value) } }
    fun objective(value: String) { mutable.update { it.copy(objective = value, selected = null) } }
    fun advanced(value: Boolean) { mutable.update { it.copy(advanced = value, selected = null) } }
    fun select(value: String?) { mutable.update { it.copy(selected = value) } }
    fun message(value: String) { mutable.update { it.copy(message = value) } }
    fun preference(key: String, value: String) { scope.launch { repo.preference(key, value) } }
    fun speakNext() { val next = state.value.next; narrator.speak(next?.let { "${it.rule.instruction} ${it.rule.why}" } ?: "You have completed this guide. Start a fresh game when you are ready.") }
    fun complete() { launch { val s = state.value; val next = s.next ?: return@launch; repo.record(s.pack!!.variantId, "progress", next.rule.instruction, next.rule.outcome) } }
    fun newGame() { launch { val id = state.value.pack?.variantId ?: return@launch; repo.newGame(id); message("Fresh game. Your learning history is retained.") } }
    fun saveScore(game: GameEntity, score: Long) { launch { repo.saveScore(game, score) } }
    fun signIn(email: String) { launch { api.signIn(email); message("Open the email link on this phone to sign in.") } }
    fun callback(uri: Uri) { launch { api.callback(uri); message("Signed in. You can now sync your private history.") } }
    fun sync() { launch { repo.sync(api); message("Games, learning and private activity synced.") } }
    fun updatePacks() { launch {repo.updatePacks(api);message("Your machine packs are up to date.")} }
    fun ask(question: String) { launch {
        val id = state.value.pack?.variantId ?: return@launch
        val result = api.ask(id, question, state.value.completed.toList())
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
