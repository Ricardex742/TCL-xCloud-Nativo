package com.xcloud.tclnative

import com.xcloud.tclnative.catalog.CatalogWorkGate
import com.xcloud.tclnative.home.HomePublicationJobs
import com.xcloud.tclnative.webrtc.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CompatibilityTest {
    @Test fun baselineIsPreferredForLightweightDecodingWithoutForgingProfiles() {
        val offered=listOf("640c1f","42e01f","4d001f")
        assertEquals(listOf("42e01f","4d001f","640c1f"),offered.sortedBy { CodecPreference.rank(it,true) })
        assertEquals(listOf("640c1f","4d001f","42e01f"),offered.sortedBy { CodecPreference.rank(it,false) })
    }
    @Test fun compatibilityCapsTheRequestAtSixMbpsWithoutTouchingAudioIceOrH264Profile() {
        val sdp="v=0\r\nm=audio 9 RTP/AVP 111\r\na=rtpmap:111 opus/48000\r\nm=video 9 RTP/AVP 96\r\nc=IN IP4 0.0.0.0\r\na=rtpmap:96 H264/90000\r\na=fmtp:96 profile-level-id=42e01f;packetization-mode=1\r\na=ice-ufrag:test\r\n"
        val result=StreamProfile.receiveSdp(sdp,StreamResolution.HD_720,true)
        assertTrue(result.contains("b=AS:6000\r\nb=TIAS:6000000"))
        assertTrue(result.contains("x-google-min-bitrate=1000;x-google-start-bitrate=2500;x-google-max-bitrate=6000"))
        assertTrue(result.contains("profile-level-id=42e01f;packetization-mode=1"))
        assertEquals(sdp.substringBefore("m=video"),result.substringBefore("m=video"))
        assertTrue(result.contains("a=ice-ufrag:test"))
        assertEquals(result,StreamProfile.receiveSdp(result,StreamResolution.HD_720,true))
    }
    @Test fun lightModeRecoversSoonerAndDoesNotResetTheDecoderWhenNetworkPacketsStop() {
        val decoder=VideoRecoveryPolicy(true)
        decoder.observe(0,1,false,true)
        assertEquals(VideoRecoveryAction.REQUEST_KEYFRAME,decoder.observe(1500,1,false,true))
        assertEquals(VideoRecoveryAction.RESET_DECODER,decoder.observe(4000,1,false,true))
        val network=VideoRecoveryPolicy(true)
        network.observe(0,1,false,false)
        network.observe(1500,1,false,false)
        assertEquals(VideoRecoveryAction.REQUEST_KEYFRAME,network.observe(4000,1,false,false))
        assertEquals(VideoRecoveryAction.NONE,network.observe(5000,2,true,true))
    }
    @Test fun catalogRequestsWaitWhilePlayingAndCancellationDoesNotLeaveBackgroundWorkRunning() = runBlocking {
        val gate=CatalogWorkGate(); gate.pause()
        var requests=0
        val request=launch(start=CoroutineStart.UNDISPATCHED) { gate.await(); requests++ }
        assertEquals(0,requests)
        gate.resume(); request.join(); assertEquals(1,requests)
        gate.pause()
        val cancelled=launch(start=CoroutineStart.UNDISPATCHED) { gate.await(); requests++ }
        cancelled.cancelAndJoin(); gate.resume(); yield(); assertEquals(1,requests)
    }
    @Test fun metadataUpdatesNeverCancelAnExplicitHomeScreenPublication() = runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        val jobs=HomePublicationJobs(scope,0)
        val started=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>(); val done=CompletableDeferred<Unit>()
        try {
            jobs.schedule(true) { started.complete(Unit); release.await(); done.complete(Unit) }
            withTimeout(2000) { started.await() }
            repeat(10) { jobs.schedule(false) {} }
            release.complete(Unit)
            withTimeout(2000) { done.await() }
            assertTrue(done.isCompleted)
        } finally { jobs.cancel(); scope.cancel() }
    }
    @Test fun localDiagnosticsKeepABoundedHistoryAndTreatStaleReceptionAsUnknown() {
        val history=StreamDiagnosticHistory()
        repeat(40) { history.add(it*1000L,StreamMetrics(receivedVideoBytes=0,decodedFrames=0),InputMetrics()) }
        assertEquals(20,history.report().lines().size)
        assertEquals(false,history.receivingVideo(39000))
        assertNull(history.receivingVideo(42000))
        history.add(43000,StreamMetrics(receivedVideoBytes=500,decodedFrames=30),InputMetrics())
        assertEquals(true,history.receivingVideo(44000))
        history.clear(); assertNull(history.receivingVideo(44000))
    }
}
