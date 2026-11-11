package com.xcloud.tclnative

import android.view.KeyEvent
import com.xcloud.tclnative.webrtc.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

class DesktopInputTest {
    @Test fun nativePacketsMatchTheOfficialV8MouseAndVKeyFieldLayout() {
        val bytes=GamepadPacket.encode(emptyMap(),7,1.5,keys=listOf(NativeKey(65,true)),
            mice=listOf(NativeMouse(-3,5,0,120,3)))
        assertEquals(37,bytes.size)
        val packet=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(96,packet.short.toInt());assertEquals(7,packet.int);assertEquals(1.5,packet.double,0.0)
        assertEquals(1,packet.get().toInt())
        assertEquals(-3,packet.int);assertEquals(5,packet.int);assertEquals(0,packet.int);assertEquals(120,packet.int)
        assertEquals(3,packet.get().toInt());assertEquals(0,packet.get().toInt())
        assertEquals(1,packet.get().toInt());assertEquals(2,packet.get().toInt());assertEquals(1,packet.get().toInt());assertEquals(65,packet.get().toInt())
        assertFalse(packet.hasRemaining())
    }
    @Test fun saturationAndFocusLossReleasePreviouslyReportedKeysEvenAfterTheirUpWasPending() {
        val buffer=DesktopInputBuffer();val remote=mutableSetOf<Int>()
        fun drain() { val batch=buffer.next();batch.first.forEach { if(it.pressed) remote.add(it.code) else remote.remove(it.code) } }
        buffer.key(NativeKey(65,true));drain();assertEquals(setOf(65),remote)
        buffer.key(NativeKey(65,false))
        repeat(200) { buffer.key(NativeKey(66,true));buffer.key(NativeKey(66,false)) }
        var batches=0
        while(buffer.pending()) { drain();batches++;assertTrue(batches<20) }
        assertTrue(remote.isEmpty())
        buffer.key(NativeKey(17,true));drain();buffer.release()
        while(buffer.pending()) drain()
        assertTrue(remote.isEmpty())
    }
    @Test fun mouseMotionCoalescesWithoutHidingButtonEdgesAndThePumpSharesASequence() {
        val buffer=DesktopInputBuffer()
        buffer.mouse(NativeMouse(2,3,buttons=1));buffer.mouse(NativeMouse(4,-1,buttons=1));buffer.mouse(NativeMouse(buttons=0))
        assertEquals(listOf(NativeMouse(6,2,buttons=1),NativeMouse()),buffer.next().second)
        val sent=CopyOnWriteArrayList<ByteArray>();val latch=CountDownLatch(1)
        val pump=RealtimeInputPump({0},{ sent.add(it);latch.countDown();true },{fail("input failed")},initialGamepads=emptySet(),desktop=buffer)
        pump.desktopKey(NativeKey(65,true));pump.start()
        assertTrue(latch.await(2,TimeUnit.SECONDS));pump.close()
        assertEquals(64,ByteBuffer.wrap(sent.first()).order(ByteOrder.LITTLE_ENDIAN).short.toInt())
        assertTrue(pump.metrics.sentBytes>0)
    }
    @Test fun emulationReleasesTheCameraWhileKeepingHeldKeysAndMouseButtons() {
        val gamepad=DesktopGamepad()
        assertTrue(gamepad.key(KeyEvent.KEYCODE_W,true));gamepad.mouse(10f,-5f,3,100)
        assertEquals(-1f,gamepad.state().leftY,0f)
        assertTrue(gamepad.state().rightX>0);assertEquals(1f,gamepad.state().rightTrigger,0f)
        gamepad.releaseMotion();assertEquals(0f,gamepad.state().rightX,0f);assertEquals(-1f,gamepad.state().leftY,0f)
        gamepad.reset();assertEquals(GamepadState(),gamepad.state())
        assertTrue(DesktopKeys.nativeSupported(setOf("MKB")))
        assertFalse(DesktopKeys.nativeSupported(setOf("Controller","NativeTouch")))
        assertEquals(65,DesktopKeys.windowsKey(KeyEvent.KEYCODE_A))
    }
}
