package com.xcloud.tclnative

import com.xcloud.tclnative.webrtc.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class NativeGyroDeliveryTest {
    private fun view(bytes: ByteArray)=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private fun next(packets: LinkedBlockingQueue<ByteArray>,predicate: (ByteBuffer)->Boolean): ByteBuffer {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
        while(System.nanoTime()<end) {
            val bytes=packets.poll(50,TimeUnit.MILLISECONDS) ?: continue
            val packet=view(bytes)
            if(predicate(packet)) return packet
        }
        throw AssertionError("Expected input report not received")
    }
    @Test fun gyroAndContactsShareTheSerializerAndNeutralRetiresOnlyTheAuxiliaryStick() {
        val packets=LinkedBlockingQueue<ByteArray>();val touch=NativeTouchBuffer()
        val pump=RealtimeInputPump({0},{packets.offer(it);true},{fail("Input failed")},initialGamepads=emptySet(),
            touch=touch,initialTouchDimensions=TouchInputDimensions(1920,1080))
        val finger=NativePointer(7,.25f,.5f,1)
        pump.update(GamepadState(rightX=.5f));pump.pointers(listOf(finger))
        try {
            pump.start()
            val down=next(packets) { it.getShort(0).toInt()==6 }
            assertEquals(16384,down.getShort(22).toInt());assertEquals(7,down.getInt(47));assertEquals(1,down.get(59).toInt())
            pump.releaseAfterNeutral()
            val neutral=next(packets) { it.getShort(0).toInt() and 2!=0 && it.getShort(22).toInt()==0 }
            assertTrue(neutral.getInt(2)>down.getInt(2))
            pump.pointers(listOf(finger.copy(type=3,x=.6f)))
            val move=next(packets) { it.getShort(0).toInt()==4 }
            assertEquals(7,move.getInt(23));assertEquals(1152,move.getInt(27));assertEquals(3,move.get(35).toInt())
            pump.pointers(listOf(finger.copy(type=2,pressure=0f)))
            assertEquals(2,next(packets) { it.getShort(0).toInt()==4 }.get(35).toInt())
        } finally { pump.close() }
        assertTrue(touch.diagnostics().contains("aceitos pelo canal D/U/M 1/1/1"))
        assertFalse(GamepadConnections().remove(0))
    }
    @Test fun newAimRacingTheNeutralSendIsNotRetired() {
        val packets=LinkedBlockingQueue<ByteArray>()
        lateinit var pump: RealtimeInputPump
        var resumed=false
        pump=RealtimeInputPump({0},{ bytes ->
            if(!resumed && view(bytes).getShort(22).toInt()==0) {
                resumed=true;pump.update(GamepadState(rightX=.75f))
            }
            packets.offer(bytes);true
        },{fail("Input failed")},initialGamepads=emptySet())
        pump.update(GamepadState(rightX=.5f));pump.releaseAfterNeutral()
        try {
            pump.start();next(packets) { it.getShort(22).toInt()==0 }
            next(packets) { it.getShort(22).toInt()>20000 }
            next(packets) { it.getShort(22).toInt()>20000 }
            assertTrue(resumed)
        } finally { pump.close() }
    }
    @Test fun backpressureCannotRetireBeforeNeutralIsAccepted() {
        val queued=AtomicLong(10000);val packets=LinkedBlockingQueue<ByteArray>()
        val pump=RealtimeInputPump({queued.get()},{packets.offer(it);true},{fail("Input failed")},initialGamepads=emptySet())
        pump.update(GamepadState(rightX=.5f));pump.releaseAfterNeutral()
        try {
            pump.start()
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            while(pump.metrics.backpressureSkips==0L && System.nanoTime()<end) Thread.yield()
            assertTrue(pump.metrics.backpressureSkips>0);assertTrue(packets.isEmpty())
            queued.set(0)
            assertEquals(0,next(packets) { it.getShort(0).toInt()==2 }.getShort(22).toInt())
            assertNull(packets.poll(40,TimeUnit.MILLISECONDS))
        } finally { pump.close() }
    }
}
