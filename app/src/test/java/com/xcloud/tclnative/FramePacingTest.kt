package com.xcloud.tclnative

import com.xcloud.tclnative.renderer.*
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

class FramePacingTest {
    private class Buffer : VideoFrame.Buffer {
        var refs=1
        override fun getWidth()=16
        override fun getHeight()=16
        override fun retain() { refs++ }
        override fun release() { refs--;check(refs>=0) }
        override fun toI420(): VideoFrame.I420Buffer = throw UnsupportedOperationException()
        override fun cropAndScale(x: Int,y: Int,w: Int,h: Int,sw: Int,sh: Int): VideoFrame.Buffer = throw UnsupportedOperationException()
    }
    private class Clock : FramePacingScheduler {
        var now=0L;var closed=false
        val tasks=mutableMapOf<Runnable,Long>()
        override fun nowNs()=now
        override fun post(task: Runnable,delayMs: Long) { check(!closed);tasks[task]=now+delayMs*1_000_000 }
        override fun remove(task: Runnable) { tasks.remove(task) }
        override fun close() { closed=true;tasks.clear() }
        fun advance(ms: Long) {
            now+=ms*1_000_000
            val due=tasks.filterValues { it<=now }.keys.toList()
            due.forEach { tasks.remove(it);it.run() }
        }
    }
    @Test fun irregularFramesAndTimestampResetsNeverScheduleMoreThanOneFrameAhead() {
        val planner=FrameCadencePlanner();var arrival=1_000_000_000L;var timestamp=100_000_000L
        repeat(500) { i ->
            arrival+=if(i%7==0) 25_000_000L else 16_000_000L
            timestamp=if(i==250) 1L else timestamp+16_666_667L
            val due=planner.schedule(timestamp,arrival)
            assertTrue(due>=arrival && due<=arrival+FrameCadencePlanner.MAX_DELAY_NS)
        }
    }
    @Test fun onlyTheLatestPendingFrameIsRetainedAndShutdownReleasesIt() {
        val clock=Clock();val seen=mutableListOf<VideoFrame>()
        val sink=FramePacedVideoSink(VideoSink { seen+=it },clock)
        val first=Buffer();val second=Buffer();val third=Buffer()
        val f1=VideoFrame(first,0,1);sink.onFrame(f1);f1.release()
        val f2=VideoFrame(second,0,16_666_668);sink.onFrame(f2);f2.release()
        assertEquals(0,first.refs);assertEquals(1,second.refs);assertEquals(1,clock.tasks.size)
        clock.advance(4)
        assertEquals(listOf(f2),seen);assertEquals(0,second.refs)
        val f3=VideoFrame(third,0,33_333_335);sink.onFrame(f3);f3.release()
        assertEquals(1,third.refs);sink.close();sink.close()
        assertEquals(0,third.refs);assertTrue(clock.tasks.isEmpty());assertTrue(clock.closed)
        val after=Buffer();val f4=VideoFrame(after,0,40_000_000);sink.onFrame(f4);f4.release();assertEquals(0,after.refs)
    }
    @Test fun deliveryFailureReleasesOwnershipAndStopsTheScheduler() {
        val clock=Clock();var failures=0
        val sink=FramePacedVideoSink(VideoSink { throw IllegalStateException("closed renderer") },clock) { failures++ }
        val buffer=Buffer();val frame=VideoFrame(buffer,0,1);sink.onFrame(frame);frame.release()
        clock.advance(4)
        assertEquals(0,buffer.refs);assertEquals(1,failures);assertTrue(clock.closed)
    }
}
