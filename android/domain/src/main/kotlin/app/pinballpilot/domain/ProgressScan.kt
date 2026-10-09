package app.pinballpilot.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class ScanReading(val target: String, val variableId: String? = null, val value: JsonPrimitive, val evidence: String, val confidence: Double) {
    val key get() = "$target:${variableId.orEmpty()}"
}
@Serializable data class ScanResult(val machineMismatch: Boolean, val readings: List<ScanReading>, val note: String, val requiresConfirmation: Boolean = true)
data class ScanContext(val gameId: String, val variantId: String, val packVersion: Int, val gameUpdatedAt: Long, val capturedAt: Long)
data class ScanUpdate(val state: GameState, val score: Long?)

object ProgressScan {
    fun validate(readings: List<ScanReading>, defs: List<StateVariable>) {
        require(readings.size <= 12 && readings.map { it.key }.distinct().size == readings.size) { "Duplicate or excessive scan readings" }
        for (r in readings) {
            require(r.confidence.isFinite() && r.confidence in 0.0..1.0 && r.evidence.isNotBlank() && r.evidence.length <= 240)
            require(r.value != JsonNull)
            when (r.target) {
                "state" -> {
                    val def = defs.firstOrNull { it.id == r.variableId } ?: error("Unrecognised progress field")
                    require(def.type != "timer" && StateEngine.valid(def, r.value)) { "Unsupported progress value" }
                }
                "score", "ball" -> {
                    require(r.variableId == null && !r.value.isString)
                    val n = r.value.longOrNull ?: error("Invalid display number")
                    require(n in (if (r.target == "ball") 1L..99L else 0L..9007199254740991L))
                }
                else -> error("Unsupported scan reading")
            }
        }
    }
    fun confirm(context: ScanContext, gameId: String, variantId: String, version: Int, updatedAt: Long, now: Long, readings: List<ScanReading>, defs: List<StateVariable>, state: GameState, score: Long?): ScanUpdate {
        require(context.gameId == gameId && context.variantId == variantId && context.packVersion == version && context.gameUpdatedAt == updatedAt) { "Your game changed. Scan again before saving progress." }
        require(now - context.capturedAt in 0..120_000) { "This scan is out of date. Scan the display again." }
        require(readings.isNotEmpty()) { "Select at least one reading to confirm." }
        validate(readings, defs)
        val next = state.toMutableMap()
        readings.filter { it.target == "state" }.forEach { next[it.variableId!!] = StateCell(it.value, "player", context.capturedAt) }
        // A displayed ball number is recorded as evidence, never as an implicit ball-end event.
        return ScanUpdate(next, readings.firstOrNull { it.target == "score" }?.value?.long ?: score)
    }
}
