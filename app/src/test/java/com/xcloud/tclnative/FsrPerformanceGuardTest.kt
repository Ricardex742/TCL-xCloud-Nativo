package com.xcloud.tclnative

import com.xcloud.tclnative.renderer.FsrPerformanceGuard
import com.xcloud.tclnative.webrtc.StreamMetrics
import org.junit.Assert.*
import org.junit.Test

class FsrPerformanceGuardTest {
    private val good=StreamMetrics(receivedVideoBytes=500000,packetLossPercent=0.0,intervalSeconds=1.0)
    @Test fun sustainedSixOrThirteenDrawsPerSecondTripsOnceAfterWarmupAndFourLowSeconds() {
        for(rate in listOf(6,13)) {
            val guard=FsrPerformanceGuard()
            for(second in 0..5) assertNull(guard.observe(second*1000L,(second+1L)*rate,good,true))
            val slow=guard.observe(6000,7L*rate,good,true)!!
            assertEquals(rate.toDouble(),slow.drawsPerSecond,0.01);assertEquals(4000L,slow.durationMs)
            assertNull(guard.observe(7000,8L*rate,good,true))
        }
    }
    @Test fun stableThirtyAndSixtyFramesAndShortSlowBurstsDoNotTrip() {
        for(rate in listOf(30,60)) {
            val guard=FsrPerformanceGuard()
            repeat(20) { assertNull(guard.observe(it*1000L,(it+1L)*rate,good,true)) }
        }
        val guard=FsrPerformanceGuard();var frames=1L
        repeat(20) { second ->
            frames+=if(second%4==3) 60 else 13
            assertNull(guard.observe(second*1000L,frames,good,true))
        }
    }
    @Test fun dataDroughtPacketLossAndBackgroundResetTheWindowInsteadOfBlamingFsr() {
        for(bad in listOf(good.copy(receivedVideoBytes=0),good.copy(packetLossPercent=3.57),
            good.copy(packetLossPercent=null),good.copy(packetLossPercent=Double.NaN),good.copy(intervalSeconds=null))) {
            val guard=FsrPerformanceGuard()
            for(second in 0..4) assertNull(guard.observe(second*1000L,(second+1L)*6,good,true))
            assertNull(guard.observe(5000,36,bad,true))
            for(second in 6..9) assertNull(guard.observe(second*1000L,(second+1L)*6,good,true))
        }
        val guard=FsrPerformanceGuard()
        for(second in 0..4) assertNull(guard.observe(second*1000L,(second+1L)*6,good,true))
        assertNull(guard.observe(5000,36,good,false))
        for(second in 6..9) assertNull(guard.observe(second*1000L,(second+1L)*6,good,true))
    }
    @Test fun samplingGapsCounterResetsAndNoActualFsrDrawsDoNotProduceAFabricatedRate() {
        val guard=FsrPerformanceGuard()
        assertNull(guard.observe(0,0,good,true));assertNull(guard.lastRate)
        assertNull(guard.observe(1000,12,good,true))
        assertNull(guard.observe(5000,24,good,true));assertNull(guard.lastRate)
        assertNull(guard.observe(6000,12,good,true))
        assertNull(guard.observe(7000,1,good,true));assertNull(guard.lastRate)
    }
}
