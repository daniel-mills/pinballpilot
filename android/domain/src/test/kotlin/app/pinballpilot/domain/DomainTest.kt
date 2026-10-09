package app.pinballpilot.domain

import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

class DomainTest {
    private fun pack() = Json.decodeFromString<MachinePack>(File("../app/src/main/assets/demo-pack.json").readText())
    @Test fun multiballPrerequisites() {
        val p = pack(); val done = mutableSetOf<String>(); val engine = StrategyEngine()
        for (id in listOf("light-lock", "lock-one", "lock-two", "lock-three", "jackpot")) {
            val next = engine.rank(p, "multiball", false, done).first()
            assertEquals(id, next.rule.id); done += next.rule.outcome
        }
        assertTrue(engine.rank(p, "multiball", false, done).isEmpty())
    }
    @Test fun refusesStaleEvidence() {
        val p = pack(); val stale = p.copy(claims = p.claims.map { it.copy(revision = 2) })
        assertTrue(StrategyEngine().rank(stale, "scoring", false, emptySet()).isEmpty())
    }
    @Test fun advancedIsSeparate() {
        assertEquals("mode-qualify", StrategyEngine().rank(pack(), "scoring", true, emptySet()).first().rule.id)
    }
    @Test fun coordinateMappingAndPolygons() {
        assertEquals(Point(.5f, .5f), Coordinates.normalise(300f, 550f, 600f, 1100f))
        val square = listOf(Point(.2f,.2f), Point(.8f,.2f),Point(.8f,.8f),Point(.2f,.8f))
        assertTrue(Coordinates.contains(Point(.5f,.5f), square)); assertFalse(Coordinates.contains(Point(.1f,.1f), square))
    }
    @Test fun doesNotInventProgressFromSpeech() {
        assertEquals(VoiceCommand.QUESTION, VoiceCommands.parse("I think multiball might be ready"))
        assertEquals(VoiceCommand.COMPLETE, VoiceCommands.parse("Hey Pinball, I made that shot"))
    }
    @Test fun groupsUnknownActivityAndAllowsExplicitEnd() {
        val a = GameActivity("maiden", 1000)
        assertFalse(GameGrouping.startsNew(a, GameActivity("maiden",2000)))
        assertTrue(GameGrouping.startsNew(a.copy(confirmedGameOver=true),GameActivity("maiden",2000)))
        assertTrue(GameGrouping.startsNew(a,GameActivity("other",2000)))
        assertFalse(GameGrouping.startsNew(a.copy(ball=3),GameActivity("maiden",2000,ball=1)))
    }
    private val variables = listOf(
        StateVariable("locks", "Lock credits", "counter", "game", JsonPrimitive(0)),
        StateVariable("bonus", "Ball bonus", "counter", "ball", JsonPrimitive(0)),
        StateVariable("save", "Ball save", "timer", "mode", JsonPrimitive(0), "multiball"),
        StateVariable("qualified", "Qualification", "boolean", "game"),
    )
    @Test fun unknownAndInferredAreNotConfirmedProgress() {
        val condition = Conditions(all = listOf(Predicate("qualified", "eq", JsonPrimitive(false))))
        val initial = StateEngine.initial(variables, 0)
        assertFalse(StateEngine.eligible(condition, initial, 0))
        assertFalse(StateEngine.eligible(condition, initial + ("qualified" to StateCell(JsonPrimitive(false), "inferred", 0)), 0))
        assertTrue(StateEngine.eligible(condition, initial + ("qualified" to StateCell(JsonPrimitive(false), "player", 0)), 0))
    }
    @Test fun repeatsCountersAndCanBeginAnotherCycle() {
        val p = pack(); val old = p.rules.first { it.id == "ramp-build" }
        val rule = old.copy(repeatable = true, conditions = Conditions(all = listOf(Predicate("locks", "lt", JsonPrimitive(3)))), effects = listOf(Effect("locks", "increment", JsonPrimitive(1))))
        val changed = p.copy(rules = p.rules.map { if (it.id == rule.id) rule else it }, stateVariables = variables)
        var state = StateEngine.initial(variables, 1000)
        repeat(3) {
            assertTrue(StrategyEngine().rank(changed,"scoring",false,setOf(rule.outcome),state,1000).any { it.rule.id == rule.id })
            state = StateEngine.apply(variables,state,rule.effects,1000)
        }
        assertFalse(StrategyEngine().rank(changed,"scoring",false,setOf(rule.outcome),state,1000).any { it.rule.id == rule.id })
        state = StateEngine.apply(variables,state,listOf(Effect("locks","reset")),2000)
        assertTrue(StrategyEngine().rank(changed,"scoring",false,setOf(rule.outcome),state,2000).any { it.rule.id == rule.id })
    }
    @Test fun timersAndScopeResetsMatchWebEngine() {
        val state = StateEngine.apply(variables,StateEngine.initial(variables,1000),listOf(Effect("locks","increment",JsonPrimitive(2)),Effect("bonus","increment",JsonPrimitive(4)),Effect("save","startTimer",JsonPrimitive(30000))),1000)
        val next = StateEngine.reset(variables,state,"ball",2000)
        assertEquals(JsonPrimitive(2),next["locks"]!!.value);assertEquals(JsonPrimitive(0),next["bonus"]!!.value)
        assertTrue(StateEngine.eligible(Conditions(all=listOf(Predicate("save","active"))),next,30999))
        assertTrue(StateEngine.eligible(Conditions(any=listOf(Predicate("save","expired"))),next,31000))
        assertEquals(JsonPrimitive(31000),StateEngine.reset(variables,next,"mode",2000,"other")["save"]!!.value)
        val reset = StateEngine.reset(variables,next,"mode",2000,"multiball")
        assertFalse(StateEngine.eligible(Conditions(any=listOf(Predicate("save","expired"))),reset,50000))
    }
    @Test fun rejectsInvalidStateAndKeepsOriginalSnapshot() {
        val state = StateEngine.initial(variables,0)
        assertFails { StateEngine.apply(variables,state,listOf(Effect("locks","increment",JsonPrimitive(-1))),0) }
        assertFails { StateEngine.apply(variables,state + ("locks" to StateCell(null,"player",0)),listOf(Effect("locks","increment",JsonPrimitive(1))),0) }
        assertEquals(JsonPrimitive(0),state["locks"]!!.value)
    }
    @Test fun legacyPackCannotSmuggleStatefulRules() {
        PackValidation.validate(pack())
        assertFails { PackValidation.validate(pack().copy(rules=pack().rules.map { it.copy(repeatable=true) })) }
    }
    @Test fun versionTwoRequiresExactReviewedBehaviourAndSourceRevision() {
        val base=pack(); val old=base.rules.first { it.id=="ramp-build" }
        val rule=old.copy(repeatable=true,conditions=Conditions(all=listOf(Predicate("locks","lt",JsonPrimitive(3)))),effects=listOf(Effect("locks","increment",JsonPrimitive(1))))
        val spec=RuleSpec(rule.id,rule.shotId,rule.prerequisites,rule.outcome,true,rule.conditions!!,rule.effects,variables)
        val applicability=Applicability("unknown",emptyList(),"")
        val modern=base.copy(schemaVersion=2,engineVersion=1,applicability=applicability,stateVariables=variables,
            sources=base.sources.map { it.copy(revision=1) },rules=base.rules.map { if(it.id==rule.id) rule else it },
            claims=base.claims.map { c -> c.copy(applicability=applicability,citations=c.sourceIds.map { id->Citation(id,1,base.sources.first { it.id==id }.locator,"supports") },ruleSpec=if(c.id in rule.claimIds&&c.kind=="recommendation") spec else null) })
        PackValidation.validate(modern)
        assertFails { PackValidation.validate(modern.copy(sources=modern.sources.map { it.copy(revision=2) })) }
        assertFails { PackValidation.validate(modern.copy(rules=modern.rules.map { if(it.id==rule.id) it.copy(shotId="right-ramp") else it })) }
    }
}
