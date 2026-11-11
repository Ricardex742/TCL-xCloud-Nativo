package com.xcloud.tclnative

import com.xcloud.tclnative.webrtc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection.IceGatheringState
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class LocalIceGatheringTest {
    private val host=IceCandidate("0",0,"candidate:host1 1 UDP 1 192.168.1.2 5000 typ host")
    private val ipv6=IceCandidate("1",1,"candidate:v6 1 UDP 1 2001:db8::1 5001 typ host")
    private val reflexive=IceCandidate("0",0,"candidate:srflx1 1 UDP 1 203.0.113.1 6000 typ srflx raddr 192.168.1.2 rport 5000")

    @Test fun emptyCompleteDoesNotAbortAndALateInterfaceCanStillSupplyTheCandidate() = runTest {
        val gathering=LocalIceGathering()
        gathering.state(IceGatheringState.COMPLETE)
        val result=async { gathering.awaitCandidates() }
        runCurrent();advanceTimeBy(7_999);runCurrent()
        assertFalse(result.isCompleted)
        gathering.candidate(ipv6)
        runCurrent()
        assertEquals(listOf(ipv6),result.await())
        assertEquals(7_999L,currentTime)
    }
    @Test fun anEmptyResultStillHasAFixedDeadlineEvenIfCompleteArrivedEarly() = runTest {
        val gathering=LocalIceGathering()
        gathering.state(IceGatheringState.COMPLETE)
        gathering.error(701)
        val result=async { gathering.awaitCandidates() }
        runCurrent();advanceTimeBy(8_000);runCurrent()
        assertTrue(result.await().isEmpty())
        assertTrue(gathering.diagnostics(1).contains("coleta COMPLETE; candidatos 0"))
        assertTrue(gathering.diagnostics(1).contains("códigos STUN: 701"))
    }
    @Test fun aStalledStunServerDoesNotDiscardHostOrIpv6CandidatesAtTheDeadline() = runTest {
        val gathering=LocalIceGathering()
        gathering.state(IceGatheringState.GATHERING)
        gathering.candidate(host);gathering.candidate(ipv6);gathering.error(701)
        val result=async { gathering.awaitCandidates() }
        runCurrent();advanceTimeBy(7_999);runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(1);runCurrent()
        assertEquals(listOf(host,ipv6),result.await())
        assertEquals(8_000L,currentTime)
    }
    @Test fun normalCompleteReturnsImmediatelyWithAllCandidatesWithoutAnExtraDelay() = runTest {
        val gathering=LocalIceGathering()
        gathering.candidate(host);gathering.candidate(reflexive)
        gathering.state(IceGatheringState.COMPLETE)
        assertEquals(listOf(host,reflexive),gathering.awaitCandidates())
        assertEquals(0L,currentTime)
    }
    @Test fun interfaceRemovalDoesNotPublishAStaleCandidateAndCanWaitForItsReplacement() = runTest {
        val gathering=LocalIceGathering()
        gathering.candidate(host)
        gathering.removed(arrayOf(IceCandidate(host.sdpMid,host.sdpMLineIndex,host.sdp)))
        gathering.state(IceGatheringState.COMPLETE)
        val result=async { gathering.awaitCandidates() }
        runCurrent()
        assertFalse(result.isCompleted)
        gathering.state(IceGatheringState.GATHERING)
        gathering.candidate(ipv6);gathering.state(IceGatheringState.COMPLETE)
        assertEquals(listOf(ipv6),result.await())
    }
    @Test fun duplicatesAreRemovedButDifferentMediaSectionsAndOriginalAddressesArePreserved() = runTest {
        val gathering=LocalIceGathering()
        gathering.candidate(host);gathering.candidate(host);gathering.candidate(ipv6)
        val otherMid=IceCandidate("2",2,host.sdp)
        gathering.candidate(otherMid)
        gathering.candidate(IceCandidate("0",0,""))
        gathering.candidate(IceCandidate("0",-1,host.sdp))
        gathering.state(IceGatheringState.COMPLETE)
        assertEquals(listOf(host,ipv6,otherMid),gathering.awaitCandidates())
    }
    @Test fun closingAnAttemptCancelsItsWaitAndIgnoresLateCallbacks() = runTest {
        val gathering=LocalIceGathering()
        val result=async { gathering.awaitCandidates() }
        runCurrent()
        gathering.close()
        gathering.candidate(host);gathering.state(IceGatheringState.COMPLETE);gathering.error(701)
        runCurrent()
        assertTrue(result.isCancelled)
        assertTrue(gathering.diagnostics(1).contains("candidatos 0"))
        assertFalse(gathering.diagnostics(1).contains("701"))
    }
    @Test fun cancellingTheWaitDoesNotBecomeASuccessfulEmptyGathering() = runTest {
        val gathering=LocalIceGathering()
        val result=async { gathering.awaitCandidates() }
        runCurrent()
        result.cancelAndJoin()
        assertTrue(result.isCancelled)
        assertEquals(0L,currentTime)
    }
    @Test fun diagnosticsContainOnlyCountsStatesAndBoundedNumericErrorCodes() = runTest {
        val gathering=LocalIceGathering()
        gathering.candidate(host);gathering.candidate(ipv6);gathering.candidate(reflexive)
        repeat(20) { gathering.error(701+it) }
        gathering.error(701)
        gathering.state(IceGatheringState.COMPLETE)
        val report=gathering.diagnostics(2)
        assertTrue(report.contains("candidatos 3 (host 2, srflx 1, relay 0)"))
        assertFalse(report.contains("709"))
        val error=LocalIceUnavailableException(2,report).message!!
        for(secret in listOf("candidate:","192.168.1.2","2001:db8","203.0.113.1","raddr")) assertFalse(error.contains(secret))
    }
    @Test fun emptyLocalGatheringGetsExactlyOneFreshAttemptAfterDisposingTheFirst() = runTest {
        val events=mutableListOf<String>()
        val result=LocalIceOfferRetry.prepare(create={ attempt ->
            events.add("create$attempt")
            if(attempt==1) throw LocalIceUnavailableException(attempt,"ICE local: vazio")
            "exclusive-hevc-offer" to listOf(host)
        },discard={ events.add("dispose1") },onRetry={ events.add("progress2") })
        assertEquals(listOf("create1","dispose1","progress2","create2"),events)
        assertEquals("exclusive-hevc-offer",result.first)
        assertEquals(listOf(host),result.second)
    }
    @Test fun aSecondEmptyAttemptFailsWithoutAThirdAttempt() = runTest {
        var attempts=0;var disposals=0
        try {
            LocalIceOfferRetry.prepare(create={ attempt ->
                attempts++;throw LocalIceUnavailableException(attempt,"sem candidatos")
            },discard={ disposals++ },onRetry={})
            fail("Empty gathering was hidden")
        } catch(error: LocalIceUnavailableException) { assertTrue(error.message!!.contains("2/2")) }
        assertEquals(2,attempts);assertEquals(1,disposals)
    }
    @Test fun codecRemoteIceAndNetworkErrorsAreNotRetriedByThisLocalPolicy() = runTest {
        for(reason in listOf("VIDEO_CODEC: rejected","SDP: rejected","ICE_LOCAL: HTTP 400","ICE_REMOTE: rejected","TRANSPORT: failed")) {
            var attempts=0
            try {
                LocalIceOfferRetry.prepare(create={ attempts++;throw IOException(reason) },
                    discard={ fail("Unrelated failure disposed as empty ICE") },onRetry={ fail("Unrelated failure retried") })
                fail("Expected failure")
            } catch(error: IOException) { assertEquals(reason,error.message) }
            assertEquals(1,attempts)
        }
    }
    @Test fun cancellationDuringCollectionDoesNotDisposeAndRelaunchAnotherAttempt() = runTest {
        var attempts=0
        val result=async { LocalIceOfferRetry.prepare(create={ attempts++;awaitCancellation() },
            discard={ fail("Cancelled attempt retried") },onRetry={ fail("Cancelled attempt retried") }) }
        runCurrent();result.cancelAndJoin()
        assertTrue(result.isCancelled);assertEquals(1,attempts)
    }
    @Test fun cancellationWhileWaitingForDisposalNeverStartsAReplacementPeer() = runTest {
        var attempts=0;var disposals=0
        val result=async { LocalIceOfferRetry.prepare(create={ attempt ->
            attempts++;throw LocalIceUnavailableException(attempt,"sem candidatos")
        },discard={ disposals++;awaitCancellation() },onRetry={ fail("Cancelled disposal launched another peer") }) }
        runCurrent()
        assertEquals(1,disposals)
        result.cancelAndJoin()
        assertTrue(result.isCancelled);assertEquals(1,attempts)
    }
}
