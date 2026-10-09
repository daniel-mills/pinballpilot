package app.pinballpilot.domain

import kotlinx.serialization.Serializable

@Serializable data class Point(val x: Float, val y: Float)
@Serializable data class Geometry(val point: Point, val polygon: List<Point>? = null)
@Serializable data class Shot(val id: String, val name: String, val type: String, val geometry: Geometry, val description: String, val difficulty: Double, val risk: Double)
@Serializable data class Rule(val id: String, val shotId: String, val claimIds: List<String>, val instruction: String, val why: String, val prerequisites: List<String>, val outcome: String, val value: Double, val progression: Double, val repeatable: Boolean = false, val conditions: Conditions? = null, val effects: List<Effect> = emptyList())
@Serializable data class Strategy(val id: String, val title: String, val objective: String, val level: String, val claimIds: List<String>, val steps: List<String>)
@Serializable data class Applicability(val status: String, val releaseIds: List<String>, val settings: String)
@Serializable data class Citation(val sourceId: String, val sourceRevision: Int, val locator: String, val relation: String)
@Serializable data class SoftwareRelease(val id: String, val variantId: String, val version: String, val releasedAt: String? = null)
@Serializable data class Claim(val id: String, val variantId: String, val kind: String, val title: String, val body: String, val sourceIds: List<String>, val status: String, val revision: Int, val reviewedRevision: Int? = null, val reviewNote: String = "", val software: String = "", val applicability: Applicability? = null, val citations: List<Citation> = emptyList(), val ruleSpec: RuleSpec? = null)
@Serializable data class KnowledgeSource(val id: String, val title: String, val url: String, val kind: String, val locator: String, val accessedAt: String, val notes: String, val revision: Int? = null, val contentHash: String? = null)
@Serializable data class PackImage(val path: String, val license: String, val rightsConfirmed: Boolean, val width: Int, val height: Int)
@Serializable data class MachinePack(val schemaVersion: Int, val variantId: String, val version: Int, val name: String, val edition: String, val manufacturer: String, val demo: Boolean, val image: PackImage, val shots: List<Shot>, val rules: List<Rule>, val strategies: List<Strategy>, val claims: List<Claim>, val sources: List<KnowledgeSource>, val engineVersion: Int? = null, val applicability: Applicability? = null, val stateVariables: List<StateVariable> = emptyList(), val releases: List<SoftwareRelease> = emptyList())
data class Recommendation(val rule: Rule, val shot: Shot, val score: Double)

/** Pure domain ranking; AI and UI cannot override rule prerequisites. */
class StrategyEngine {
    fun rank(pack: MachinePack, objective: String, advanced: Boolean, completed: Set<String>, state: GameState = emptyMap(), now: Long = System.currentTimeMillis()): List<Recommendation> {
        val guide = pack.strategies.find { it.objective == objective && it.level == if (advanced && objective == "scoring") "advanced" else "simple" } ?: return emptyList()
        val approved = pack.claims.filter { it.status == "approved" && it.revision == it.reviewedRevision && it.variantId == pack.variantId }.map { it.id }.toSet()
        if (!guide.claimIds.all { it in approved }) return emptyList()
        return pack.rules.filter { it.id in guide.steps && (it.repeatable || it.outcome !in completed) && it.prerequisites.all(completed::contains) && it.claimIds.all(approved::contains) && StateEngine.eligible(it.conditions, state, now) }
            .mapNotNull { rule -> pack.shots.find { it.id == rule.shotId }?.let { shot ->
                Recommendation(rule, shot, rule.value * (if (advanced) 4 else 2) + rule.progression * (if (objective == "multiball") 5 else 2) - shot.risk * (if (advanced) 1 else 3) - shot.difficulty * (if (advanced) 1 else 2))
            } }.sortedWith(compareByDescending<Recommendation> { it.score }.thenBy { it.rule.id })
    }
}

/** Coordinates are image-relative. Future camera homography belongs in presentation. */
object Coordinates {
    fun normalise(x: Float, y: Float, width: Float, height: Float): Point {
        require(width > 0 && height > 0)
        return Point((x / width).coerceIn(0f, 1f), (y / height).coerceIn(0f, 1f))
    }
    fun contains(point: Point, polygon: List<Point>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val a = polygon[i]; val b = polygon[j]
            if ((a.y > point.y) != (b.y > point.y) && point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }
}

enum class VoiceCommand { NEXT, REPEAT, COMPLETE, NEW_GAME, MULTIBALL, SCORING, STOP, QUESTION }
object VoiceCommands {
    fun parse(text: String): VoiceCommand = when (text.lowercase().trim().removePrefix("hey pinball").trim(' ', ',', '.', '!')) {
        "next", "what next", "what should i shoot", "what should i shoot next" -> VoiceCommand.NEXT
        "repeat", "say that again" -> VoiceCommand.REPEAT
        "done", "completed", "i made that shot" -> VoiceCommand.COMPLETE
        "new game", "start a new game" -> VoiceCommand.NEW_GAME
        "multiball", "multiball guide" -> VoiceCommand.MULTIBALL
        "scoring", "scoring guide" -> VoiceCommand.SCORING
        "stop", "stop listening" -> VoiceCommand.STOP
        else -> VoiceCommand.QUESTION
    }
}

data class GameActivity(val variantId: String, val timestamp: Long, val ball: Int? = null, val score: Long? = null, val confirmedGameOver: Boolean = false)
object GameGrouping {
    // A conservative grouping hint. Unknown photos never reset a game by themselves.
    fun startsNew(previous: GameActivity?, next: GameActivity): Boolean = previous == null ||
        previous.variantId != next.variantId || previous.confirmedGameOver ||
        next.timestamp - previous.timestamp > 30 * 60_000 ||
        (next.ball == 1 && previous.ball != null && previous.ball > 1 && next.score != null && previous.score != null && next.score < previous.score)
}
