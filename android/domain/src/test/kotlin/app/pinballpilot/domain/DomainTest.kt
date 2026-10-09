package app.pinballpilot.domain

import kotlin.test.*
import kotlinx.serialization.json.Json
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
}
