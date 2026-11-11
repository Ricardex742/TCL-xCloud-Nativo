package com.xcloud.tclnative.renderer

import com.xcloud.tclnative.settings.ReconstructionQuality
import com.xcloud.tclnative.webrtc.StreamMetrics

/** Runtime cadence is evidence of delivery, never a GPU duration or a hardware performance rating. */
class ReconstructionController {
    var level=1;private set
    private var ceiling=3
    private var quality=ReconstructionQuality.AUTO
    private var previousTime: Long?=null
    private var previousDraws=0L
    private var healthyMs=0L
    var decision="warmup";private set
    fun reset(requested: ReconstructionQuality,lowRam: Boolean=false) {
        quality=requested;level=requested.level;ceiling=if(lowRam && requested==ReconstructionQuality.AUTO) 2 else 3
        previousTime=null;previousDraws=0;healthyMs=0;decision="start level $level"
    }
    fun observe(now: Long,draws: Long,m: StreamMetrics,eligible: Boolean): Boolean {
        val oldTime=previousTime;val oldDraws=previousDraws;previousTime=now;previousDraws=draws
        if(!eligible || quality!=ReconstructionQuality.AUTO || level>=ceiling || oldTime==null) { healthyMs=0;return false }
        val seconds=m.intervalSeconds ?: return unhealthy()
        val source=(m.receivedFrames ?: m.decodedFrames)?.let { it/seconds } ?: m.fps
        val elapsed=now-oldTime;val count=draws-oldDraws
        val loss=m.packetLossPercent
        if(elapsed !in 500..1500 || count<0 || seconds !in .5..1.5 || source==null || !source.isFinite() || source !in 24.0..65.0 ||
            loss==null || !loss.isFinite() || loss !in 0.0..0.5 || (m.receivedVideoBytes ?: 0)<=0 || count*1000.0/elapsed<source.coerceAtMost(60.0)*.97) return unhealthy()
        // Larger sources may use balanced processing automatically; quality remains a manual option.
        if(level>=2 && (m.width==null || m.height==null || m.width.toLong()*m.height>1920L*1080)) return unhealthy()
        healthyMs+=elapsed
        if(healthyMs<(if(level==1) 12_000 else 20_000)) return false
        level++;healthyMs=0;decision="stable cadence: level $level";return true
    }
    private fun unhealthy(): Boolean { healthyMs=0;return false }
    fun reduce(): Boolean {
        healthyMs=0
        if(level<=1) return false
        level--;ceiling=level;decision="cadence protection: level $level; promotion locked this session";return true
    }
}
