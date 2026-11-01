package me.kavishdevar.librepods.services

import me.kavishdevar.librepods.bluetooth.HijackAnswer
import me.kavishdevar.librepods.bluetooth.SmartRoutingHijackResponse
import org.junit.Assert.*
import org.junit.Test

class SmartRoutingArbitratorTest {
    private val phone = "01:02:03:04:05:06"
    private val tablet = "11:12:13:14:15:16"
    private fun response(id: Long, answer: HijackAnswer = HijackAnswer.ACCEPTED, ticks: Long? = null) = SmartRoutingHijackResponse(id, answer, ticks)

    @Test fun requiresMatchingPeerAndIdAndAllPeerAcceptances() {
        val arbitrator = SmartRoutingArbitrator({ 0L }, 42L)
        val request = arbitrator.start(listOf(phone, tablet), 9, false)!!
        assertNull(arbitrator.answer("21:22:23:24:25:26", response(request.id)))
        assertNull(arbitrator.answer(phone, response(request.id + 1)))
        assertNull(arbitrator.answer(phone, response(request.id)))
        assertNull(arbitrator.answer(phone, response(request.id)))
        val result = arbitrator.answer(tablet, response(request.id))!!
        assertEquals(SmartRoutingArbitrator.Outcome.ACCEPTED, result.outcome)
        assertEquals(9L, result.request.generation)
        assertNull(arbitrator.answer(tablet, response(request.id)))
    }

    @Test fun allNegativeAnswersPreserveTheRouteAndThrottleRetries() {
        for (answer in listOf(HijackAnswer.REJECTED, HijackAnswer.AMBIGUOUS, HijackAnswer.BACKOFF)) {
            var now = 0L
            val arbitrator = SmartRoutingArbitrator({ now }, 1)
            val request = arbitrator.start(listOf(phone), 1, false)!!
            assertEquals(SmartRoutingArbitrator.Outcome.DENIED, arbitrator.answer(phone, response(request.id, answer, Long.MAX_VALUE))!!.outcome)
            assertNull(arbitrator.start(listOf(phone), 1, false))
            now = 4_000
            assertNotNull(arbitrator.start(listOf(phone), 1, false))
        }
    }

    @Test fun timeoutAndLateAnswerCannotGrantOrReviveACancelledRequest() {
        var now = 0L
        val arbitrator = SmartRoutingArbitrator({ now }, 1)
        val request = arbitrator.start(listOf(phone), 1, false)!!
        now = 3_999
        assertNull(arbitrator.timeout(request.id))
        now = 4_000
        assertEquals(SmartRoutingArbitrator.Outcome.TIMED_OUT, arbitrator.answer(phone, response(request.id))!!.outcome)
        assertNull(arbitrator.answer(phone, response(request.id)))
        now = 8_000
        val next = arbitrator.start(listOf(phone), 2, false)!!
        arbitrator.reset()
        assertNull(arbitrator.answer(phone, response(next.id)))
        assertNull(arbitrator.timeout(next.id))
    }

    @Test fun callSupersedesMediaAndStaleTimersCannotCancelIt() {
        var now = 0L
        val arbitrator = SmartRoutingArbitrator({ now }, 0xffffffffL)
        val media = arbitrator.start(listOf(phone), 1, false)!!
        assertNull(arbitrator.start(listOf(phone), 1, false))
        val call = arbitrator.start(listOf(phone), 1, true)!!
        assertEquals(1L, call.id)
        assertNull(arbitrator.start(listOf(phone), 1, false))
        assertNull(arbitrator.answer(phone, response(media.id)))
        now = 4_000
        assertNull(arbitrator.timeout(media.id))
        assertEquals(call, arbitrator.pending)
        assertEquals(SmartRoutingArbitrator.Outcome.TIMED_OUT, arbitrator.timeout(call.id)!!.outcome)
    }

    @Test fun callPolicyProtectsBothPlaybackReportsAndNegotiatedRequests() {
        assertFalse(SmartRoutingPolicy.mayYieldForRemoteIntent(callActive = true))
        assertEquals(HijackAnswer.REJECTED, SmartRoutingPolicy.answerRequest(true, false))
        assertEquals(HijackAnswer.REJECTED, SmartRoutingPolicy.answerRequest(true, true))
        assertTrue(SmartRoutingPolicy.mayYieldForRemoteIntent(true, manualReverse = true))
        assertEquals(HijackAnswer.AMBIGUOUS, SmartRoutingPolicy.answerRequest(false, true))
        assertEquals(HijackAnswer.ACCEPTED, SmartRoutingPolicy.answerRequest(false, false))
    }

    @Test fun deniedMediaDoesNotSuppressANewIncomingCall() {
        val arbitrator = SmartRoutingArbitrator({ 0L }, 1)
        val media = arbitrator.start(listOf(phone), 1, false)!!
        arbitrator.answer(phone, response(media.id, HijackAnswer.REJECTED))
        assertNotNull(arbitrator.start(listOf(phone), 1, true))
    }
}
