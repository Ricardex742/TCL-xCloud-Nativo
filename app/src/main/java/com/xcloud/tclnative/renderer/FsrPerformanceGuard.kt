package com.xcloud.tclnative.renderer

import com.xcloud.tclnative.webrtc.StreamMetrics

/** Conservative protection from sustained slow FSR draws, not a GPU timer or a game-FPS meter. */
class FsrPerformanceGuard {
    data class Slow(val drawsPerSecond: Double,val durationMs: Long)
    private var started: Long?=null
    private var previous: Long?=null
    private var previousFrames=0L
    private var lowMs=0L
    private var lowFrames=0L
    private var lowSamples=0
    private var issued=false
    var lastRate: Double?=null
        private set
    fun reset() {
        started=null;previous=null;previousFrames=0;lowMs=0;lowFrames=0;lowSamples=0;issued=false;lastRate=null
    }
    fun observe(now: Long,draws: Long,metrics: StreamMetrics,foreground: Boolean): Slow? {
        val loss=metrics.packetLossPercent
        val interval=metrics.intervalSeconds
        if(!foreground || draws<=0 || (metrics.receivedVideoBytes ?: 0)<=0 ||
            loss==null || !loss.isFinite() || loss<0 || loss>1.0 ||
            interval==null || !interval.isFinite() || interval !in .5..2.5) { reset();return null }
        if(issued) return null
        val oldTime=previous
        val oldFrames=previousFrames
        previous=now;previousFrames=draws
        if(oldTime==null) { started=now;return null }
        val elapsed=now-oldTime
        val count=draws-oldFrames
        if(elapsed !in 500..2500 || count<0) { reset();return null }
        lastRate=count*1000.0/elapsed
        if(now-(started ?: now)<=WARMUP_MS) return null
        if(lastRate!!>=MIN_DRAWS_PER_SECOND) { lowMs=0;lowFrames=0;lowSamples=0;return null }
        lowMs+=elapsed;lowFrames+=count;lowSamples++
        if(lowMs<LOW_WINDOW_MS || lowSamples<3) return null
        issued=true
        return Slow(lowFrames*1000.0/lowMs,lowMs)
    }
    companion object {
        const val MIN_DRAWS_PER_SECOND=24
        const val WARMUP_MS=2000L
        const val LOW_WINDOW_MS=4000L
    }
}
