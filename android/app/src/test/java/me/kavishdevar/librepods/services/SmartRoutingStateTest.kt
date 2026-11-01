package me.kavishdevar.librepods.services

import org.junit.Assert.*
import org.junit.Test

class SmartRoutingStateTest {
    @Test fun confirmedPhoneSourceDoesNotStartAnotherClaimWhenOwnershipIsUnknown() {
        var now = 0L
        val state = SmartRoutingState { now }
        state.remote()
        state.confirmLocalSource()
        val confirmed = state.generation
        // Reproduce the playback callback after the phone's MEDIA source notification.
        if (state.needsTakeover(ownsConnection = false, audioSourceIsOtherDevice = false)) {
            state.requestLocal(resumeMedia = true)
        }
        now += SmartRoutingState.CLAIM_TIMEOUT_MS
        assertEquals(confirmed, state.generation)
        assertFalse(state.isPending(confirmed))
        assertEquals(SmartRoutingState.Owner.LOCAL, state.owner)
    }

    @Test fun realRemoteEvidenceStillRequiresTakeoverDespiteEarlierLocalConfirmation() {
        val state = SmartRoutingState()
        assertTrue(state.needsTakeover(false, false)) // cold, unknown route
        state.confirmLocalSource()
        assertTrue(state.needsTakeover(true, true)) // current source is another host
        state.remote()
        assertTrue(state.needsTakeover(true, false)) // stale ownership must not undo handoff
    }

    @Test fun actualPlaybackCompletesPendingClaimWithoutAnOwnershipAck() {
        var now = 0L
        val state = SmartRoutingState { now }
        val claim = state.requestLocal(resumeMedia = true)
        assertTrue(state.confirmLocalPlayback(claim))
        assertTrue(state.consumeResume(claim, true))
        assertFalse(state.consumeResume(claim, true))
        now += SmartRoutingState.CLAIM_TIMEOUT_MS
        assertFalse(state.isPending(claim)) // the timeout must not yield and pause
        assertFalse(state.needsTakeover(false, false))
    }

    @Test fun playbackCannotReviveARemoteDisconnectedOrSupersededClaim() {
        val state = SmartRoutingState()
        assertFalse(state.confirmLocalPlayback(state.generation)) // no authorized claim
        val claim = state.requestLocal()
        state.remote()
        assertFalse(state.confirmLocalPlayback(claim))
        assertFalse(state.confirmLocalPlayback(state.generation))
        state.requestLocal()
        assertFalse(state.confirmLocalPlayback(claim))
        state.reset()
        assertFalse(state.confirmLocalPlayback(state.generation))
    }

    @Test fun remoteHandoffInvalidatesOldLocalWorkWithoutATimeoutReclaim() {
        var now = 0L
        val state = SmartRoutingState { now }
        val oldClaim = state.requestLocal(resumeMedia = true)
        state.confirmLocal()
        val handoff = state.remote()!!
        now = 60_000
        assertFalse(state.allowsLocal(oldClaim))
        assertTrue(state.allowsRemote(handoff))
        assertFalse(state.confirmLocal()) // delayed ownership ACK cannot reclaim by itself
        assertFalse(state.consumeResume(oldClaim, true))
    }

    @Test fun manualTakeoverSurvivesUnchangedMacTelemetryButNotANewHijack() {
        val state = SmartRoutingState { 0L }
        state.remote()
        val manual = state.requestLocal(resumeMedia = true)
        assertNull(state.remote(explicitHandoff = false))
        assertTrue(state.confirmLocal())
        assertTrue(state.allowsLocal(manual))
        state.remote(explicitHandoff = true)
        assertFalse(state.allowsLocal(manual))
        assertFalse(state.consumeResume(manual, true))
    }

    @Test fun playbackResumesOnceOnlyWhenBothOwnershipAndActiveProfileAreReady() {
        val state = SmartRoutingState { 0L }
        val claim = state.requestLocal(resumeMedia = true)
        assertFalse(state.consumeResume(claim, true)) // A2DP connected before ownership
        state.confirmLocal()
        assertFalse(state.consumeResume(claim, false)) // ownership before the active profile
        assertTrue(state.consumeResume(claim, true))
        assertFalse(state.consumeResume(claim, true)) // repeated source/profile/ACK notifications
    }

    @Test fun nativeSystemPickerSourceInvalidatesPendingRemoteRelease() {
        val state = SmartRoutingState()
        val remote = state.remote()!!
        state.confirmLocalSource()
        assertFalse(state.allowsRemote(remote))
        assertTrue(state.allowsLocal(state.generation))
        assertFalse(state.consumeResume(state.generation, true)) // no unsolicited PLAY
    }

    @Test fun coldConnectAndLateAckCanResumeButExpiredIntentCannot() {
        var now = 0L
        val state = SmartRoutingState { now }
        val claim = state.requestLocal(resumeMedia = true)
        now = 2_000
        state.confirmLocal()
        assertTrue(state.consumeResume(claim, true))
        val next = state.requestLocal(resumeMedia = true)
        now += SmartRoutingState.CLAIM_TIMEOUT_MS
        state.confirmLocal()
        assertFalse(state.consumeResume(next, true))
    }

    @Test fun failedClaimAndDisconnectInvalidateResumeAndProfileCallbacks() {
        var now = 0L
        val state = SmartRoutingState { now }
        val claim = state.requestLocal(resumeMedia = true)
        now = SmartRoutingState.CLAIM_TIMEOUT_MS
        assertNotNull(state.remote(explicitHandoff = false))
        assertFalse(state.allowsLocal(claim))
        val reconnect = state.requestLocal(resumeMedia = true)
        state.reset()
        assertFalse(state.allowsLocal(reconnect, allowUnknown = true))
        assertFalse(state.consumeResume(reconnect, true))
        assertFalse(state.allowsLocal(state.generation))
        assertTrue(state.allowsLocal(state.generation, allowUnknown = true))
    }
}
