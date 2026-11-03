package com.xcloud.tclnative

import com.xcloud.tclnative.webrtc.GamepadInputBuffer
import com.xcloud.tclnative.webrtc.GamepadState
import org.junit.Assert.*
import org.junit.Test

class InputBufferTest {
    @Test fun nativePumpKeepsBothPlayersAndDisconnectingOneDoesNotReleaseTheOther() {
        val received=java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        val pump=com.xcloud.tclnative.webrtc.RealtimeInputPump({ 0 },{ received.offer(it); true },{ fail("Send failed") })
        fun next(count: Int): ByteArray {
            val end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while(System.nanoTime()<end) {
                val data=received.poll(100,java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                if(data[14].toInt()==count) return data
            }
            throw AssertionError("No packet with $count players")
        }
        try {
            pump.update(GamepadState(buttons=16),0); pump.update(GamepadState(buttons=32),1)
            pump.start()
            val both=java.nio.ByteBuffer.wrap(next(2)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            assertEquals(16,both.getShort(16).toInt()); assertEquals(32,both.getShort(39).toInt())
            pump.remove(1)
            val one=java.nio.ByteBuffer.wrap(next(1)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            assertEquals(16,one.getShort(16).toInt())
        } finally { pump.close() }
    }
    @Test fun quickPressAndReleaseBetweenPeriodicTicksAreBothPreserved() {
        val input = GamepadInputBuffer()
        assertTrue(input.update(GamepadState(buttons = 16)))
        assertTrue(input.update(GamepadState()))
        assertEquals(16, input.next().buttons)
        assertEquals(0, input.next().buttons)
        assertEquals(0, input.next().buttons)
    }
    @Test fun analogMotionUsesTheLatestStateInsteadOfQueuingOldPositions() {
        val input = GamepadInputBuffer()
        repeat(100) { input.update(GamepadState(leftX = it / 100f)) }
        assertFalse(input.hasEdges())
        assertEquals(0.99f, input.next().leftX, 0.001f)
    }
    @Test fun congestionDropsOldButtonStatesButPreservesTheCurrentRelease() {
        val input = GamepadInputBuffer()
        input.update(GamepadState(buttons = 16)); input.update(GamepadState())
        input.discardBacklog()
        assertFalse(input.hasEdges())
        assertEquals(0, input.next().buttons)
        assertEquals(2L, input.coalescedEdges)
    }
    @Test fun excessiveTransitionsCannotBecomeAnUnboundedInputReplay() {
        val input = GamepadInputBuffer()
        repeat(40) { input.update(GamepadState(buttons = if (it % 2 == 0) 16 else 0)) }
        var pending = 0
        while (input.hasEdges()) { input.next(); pending++ }
        assertTrue(pending <= 8)
        assertTrue(input.coalescedEdges > 0)
        assertEquals(0, input.next().buttons)
    }

    @Test fun liveInputPumpDoesNotFloodABlockedChannelAndResumesWithTheCurrentState() {
        val queued = java.util.concurrent.atomic.AtomicLong(com.xcloud.tclnative.webrtc.RealtimeInputPump.MAX_BUFFERED_BYTES)
        val received = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        val pump = com.xcloud.tclnative.webrtc.RealtimeInputPump({ queued.get() }, { received.offer(it); true }, { fail("Unexpected send failure") })
        try {
            pump.update(GamepadState(buttons = 16))
            pump.start()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while (pump.metrics.backpressureSkips == 0L && System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue(pump.metrics.backpressureSkips > 0)
            assertTrue(received.isEmpty())
            pump.update(GamepadState())
            queued.set(0)
            val packet = received.poll(2, java.util.concurrent.TimeUnit.SECONDS)
            assertNotNull(packet)
            assertEquals(0, java.nio.ByteBuffer.wrap(packet).order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort(16).toInt())
        } finally { pump.close() }
    }
    @Test fun failedNativeSendTerminatesThePumpAndReportsFailure() {
        val failures = java.util.concurrent.atomic.AtomicInteger()
        val failed = java.util.concurrent.CountDownLatch(1)
        val pump = com.xcloud.tclnative.webrtc.RealtimeInputPump({ 0 }, { false }, { failures.incrementAndGet(); failed.countDown() })
        try {
            pump.start()
            assertTrue(failed.await(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally { pump.close() }
        assertEquals(1, failures.get())
    }
}
