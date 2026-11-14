package com.xcloud.tclnative

import com.xcloud.tclnative.webrtc.TransmissionRecoveryState
import com.xcloud.tclnative.webrtc.awaitTransmissionFrame
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TransmissionRecoveryStateTest {
    @Test fun doublePressIsIgnoredAndOldCallbacksCannotCompleteOrInterruptTheNewAttempt() {
        val state=TransmissionRecoveryState();val old=state.epoch
        val token=state.begin()!!
        assertNull(state.begin());assertFalse(state.current(old));assertFalse(state.interrupted(old));assertFalse(state.connected(old))
        assertTrue(state.connected(token));assertEquals(TransmissionRecoveryState.Phase.ACTIVE,state.phase)
        assertFalse(state.connected(token))
    }
    @Test fun failedAttemptCanBeRetriedButTheClosedGameCannot() {
        val state=TransmissionRecoveryState();val one=state.begin()!!;state.failed(one)
        assertEquals(TransmissionRecoveryState.Phase.WAITING_RETRY,state.phase)
        val two=state.begin()!!;state.failed(one)
        assertEquals(TransmissionRecoveryState.Phase.RELOADING,state.phase)
        state.close();assertFalse(state.connected(two));assertFalse(state.current(two));assertNull(state.begin())
    }
    @Test fun aTransportFailureOffersOnlyOneInterruptionUntilTheUserRetries() {
        val state=TransmissionRecoveryState()
        assertTrue(state.interrupted(0));assertFalse(state.interrupted(0))
        val token=state.begin()!!;assertFalse(state.interrupted(token))
        assertTrue(state.connected(token));assertTrue(state.interrupted(token))
    }
    @Test fun failedRecoveryDiagnosticsNeverIncludePrivateExceptionMessages() {
        val state=TransmissionRecoveryState();val token=state.begin()!!
        state.failed(token,java.io.IOException("SDP_OFFER: token=private-value account=private-user"))
        val report=state.diagnostics()
        assertTrue(report.contains("SDP_OFFER"));assertFalse(report.contains("private-value"));assertFalse(report.contains("private-user"))
    }
    @Test fun missingReplacementFrameBecomesARetryableFailureRatherThanCancellingTheSession() = runBlocking {
        val state=TransmissionRecoveryState();val token=state.begin()!!
        val error=runCatching { awaitTransmissionFrame(CompletableDeferred(),CompletableDeferred(),10) }.exceptionOrNull()!!
        assertTrue(error is java.io.IOException);assertFalse(error is CancellationException)
        state.failed(token,error);assertEquals(TransmissionRecoveryState.Phase.WAITING_RETRY,state.phase)
        assertTrue(state.diagnostics().contains("RECONNECT_FRAME_TIMEOUT"));assertNotNull(state.begin())
    }
    @Test fun aKnownTransportFailureWinsOverASimultaneousFirstFrame() = runBlocking {
        val error=java.io.IOException("transport failed")
        val result=runCatching { awaitTransmissionFrame(CompletableDeferred(Unit),CompletableDeferred(error),1000) }
        assertEquals(error.javaClass,result.exceptionOrNull()!!.javaClass)
        assertEquals(error.message,result.exceptionOrNull()!!.message)
    }
    @Test fun cancellationByTheGameOwnerStillCancelsTheFrameWait() = runBlocking {
        var afterWait=false
        val job=launch { awaitTransmissionFrame(CompletableDeferred(),CompletableDeferred(),30_000);afterWait=true }
        yield();job.cancelAndJoin()
        assertTrue(job.isCancelled);assertFalse(afterWait)
    }
}
