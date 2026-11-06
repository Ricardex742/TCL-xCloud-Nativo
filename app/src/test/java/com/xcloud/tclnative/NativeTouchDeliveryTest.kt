package com.xcloud.tclnative

import com.xcloud.tclnative.webrtc.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class NativeTouchDeliveryTest {
    private val a=NativePointer(1,.2f,.3f,1)
    private val b=NativePointer(2,.7f,.6f,1)
    @Test fun coalescingPartialMotionKeepsTheLatestPositionOfEachFinger() {
        val buffer=NativeTouchBuffer()
        buffer.add(listOf(a,b));buffer.next()
        buffer.add(listOf(a.copy(type=3,x=.3f)))
        buffer.add(listOf(b.copy(type=3,y=.8f)))
        buffer.add(listOf(a.copy(type=3,x=.4f)))
        val moved=buffer.next().associateBy { it.id }
        assertEquals(setOf(1,2),moved.keys)
        assertEquals(.4f,moved.getValue(1).x,0f)
        assertEquals(.8f,moved.getValue(2).y,0f)
        assertFalse(buffer.pending())
    }
    @Test fun mergingMovementNeverCrossesOrRemovesAPressOrRelease() {
        val buffer=NativeTouchBuffer()
        buffer.add(listOf(a))
        buffer.add(listOf(a.copy(type=3,x=.4f)))
        buffer.add(listOf(a.copy(type=2,x=.4f,pressure=0f)))
        buffer.add(listOf(a.copy(x=.8f)))
        buffer.add(listOf(a.copy(type=3,x=.9f)))
        buffer.add(listOf(a.copy(type=2,x=.9f,pressure=0f)))
        val received=mutableListOf<NativePointer>()
        while(buffer.pending()) received+=buffer.next()
        assertEquals(listOf(1,3,2,1,3,2),received.map { it.type })
        assertEquals(listOf(.2f,.4f,.4f,.8f,.9f,.9f),received.map { it.x })
    }
    @Test fun aBurstOfSingleFingerTapsKeepsEveryTransitionThroughTheRealPumpAndSerializer() {
        val buffer=NativeTouchBuffer()
        repeat(8) { i ->
            val down=a.copy(x=.1f+i*.1f)
            buffer.add(listOf(down));buffer.add(listOf(down.copy(type=2,pressure=0f)))
        }
        val packets=LinkedBlockingQueue<ByteArray>()
        val pump=RealtimeInputPump({0},{ packets.offer(it);true },{fail("Input send failed")},
            initialGamepads=emptySet(),touch=buffer,initialTouchDimensions=TouchInputDimensions(1920,1080))
        try {
            pump.start()
            val frames=(0 until 16).map {
                val packet=packets.poll(2,TimeUnit.SECONDS) ?: throw AssertionError("Missing native tap packet")
                ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
            }
            assertEquals((1..16).toList(),frames.map { it.getInt(2) })
            assertEquals(List(8) { listOf(1,2) }.flatten(),frames.map { it.get(35).toInt() })
            assertTrue(frames.all { it.getInt(23)==1 })
        } finally { pump.close() }
        val diagnostics=buffer.diagnostics()
        assertTrue(diagnostics,diagnostics.contains("entrada D/U/M 8/8/0; aceitos pelo canal D/U/M 8/8/0"))
        assertTrue(diagnostics.contains("ressincronizações 0"))
    }
    @Test fun aFailedSendIsNotCountedAsAnAcceptedTouch() {
        val buffer=NativeTouchBuffer()
        buffer.add(listOf(a))
        val failed=CountDownLatch(1)
        val pump=RealtimeInputPump({0},{false},{failed.countDown()},initialGamepads=emptySet(),
            touch=buffer,initialTouchDimensions=TouchInputDimensions(1920,1080))
        try { pump.start();assertTrue(failed.await(2,TimeUnit.SECONDS)) } finally { pump.close() }
        val diagnostics=buffer.diagnostics()
        assertTrue(diagnostics,diagnostics.contains("entrada D/U/M 1/0/0; aceitos pelo canal D/U/M 0/0/0"))
    }
    @Test fun overflowAndPendingWorkAreVisibleWithoutRetainingAnUnboundedReplayOrCoordinatesInTheReport() {
        val buffer=NativeTouchBuffer()
        repeat(120) { buffer.add(listOf(a.copy(type=if(it%2==0) 1 else 2))) }
        buffer.release()
        val report=buffer.diagnostics()
        assertTrue(report,Regex("ressincronizações [1-9][0-9]*").containsMatchIn(report))
        var batches=0
        while(buffer.pending()) { buffer.next();batches++;assertTrue(batches<=32) }
        assertFalse(report.contains(".2"));assertFalse(report.contains(".3"))
        assertTrue(buffer.diagnostics().contains("lotes pendentes 0"))
    }
}
