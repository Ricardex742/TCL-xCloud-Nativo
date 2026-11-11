package com.xcloud.tclnative

import com.xcloud.tclnative.renderer.FsrPerformanceGuard
import com.xcloud.tclnative.renderer.TreatmentBudget
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.webrtc.StreamMetrics
import org.junit.Assert.*
import org.junit.Test

class TreatmentBudgetTest {
    @Test fun cutsAreProgressiveBoundedAndLeaveTheRequestedSettingsIntact() {
        val requested=ImageTreatmentSettings(ImageSharpening.RCAS_LUMA,100,100,100,100,50,100)
        var effective=TreatmentBudget(requested,FsrOutput.UHD_4K,true)
        val stages=mutableListOf<String>()
        repeat(16) {
            val height=if(effective.scaling) effective.maxOutputHeight else 1080
            effective.reduce(height)?.let { cut ->
                assertNotEquals(effective,cut.effective);stages+=cut.stage;effective=cut.effective
            }
        }
        assertEquals(listOf("temporal","output 1440p","output native","antialias","neural",
            "deband 25%","deband off","cleanup","chroma","scaling off","image treatment off"),stages)
        assertFalse(effective.image.active);assertFalse(effective.scaling)
        assertEquals(100,requested.neural);assertEquals(50,requested.temporal)
        assertNull(effective.reduce(1080))
    }
    @Test fun automaticTabletOutputCanBeReducedWithoutRepeatingTheSameCutForever() {
        val auto=TreatmentBudget(ImageTreatmentSettings(),FsrOutput.AUTO,true)
        val cut=auto.reduce(1440)!!
        assertEquals(1080,cut.effective.maxOutputHeight)
        assertEquals("output native",cut.stage)
        assertEquals("scaling off",cut.effective.reduce(1080)!!.stage)
    }
    @Test fun fortyFiveDrawsAgainstSixtyDecodedFramesTriggerButAThirtyFpsSourceDoesNot() {
        val sample=StreamMetrics(receivedVideoBytes=500000,packetLossPercent=0.0,intervalSeconds=1.0,decodedFrames=60)
        val slow=FsrPerformanceGuard()
        var triggered=false
        repeat(12) { if(slow.observe(it*1000L,(it+1L)*45,sample,true)!=null) triggered=true }
        assertTrue(triggered)
        val healthy=FsrPerformanceGuard()
        repeat(20) { assertNull(healthy.observe(it*1000L,(it+1L)*30,sample.copy(decodedFrames=30),true)) }
        val idle=FsrPerformanceGuard()
        repeat(20) { assertNull(idle.observe(it*1000L,(it+1L)*3,sample.copy(decodedFrames=3),true)) }
    }
    @Test fun gpuBackpressureMustNotHideBehindTheFallingDecoderFps() {
        val sample=StreamMetrics(receivedVideoBytes=500000,packetLossPercent=0.0,intervalSeconds=1.0,
            receivedFrames=60,decodedFrames=6,fps=6.0)
        val guard=FsrPerformanceGuard();var observed=false
        repeat(12) { if(guard.observe(it*1000L,(it+1L)*6,sample,true)!=null) observed=true }
        assertTrue(observed)
    }
}
