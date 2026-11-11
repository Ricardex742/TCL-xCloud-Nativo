package com.xcloud.tclnative.renderer

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.Locale

/** A bounded schedule, never a clock conversion or a promise about physical presentation. */
class FrameCadencePlanner {
    private val intervals=LongArray(15)
    private val transit=LongArray(31)
    private val scratch=LongArray(31)
    private var intervalsCount=0;private var transitCount=0;private var intervalAt=0;private var transitAt=0
    private var reference=Long.MIN_VALUE;private var anchor=0L;private var arrivalAnchor=0L
    private var offset=0L
    fun schedule(timestampNs: Long,arrivalNs: Long): Long {
        val ref=if(timestampNs>0) timestampNs else arrivalNs
        if(reference==Long.MIN_VALUE || ref<=reference || ref-reference>1_000_000_000L) {
            reference=ref;anchor=ref;arrivalAnchor=arrivalNs;intervalsCount=0;transitCount=0;intervalAt=0;transitAt=0;offset=0
        } else {
            val delta=ref-reference;reference=ref
            if(delta in 2_000_000L..200_000_000L) {
                intervals[intervalAt]=delta;intervalAt=(intervalAt+1)%intervals.size;intervalsCount=minOf(intervalsCount+1,intervals.size)
            }
        }
        val base=arrivalAnchor+(ref-anchor)
        transit[transitAt]=arrivalNs-base;transitAt=(transitAt+1)%transit.size;transitCount=minOf(transitCount+1,transit.size)
        System.arraycopy(transit,0,scratch,0,transitCount);java.util.Arrays.sort(scratch,0,transitCount)
        val target=scratch[((transitCount-1)*.95).toInt()]+3_000_000L
        offset=if(target>offset) target else maxOf(target,offset-250_000L)
        val interval=if(intervalsCount>=5) {
            System.arraycopy(intervals,0,scratch,0,intervalsCount);java.util.Arrays.sort(scratch,0,intervalsCount)
            scratch[intervalsCount/2]
        } else MAX_DELAY_NS
        return (base+offset).coerceIn(arrivalNs,arrivalNs+minOf(MAX_DELAY_NS,interval))
    }
    companion object { const val MAX_DELAY_NS=16_666_667L }
}

interface FramePacingScheduler : AutoCloseable {
    fun nowNs(): Long
    fun post(task: Runnable,delayMs: Long)
    fun remove(task: Runnable)
}

private class HandlerFramePacingScheduler : FramePacingScheduler {
    private val thread=HandlerThread("video-presentation",Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler=Handler(thread.looper)
    override fun nowNs()=System.nanoTime()
    override fun post(task: Runnable,delayMs: Long) { handler.postDelayed(task,delayMs) }
    override fun remove(task: Runnable) { handler.removeCallbacks(task) }
    override fun close() { handler.removeCallbacksAndMessages(null);thread.quitSafely() }
}

/** One retained frame maximum; replacements, shutdown and exceptions always release ownership. */
class FramePacedVideoSink(private val target: VideoSink,
                         private val scheduler: FramePacingScheduler=HandlerFramePacingScheduler(),
                         private val failed: (Exception)->Unit={}) : VideoSink,AutoCloseable {
    private val lock=Any()
    private val planner=FrameCadencePlanner()
    private var pending: VideoFrame?=null
    private var pendingArrival=0L
    private var due: Long?=null
    @Volatile private var closed=false
    @Volatile private var delivered=0L
    @Volatile private var dropped=0L
    @Volatile private var waitNs=0L
    @Volatile private var maxWaitNs=0L
    private val task=Runnable { present() }
    override fun onFrame(frame: VideoFrame) {
        var old: VideoFrame?=null
        synchronized(lock) {
            if(closed) return
            val now=scheduler.nowNs();val deadline=planner.schedule(frame.timestampNs,now)
            frame.retain();old=pending;pending=frame;pendingArrival=now
            if(old!=null) dropped++
            if(due==null || deadline<due!!) {
                scheduler.remove(task);due=deadline
                scheduler.post(task,((deadline-now+999_999L)/1_000_000L).coerceAtLeast(0))
            }
        }
        old?.release()
    }
    private fun present() {
        val frame: VideoFrame
        val arrival: Long
        synchronized(lock) {
            due=null
            frame=pending ?: return;pending=null;arrival=pendingArrival
        }
        try {
            if(!closed) {
                val waited=(scheduler.nowNs()-arrival).coerceAtLeast(0)
                waitNs+=waited;maxWaitNs=maxOf(maxWaitNs,waited);delivered++
                target.onFrame(frame)
            }
        } catch(error: Exception) { close();failed(error) }
        finally { frame.release() }
    }
    fun diagnostics()=String.format(Locale.US,"Pacing: smooth | delivered=%d | replaced=%d | scheduled wait mean/max=%.2f/%.2f ms (not input-to-photon)",
        delivered,dropped,if(delivered==0L) 0.0 else waitNs/1e6/delivered,maxWaitNs/1e6)
    override fun close() {
        val old: VideoFrame?
        synchronized(lock) {
            if(closed) return
            closed=true;scheduler.remove(task);due=null;old=pending;pending=null
        }
        old?.release();scheduler.close()
    }
}
