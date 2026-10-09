package app.pinballpilot.domain

import kotlin.test.*
import kotlinx.serialization.json.*

class ProgressScanTest {
    private val defs=listOf(StateVariable("locks","Locked balls","counter","game"),StateVariable("clock","Timer","timer","mode",modeId="battle"))
    private val context=ScanContext("game-1","machine",2,50,100)
    private val initial=StateEngine.initial(defs,0)
    private val lock=ScanReading("state","locks",JsonPrimitive(2),"BALLS LOCKED 2",0.8)
    private val score=ScanReading("score",value=JsonPrimitive(1200),evidence="PLAYER 1 1200",confidence=0.8)
    private fun apply(readings:List<ScanReading>, scan:ScanContext=context, now:Long=200)=ProgressScan.confirm(scan,"game-1","machine",2,50,now,readings,defs,initial,900)

    @Test fun onlySelectedReadingsAffectProgress() {
        val update=apply(listOf(lock))
        assertEquals(JsonPrimitive(2),update.state["locks"]?.value)
        assertEquals("player",update.state["locks"]?.origin)
        assertEquals(100,update.state["locks"]?.observedAt)
        assertEquals(900,update.score)
        assertNull(initial["locks"]?.value)
    }
    @Test fun scoreAndBallDoNotResetOrInventProgress() {
        val ball=ScanReading("ball",value=JsonPrimitive(2),evidence="BALL 2",confidence=0.7)
        val update=apply(listOf(score,ball))
        assertEquals(1200,update.score)
        assertEquals(initial,update.state)
    }
    @Test fun rejectsChangesOfGameGuideOrProgress() {
        for(scan in listOf(context.copy(gameId="another"),context.copy(variantId="another"),context.copy(packVersion=3),context.copy(gameUpdatedAt=51))) {
            assertFailsWith<IllegalArgumentException> {apply(listOf(lock),scan)}
        }
    }
    @Test fun rejectsExpiredFutureAndEmptyConfirmations() {
        assertFailsWith<IllegalArgumentException> {apply(listOf(lock),now=120101)}
        assertFailsWith<IllegalArgumentException> {apply(listOf(lock),now=99)}
        assertFailsWith<IllegalArgumentException> {apply(emptyList())}
    }
    @Test fun refusesUnknownFieldsTimersDuplicatesAndInvalidValues() {
        for(readings in listOf(listOf(lock.copy(variableId="missing")),listOf(lock.copy(variableId="clock")),listOf(lock,lock),listOf(lock.copy(value=JsonPrimitive("2"))),listOf(score.copy(value=JsonPrimitive(-1))),listOf(score.copy(target="ball",value=JsonPrimitive(0))))) {
            assertFails {apply(readings)}
        }
    }
}
