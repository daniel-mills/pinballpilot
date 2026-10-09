package app.pinballpilot.domain

object PackValidation {
    fun validate(p: MachinePack) {
        require(p.schemaVersion in 1..2 && p.image.rightsConfirmed) { "Unsupported or unlicensed pack" }
        require(p.claims.all { it.status == "approved" && it.reviewedRevision == it.revision && it.variantId == p.variantId }) { "Unreviewed evidence" }
        require(p.shots.all { it.geometry.point.x in 0f..1f && it.geometry.point.y in 0f..1f }) { "Invalid geometry" }
        for (items in listOf(p.shots.map { it.id }, p.rules.map { it.id }, p.claims.map { it.id }, p.sources.map { it.id }, p.stateVariables.map { it.id })) require(items.distinct().size == items.size) { "Duplicate identifier" }
        val stateful = p.rules.filter { it.repeatable || it.conditions != null || it.effects.isNotEmpty() }
        if (p.schemaVersion == 1) require(stateful.isEmpty() && p.stateVariables.isEmpty()) { "Stateful rules require version 2" }
        if (p.schemaVersion == 2) {
            require(p.engineVersion == 1 && p.applicability != null)
            for (d in p.stateVariables) {
                require(d.type in listOf("boolean", "counter", "enum", "timer") && d.scope in listOf("game", "ball", "mode") && StateEngine.valid(d, d.initial))
                require(d.scope != "mode" || d.modeId != null)
                require(d.type != "enum" || !d.values.isNullOrEmpty())
                require(d.type != "timer" || d.initial == null || d.initial.toString() in listOf("null", "0"))
            }
            require(p.releases.all { it.variantId == p.variantId })
            for (a in listOf(p.applicability) + p.claims.map { it.applicability }) {
                require(a != null && a.status in listOf("unknown", "releases", "not_applicable"))
                require((a.status == "releases") == a.releaseIds.isNotEmpty())
                require(a.releaseIds.all { id -> p.releases.any { it.id == id } })
            }
            for (c in p.claims) {
                require(c.citations.any { it.relation == "supports" }) { "Missing supporting evidence" }
                require(c.sourceIds.all { id -> c.citations.any { it.sourceId == id } })
                require(c.citations.all { e -> e.locator.isNotBlank() && e.sourceId in c.sourceIds && p.sources.any { it.id == e.sourceId && it.revision == e.sourceRevision } }) { "Stale evidence revision" }
                if (p.applicability.status == "releases" && c.applicability?.status == "releases") require(c.applicability.releaseIds.containsAll(p.applicability.releaseIds))
            }
            for (r in stateful) {
                val spec = RuleSpec(r.id, r.shotId, r.prerequisites, r.outcome, r.repeatable, r.conditions ?: Conditions(), r.effects, p.stateVariables)
                require(p.claims.any { it.id in r.claimIds && it.kind == "recommendation" && it.ruleSpec == spec }) { "Unreviewed state behaviour" }
                for (test in r.conditions?.let { it.all + it.any }.orEmpty()) {
                    val d = p.stateVariables.firstOrNull { it.id == test.variable } ?: error("Unknown state variable")
                    require(when (test.op) {
                        "active", "expired" -> d.type == "timer" && test.value == null
                        "eq" -> test.value != null && StateEngine.valid(d, test.value)
                        "gte", "lt" -> d.type == "counter" && test.value != null && StateEngine.valid(d, test.value)
                        else -> false
                    })
                }
                for (effect in r.effects) {
                    val d = p.stateVariables.firstOrNull { it.id == effect.variable } ?: error("Unknown state variable")
                    require(when (effect.op) {
                        "reset" -> effect.value == null
                        "set" -> effect.value != null && StateEngine.valid(d, effect.value)
                        "increment" -> d.type == "counter" && effect.value?.toString()?.toLongOrNull() != null
                        "startTimer" -> d.type == "timer" && (effect.value?.toString()?.toLongOrNull() ?: 0) > 0
                        else -> false
                    })
                }
            }
        }
        for (r in p.rules) require(p.shots.any { it.id == r.shotId } && r.claimIds.isNotEmpty() && r.claimIds.all { id -> p.claims.any { it.id == id } })
    }
}
