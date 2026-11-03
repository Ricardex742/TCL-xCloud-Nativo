package com.xcloud.tclnative

import com.xcloud.tclnative.webrtc.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MultiplayerInputTest {
    @Test fun controllerSlotsStayIndependentAcrossHotplugAndFocusReset() {
        val router=ControllerRouter()
        router.beginSession(true)
        assertEquals(listOf(0,1,2,3),(11..14).map(router::slot))
        assertNull(router.slot(15))
        router.update(0,GamepadState(buttons=16,leftX=.3f))
        router.update(1,GamepadState(buttons=32,rightTrigger=.8f))
        assertEquals(16,router.combined(0).buttons); assertEquals(32,router.combined(1).buttons)
        assertEquals(0f,router.combined(1).leftX,0f)
        assertEquals(1,router.remove(12)); assertEquals(2,router.slot(13)); assertEquals(1,router.slot(21))
        router.reset()
        assertEquals(GamepadState(),router.combined(0)); assertEquals(GamepadState(),router.combined(1))
        assertEquals(11,router.deviceFor(0)); assertEquals(21,router.deviceFor(1))
    }
    @Test fun touchPlayerOneCanCooperateWithAPhysicalPlayerTwoWithoutMixingInputs() {
        val router=ControllerRouter()
        router.beginSession(true,true)
        router.virtual(GamepadState(buttons=16,leftX=-1f))
        assertEquals(1,router.slot(40))
        router.update(1,GamepadState(buttons=32,leftX=1f))
        assertEquals(16,router.combined(0).buttons); assertEquals(-1f,router.combined(0).leftX,0f)
        assertEquals(32,router.combined(1).buttons); assertEquals(1f,router.combined(1).leftX,0f)
        assertNull(router.deviceFor(0)); assertEquals(40,router.deviceFor(1))
        router.beginSession(false)
        assertEquals(0,router.slot(50)); assertNull(router.slot(51))
    }
    @Test fun multiplayerPacketCarriesSeparateIndicesAndBothStickDirections() {
        val bytes=GamepadPacket.encode(mapOf(3 to GamepadState(buttons=128),0 to GamepadState(buttons=16,leftY=-1f)),7,24.0)
        assertEquals(61,bytes.size)
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2,b.short.toInt()); assertEquals(7,b.int); assertEquals(24.0,b.double,0.0)
        assertEquals(2,b.get().toInt()); assertEquals(0,b.get().toInt()); assertEquals(16,b.short.toInt())
        assertEquals(32767,b.getShort(20).toInt())
        assertEquals(3,b.get(38).toInt()); assertEquals(128,b.getShort(39).toInt())
        assertEquals(1,b.getInt(30)); assertEquals(1,b.order(ByteOrder.BIG_ENDIAN).getInt(34))
    }
    @Test fun remoteMetadataAndRumbleDecodeTogetherWithoutMovingTheCallerBuffer() {
        val data=ByteBuffer.allocate(23).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(144).putInt(720).putInt(1280).put(0).put(2).put(80).put(40).put(10).put(0)
            .putShort(350).putShort(25).put(2).flip() as ByteBuffer
        val report=RemoteInputReport.parse(data)!!
        assertEquals(0,data.position()); assertEquals(1280,report.width); assertEquals(720,report.height)
        assertEquals(RumbleCommand(2,80,40,10,0,350,25,2),report.rumble)
        assertEquals(80,report.rumble!!.intensity)
        assertNull(RemoteInputReport.parse(ByteBuffer.wrap(byteArrayOf(128.toByte(),0,0))))
        data.put(11,9)
        assertNull(RemoteInputReport.parse(data)!!.rumble)
    }
    @Test fun nativeTouchPacketsKeepAllFingerIdsCoordinatesAndReleaseEvents() {
        val points=listOf(NativePointer(3,.25f,.5f,1),NativePointer(9,.75f,.2f,2,0f))
        val bytes=GamepadPacket.encode(emptyMap(),4,3.0,points,1280,720)
        assertEquals(56,bytes.size)
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(4,b.short.toInt()); assertEquals(1,b.get(14).toInt()); assertEquals(2,b.get(15).toInt())
        assertEquals(3,b.getInt(23)); assertEquals(320,b.getInt(27)); assertEquals(360,b.getInt(31)); assertEquals(1,b.get(35).toInt())
        assertEquals(9,b.getInt(43)); assertEquals(2,b.get(55).toInt()); assertEquals(1,b.getShort(36).toInt())
        assertEquals(10,GamepadPacket.metadata(0.0,10).last().toInt())
    }
    @Test fun nativeTouchOverflowResynchronizesAndFocusLossReleasesEveryContact() {
        val input=NativeTouchBuffer()
        val remote=mutableSetOf<Int>()
        fun receive(points: List<NativePointer>) { points.forEach { if(it.type==2) remote.remove(it.id) else remote.add(it.id) } }
        input.add(listOf(NativePointer(3,.2f,.2f,1))); receive(input.next())
        repeat(120) { input.add(listOf(NativePointer(9,.5f,.5f,if(it%2==0) 1 else 2))) }
        input.release()
        var batches=0
        while(input.pending()) { receive(input.next()); batches++; assertTrue(batches<=32) }
        assertTrue(remote.isEmpty())
    }
    @Test fun openingAMenuAfterFingerUpDoesNotDuplicateTheNativeTapRelease() {
        val input=NativeTouchBuffer()
        val down=NativePointer(3,.2f,.2f,1)
        input.add(listOf(down)); input.next()
        input.add(listOf(down.copy(type=2,pressure=0f)))
        input.release()
        assertEquals(listOf(2),input.next().map { it.type })
        assertFalse(input.pending())
    }
    @Test fun virtualThumbsticksButtonsAndTriggersWorkAtTheSameTimeAndReleaseSeparately() {
        val input=VirtualGamepad(1280,720)
        val left=input.controls.single { it.kind==TouchKind.LEFT_STICK }
        val a=input.controls.single { it.label=="A" }
        val trigger=input.controls.single { it.label=="RT" }
        input.down(1,left.x,left.y); input.move(1,left.x+left.radius*2,left.y)
        input.down(2,a.x,a.y); input.down(3,trigger.x,trigger.y)
        assertEquals(1f,input.state().leftX,0f); assertEquals(16,input.state().buttons); assertEquals(1f,input.state().rightTrigger,0f)
        input.up(2)
        assertEquals(0,input.state().buttons); assertEquals(1f,input.state().rightTrigger,0f)
        input.clear(); assertEquals(GamepadState(),input.state())
    }
}
