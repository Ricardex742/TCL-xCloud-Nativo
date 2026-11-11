package com.xcloud.tclnative

import com.xcloud.tclnative.webrtc.*
import com.xcloud.tclnative.webrtc.VideoRecoveryAction.*
import org.junit.Assert.*
import org.junit.Test

class VideoRecoveryPolicyTest {
    @Test fun staticLoadingScreensWithNewFramesDoNotTriggerRecovery() {
        val policy = VideoRecoveryPolicy()
        for (second in 0L..120L) assertEquals(NONE, policy.observe(second * 1000, second, true))
    }
    @Test fun audioOnlyAfterEncoderRestartRequestsAnIdrThenResetsDecoder() {
        val policy = VideoRecoveryPolicy()
        policy.observe(0, 1, true)
        assertEquals(NONE, policy.observe(3000, 1, false))
        assertEquals(REQUEST_KEYFRAME, policy.observe(4000, 1, false))
        assertEquals(NONE, policy.observe(5000, 1, false))
        assertEquals(RESET_DECODER, policy.observe(8000, 1, false))
        assertEquals(NONE, policy.observe(9000, 2, true))
        assertEquals(NONE, policy.observe(10_000, 3, true))
    }
    @Test fun decodedFramesWithoutPresentationRebuildTheSurfaceInstead() {
        val policy = VideoRecoveryPolicy()
        policy.observe(0, 1, true)
        assertEquals(REQUEST_KEYFRAME, policy.observe(4000, 1, true))
        assertEquals(REBUILD_SURFACE, policy.observe(8000, 1, true))
        assertEquals(NONE, policy.observe(9000, 2, true))
    }
    @Test fun prolongedFreezeOffersAnExplicitRetryAndStopsAutomaticResets() {
        val policy = VideoRecoveryPolicy()
        policy.observe(0, 1, true)
        policy.observe(4000, 1, false)
        policy.observe(8000, 1, false)
        assertEquals(RESET_DECODER, policy.observe(16_000, 1, false))
        assertEquals(OFFER_RETRY, policy.observe(30_000, 1, false))
        for (second in 31L..90L) assertEquals(NONE, policy.observe(second * 1000, 1, false))
        policy.retry(91_000)
        assertEquals(REQUEST_KEYFRAME, policy.observe(91_000, 1, false))
        assertEquals(NONE, policy.observe(92_000, 2, true))
    }
    @Test fun aNewFrameResetsTheEntireFailureWindowWithoutNeedingANewSession() {
        val policy = VideoRecoveryPolicy()
        policy.observe(0, 1, true)
        policy.observe(4000, 1, false)
        assertEquals(NONE, policy.observe(7000, 2, true))
        assertEquals(NONE, policy.observe(10_000, 2, false))
        assertEquals(REQUEST_KEYFRAME, policy.observe(11_000, 2, false))
        assertEquals(RESET_DECODER, policy.observe(15_000, 2, false))
    }
    @Test fun keyframeRequestsAreThrottledWhileWaitingForRecovery() {
        val policy = VideoRecoveryPolicy()
        policy.observe(0, 1, true)
        policy.observe(4000, 1, false)
        policy.observe(8000, 1, false)
        for (ms in 8001L..12_999L) assertEquals(NONE, policy.observe(ms, 1, false))
        assertEquals(REQUEST_KEYFRAME, policy.observe(13_000, 1, false))
    }
}
