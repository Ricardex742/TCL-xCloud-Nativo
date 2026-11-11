package com.xcloud.tclnative

import com.xcloud.tclnative.renderer.ReconstructionController
import com.xcloud.tclnative.settings.ReconstructionQuality
import com.xcloud.tclnative.webrtc.StreamMetrics
import org.junit.Assert.*
import org.junit.Test

class ReconstructionControllerTest {
    private val good=StreamMetrics(width=1920,height=1080,intervalSeconds=1.0,receivedFrames=60,
        decodedFrames=60,receivedVideoBytes=200000,packetLossPercent=0.0)
    @Test fun automaticPromotesOnlyAfterSeparateSustainedHealthyWindows() {
        val c=ReconstructionController();c.reset(ReconstructionQuality.AUTO)
        repeat(12) { assertFalse(c.observe(it*1000L,it*60L,good,true)) }
        assertEquals(1,c.level)
        assertTrue(c.observe(12000,720,good,true));assertEquals(2,c.level)
        for(i in 13..31) assertFalse(c.observe(i*1000L,i*60L,good,true))
        assertTrue(c.observe(32000,1920,good,true));assertEquals(3,c.level)
    }
    @Test fun lossStallsBadStatsAndComparisonCannotEarnPromotion() {
        for(sample in listOf(good.copy(receivedFrames=0),good.copy(packetLossPercent=2.0),
            good.copy(packetLossPercent=Double.NaN),good.copy(intervalSeconds=Double.NaN),
            good.copy(receivedVideoBytes=0))) {
            val c=ReconstructionController();c.reset(ReconstructionQuality.AUTO)
            repeat(90) { c.observe(it*1000L,it*60L,sample,true) };assertEquals(1,c.level)
        }
        val c=ReconstructionController();c.reset(ReconstructionQuality.AUTO)
        repeat(90) { c.observe(it*1000L,it*60L,good,false) };assertEquals(1,c.level)
        repeat(90) { c.observe((it+90)*1000L,(it+90)*30L,good,true) };assertEquals(1,c.level)
    }
    @Test fun aThirtyFpsSourceCanUseQualityWhileLargeSourcesAndLowRamStayBounded() {
        val thirty=ReconstructionController();thirty.reset(ReconstructionQuality.AUTO)
        repeat(50) { thirty.observe(it*1000L,it*30L,good.copy(receivedFrames=30,decodedFrames=30),true) }
        assertEquals(3,thirty.level)
        val large=ReconstructionController();large.reset(ReconstructionQuality.AUTO)
        repeat(60) { large.observe(it*1000L,it*60L,good.copy(width=2560,height=1440),true) }
        assertEquals(2,large.level)
        val low=ReconstructionController();low.reset(ReconstructionQuality.AUTO,true)
        repeat(60) { low.observe(it*1000L,it*60L,good,true) };assertEquals(2,low.level)
        low.reset(ReconstructionQuality.QUALITY,true);assertEquals(3,low.level)
    }
    @Test fun slowDeliveryPermanentlyLowersTheAutomaticCeilingUntilTheNextSession() {
        val c=ReconstructionController();c.reset(ReconstructionQuality.AUTO)
        repeat(40) { c.observe(it*1000L,it*60L,good,true) };assertEquals(3,c.level)
        assertTrue(c.reduce());assertEquals(2,c.level)
        repeat(90) { c.observe((it+40)*1000L,(it+40)*60L,good,true) };assertEquals(2,c.level)
        assertTrue(c.reduce());assertEquals(1,c.level);assertFalse(c.reduce())
        c.reset(ReconstructionQuality.AUTO)
        repeat(40) { c.observe(it*1000L,it*60L,good,true) };assertEquals(3,c.level)
    }
}
