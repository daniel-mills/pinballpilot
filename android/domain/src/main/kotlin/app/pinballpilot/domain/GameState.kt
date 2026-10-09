package app.pinballpilot.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class StateVariable(val id: String, val label: String, val type: String, val scope: String, val initial: JsonPrimitive? = null, val modeId: String? = null, val values: List<String>? = null)
@Serializable data class Predicate(val variable: String, val op: String, val value: JsonPrimitive? = null)
@Serializable data class Conditions(val all: List<Predicate> = emptyList(), val any: List<Predicate> = emptyList())
@Serializable data class Effect(val variable: String, val op: String, val value: JsonPrimitive? = null)
@Serializable data class StateCell(val value: JsonPrimitive? = null, val origin: String, val observedAt: Long)
typealias GameState = Map<String, StateCell>
@Serializable data class RuleSpec(val ruleId: String, val shotId: String, val prerequisites: List<String>, val outcome: String, val repeatable: Boolean, val conditions: Conditions, val effects: List<Effect>, val variables: List<StateVariable>)

object StateEngine {
    private const val MAX_SAFE = 9007199254740991L
    fun valid(def: StateVariable, value: JsonPrimitive?): Boolean {
        if (value == null || value == JsonNull) return true
        return when (def.type) {
            "boolean" -> !value.isString && value.booleanOrNull != null
            "enum" -> value.isString && value.content in def.values.orEmpty()
            "counter", "timer" -> !value.isString && value.longOrNull?.let { it in 0..MAX_SAFE } == true
            else -> false
        }
    }
    fun initial(defs: List<StateVariable>, at: Long): GameState = defs.associate { it.id to StateCell(it.initial, "initial", at) }
    fun eligible(conditions: Conditions?, state: GameState, now: Long): Boolean {
        fun test(p: Predicate): Boolean {
            val cell = state[p.variable] ?: return false
            val value = cell.value ?: return false
            if (value == JsonNull || cell.origin == "inferred") return false
            if (p.op == "eq") return value == p.value
            if (value.isString) return false
            val n = value.longOrNull ?: return false
            return when (p.op) {
                "active" -> n > now
                "expired" -> n > 0 && n <= now
                "gte" -> p.value?.longOrNull?.let { n >= it } == true
                "lt" -> p.value?.longOrNull?.let { n < it } == true
                else -> false
            }
        }
        return conditions == null || conditions.all.all(::test) && (conditions.any.isEmpty() || conditions.any.any(::test))
    }
    fun apply(defs: List<StateVariable>, state: GameState, effects: List<Effect>, at: Long): GameState {
        val next = state.toMutableMap()
        for (e in effects) {
            val d = defs.firstOrNull { it.id == e.variable } ?: error("Unknown state variable")
            val value = when (e.op) {
                "reset" -> d.initial
                "set" -> e.value
                "increment" -> {
                    val cell = next[d.id]
                    require(d.type == "counter" && cell?.origin != "inferred" && StateEngine.valid(d, cell?.value))
                    val current = cell?.value?.longOrNull ?: error("Confirm ${d.label} before incrementing it")
                    val amount = e.value?.longOrNull ?: error("Invalid increment")
                    JsonPrimitive(Math.addExact(current, amount))
                }
                "startTimer" -> {
                    val duration = e.value?.longOrNull ?: error("Invalid timer")
                    require(d.type == "timer" && duration > 0)
                    JsonPrimitive(Math.addExact(at, duration))
                }
                else -> error("Unsupported state effect")
            }
            require(valid(d, value)) { "Invalid value for ${d.label}" }
            next[d.id] = StateCell(value, "player", at)
        }
        return next
    }
    fun reset(defs: List<StateVariable>, state: GameState, scope: String, at: Long, modeId: String? = null): GameState =
        apply(defs, state, defs.filter { it.scope == scope && (scope != "mode" || it.modeId == modeId) }.map { Effect(it.id, "reset") }, at)
}
