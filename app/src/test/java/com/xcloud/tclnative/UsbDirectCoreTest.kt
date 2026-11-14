package com.xcloud.tclnative

import com.xcloud.tclnative.webrtc.*
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

class UsbDirectCoreTest {
    private fun report(first: Int=0,second: Int=0)=ByteArray(20).apply { this[1]=20;this[2]=first.toByte();this[3]=second.toByte() }
    @Test fun standardButtonsAreMappedWithoutTreatingStatusOrVendorBitsAsActions() {
        val state=UsbXInputParser.parse(report(255,255))!!
        assertEquals(65534,state.buttons)
        assertEquals(0,UsbXInputParser.parse(report(0,8))!!.buttons)
        for(length in listOf(0,2,13,14,19,21,32)) assertNull(UsbXInputParser.parse(ByteArray(length),length))
        assertNull(UsbXInputParser.parse(report().apply { this[0]=1 }))
        assertNull(UsbXInputParser.parse(report().apply { this[1]=19 }))
        assertNull(UsbXInputParser.parse(report(),21))
    }
    @Test fun axesKeepSixteenBitPrecisionSignedExtremesAndAnalogTriggers() {
        val bytes=report().apply { this[4]=128.toByte();this[5]=255.toByte() }
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(6,1).putShort(8,Short.MIN_VALUE)
            .putShort(10,Short.MAX_VALUE).putShort(12,Short.MAX_VALUE)
        val state=UsbXInputParser.parse(bytes)!!
        assertEquals(1/32767f,state.leftX,0f);assertTrue(state.leftX>0f && state.leftX<.001f)
        assertEquals(1f,state.leftY,0f);assertEquals(1f,state.rightX,0f);assertEquals(-1f,state.rightY,0f)
        assertEquals(128/255f,state.leftTrigger,0f);assertEquals(1f,state.rightTrigger,0f)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(6,Short.MIN_VALUE)
        assertEquals(-1f,UsbXInputParser.parse(bytes)!!.leftX,0f)
    }
    @Test fun usbHandoverKeepsThePlayerAndAnOldAndroidRemovalCannotRemoveUsb() {
        val router=ControllerRouter();router.beginSession(true)
        val first=ControllerSource.Usb("bus-a@1",0x2dc8,1,0,"Demo")
        val second=first.copy(connection="bus-b@1")
        assertEquals(0,router.slot(7));router.update(0,GamepadState(buttons=16))
        assertEquals(0,router.attachUsb(first,7));assertNull(router.deviceFor(0))
        assertEquals(first,router.sourceFor(0));assertEquals(GamepadState(),router.physical(0))
        assertNull(router.remove(7));assertEquals(first,router.sourceFor(0))
        assertEquals(1,router.slot(second));assertEquals(second,router.sourceFor(1))
        assertEquals(0,router.remove(first));assertEquals(second,router.sourceFor(1))
        val refreshed=first.copy(connection="bus-a@2")
        assertEquals(0,router.slot(refreshed));assertNull(router.remove(first));assertEquals(refreshed,router.sourceFor(0))
    }
    @Test fun usbRespectsTouchReservationAndSinglePlayerLimits() {
        val source=ControllerSource.Usb("bus",1,2,0,"Demo")
        val router=ControllerRouter();router.beginSession(true,true)
        assertEquals(1,router.slot(source));router.virtual(GamepadState(buttons=16));router.update(1,GamepadState(buttons=32))
        assertEquals(16,router.combined(0).buttons);assertEquals(32,router.combined(1).buttons)
        router.beginSession(false);assertEquals(0,router.slot(source));assertNull(router.slot(42))
    }
    @Test fun motionCoalescesButPressReleaseAndAnalogTriggerEdgesStayInOrder() {
        val queue=UsbInputQueue()
        repeat(20) { queue.offer(GamepadState(leftX=it/20f),it.toLong()) }
        queue.offer(GamepadState(buttons=16),21);queue.offer(GamepadState(),22)
        queue.offer(GamepadState(leftTrigger=.1f),23);queue.offer(GamepadState(leftTrigger=1f),24);queue.offer(GamepadState(),25)
        val items=generateSequence { queue.poll() }.toList()
        assertEquals(listOf(0,16,0,0,0),items.map { it.state.buttons })
        assertEquals(1f,items[3].state.leftTrigger,0f);assertEquals(20,queue.coalesced)
        val bounded=UsbInputQueue(4)
        repeat(20) { bounded.offer(GamepadState(buttons=if(it%2==0) 16 else 0),it.toLong()) }
        assertTrue(bounded.overflows>0);assertTrue(generateSequence { bounded.poll() }.toList().size<=4)
    }
    @Test fun menuSticksHaveHysteresisAndOnlyDirectionsRepeat() {
        val nav=ControllerNavigation()
        assertTrue(nav.update(GamepadState(leftX=.5f),0).isEmpty())
        assertEquals(listOf(ControllerNavigation.Stroke(KeyEvent.KEYCODE_DPAD_RIGHT,true)),nav.update(GamepadState(leftX=.8f),1))
        assertTrue(nav.update(GamepadState(leftX=.4f),20).isEmpty())
        assertTrue(nav.repeat(350).isEmpty());assertEquals(1,nav.repeat(351).single().repeat)
        assertTrue(nav.update(GamepadState(leftX=.3f),360).single().down.not())
        assertEquals(KeyEvent.KEYCODE_BUTTON_A,nav.update(GamepadState(buttons=16),400).single().key)
        assertTrue(nav.repeat(2000).isEmpty());nav.clear();assertFalse(nav.repeating)
    }
    @Test fun rumbleEnvelopeHonorsDelayRepeatIndependentMotorsAndReplacement() {
        val envelope=UsbRumbleEnvelope()
        envelope.set(RumbleCommand(0,80,20,0,0,100,50,1),1000)
        assertEquals(0 to 0,envelope.levels(1049));assertEquals(80 to 20,envelope.levels(1050))
        assertEquals(0 to 0,envelope.levels(1150));assertEquals(80 to 20,envelope.levels(1200))
        assertEquals(0 to 0,envelope.levels(1300))
        envelope.set(RumbleCommand(0,0,50,0,0,200,0,0),1250)
        assertEquals(0 to 50,envelope.levels(1300));envelope.stop();assertEquals(0 to 0,envelope.levels(1301))
    }
    private class Io(var accepted: Boolean=true): UsbGamepadSession.Io {
        val claims=mutableListOf<Boolean>();val packets=mutableListOf<ByteArray>()
        val completions=ArrayDeque<UsbGamepadSession.Completion>()
        var released=0;var closed=0;var cancelled=0;var clock=0L;var pending=false
        var onPoll: () -> Unit={};var failWrite=false
        override fun claim(force: Boolean): Boolean { claims+=force;return accepted || force }
        override fun startInput()=true
        override fun poll(timeoutMs: Long): UsbGamepadSession.Completion {
            clock+=timeoutMs;onPoll()
            val result=if(completions.isEmpty()) UsbGamepadSession.Completion.Timeout else completions.removeFirst()
            if(result==UsbGamepadSession.Completion.Output) pending=false
            return result
        }
        override fun write(packet: ByteArray): Boolean {
            assertFalse("A pending OUT buffer cannot be reused",pending)
            if(failWrite) return false
            pending=true;packets+=packet.clone();completions.addLast(UsbGamepadSession.Completion.Output);return true
        }
        override fun cancelInput() { cancelled++ }
        override fun release() { released++ }
        override fun close() { closed++ }
    }
    @Test fun oneDispatcherHandlesInputBeforeOutputAndStopsMotorsOnExit() {
        val io=Io();val pressed=report(second=16)
        io.completions.add(UsbGamepadSession.Completion.Input(pressed,20))
        val received=mutableListOf<GamepadState?>();var ready=false;lateinit var session: UsbGamepadSession
        session=UsbGamepadSession(io,false,{io.clock},{ _,_,state,_ -> received+=state },{ ready=true })
        session.rumble.set(RumbleCommand(0,80,20,0,0,1000,0,0),0)
        io.onPoll={ if(io.clock>=80) session.stop() }
        session.run()
        assertTrue(ready);assertEquals(16,received.single()!!.buttons)
        assertArrayEquals(UsbXInputProtocol.packet(80,20),io.packets.first())
        assertArrayEquals(UsbXInputProtocol.packet(0,0),io.packets.last())
        assertEquals(1,io.released);assertEquals(1,io.closed);assertEquals(1,io.cancelled)
    }
    @Test fun aSilentControllerKeepsItsHeldInputAndUnknownPacketsAreIgnored() {
        val io=Io();io.completions.add(UsbGamepadSession.Completion.Input(report(first=1),20))
        io.completions.add(UsbGamepadSession.Completion.Input(byteArrayOf(1,3,0),3))
        val received=mutableListOf<GamepadState?>();lateinit var session: UsbGamepadSession
        session=UsbGamepadSession(io,false,{io.clock},{ _,_,state,_ -> received+=state },{})
        io.onPoll={ if(io.clock>=1600) session.stop() };session.run()
        assertEquals(2,received.size);assertEquals(256,received.first()!!.buttons);assertNull(received.last())
    }
    @Test fun busyInterfaceRequiresExplicitExclusivePolicyAndAlwaysCloses() {
        val io=Io(false)
        try { UsbGamepadSession(io,false,{0},{ _,_,_,_ -> },{}).run();fail("Busy interface") }
        catch(error: IllegalStateException) { assertEquals("USB_INTERFACE_BUSY",error.message) }
        assertEquals(listOf(false),io.claims);assertEquals(0,io.released);assertEquals(1,io.closed);assertTrue(io.packets.isEmpty())
        val exclusive=Io(false);lateinit var session: UsbGamepadSession
        session=UsbGamepadSession(exclusive,true,{exclusive.clock},{ _,_,_,_ -> },{ session.stop() });session.run()
        assertEquals(listOf(false,true),exclusive.claims);assertEquals(1,exclusive.released);assertEquals(1,exclusive.closed)
    }
    @Test fun outputFailureStillReleasesTheInterfaceAndClosesTheConnection() {
        val io=Io().apply { failWrite=true }
        try { UsbGamepadSession(io,false,{0},{ _,_,_,_ -> },{}).run();fail("Output failure") }
        catch(error: IllegalStateException) { assertEquals("USB_OUTPUT_QUEUE",error.message) }
        assertEquals(1,io.released);assertEquals(1,io.closed)
    }
}
