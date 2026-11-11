package com.xcloud.tclnative

import com.xcloud.tclnative.webrtc.UsbXInputProtocol
import com.xcloud.tclnative.webrtc.UsbXInputSession
import org.junit.Assert.*
import org.junit.Test

class UsbXInputProtocolTest {
    @Test fun onlyAnUnambiguousWiredXInputInterfaceIsAccepted() {
        assertTrue(UsbXInputProtocol.supported(0xff,0x5d,1,0,true,listOf(32)))
        for((klass,sub,protocol) in listOf(Triple(3,0,0),Triple(0xff,0x47,0xd0),Triple(0xff,0x5d,0x81)))
            assertFalse(UsbXInputProtocol.supported(klass,sub,protocol,0,true,listOf(32)))
        assertFalse(UsbXInputProtocol.supported(0xff,0x5d,1,1,true,listOf(32)))
        assertFalse(UsbXInputProtocol.supported(0xff,0x5d,1,0,false,listOf(32)))
        for(sizes in listOf(emptyList(),listOf(7),listOf(32,32)))
            assertFalse(UsbXInputProtocol.supported(0xff,0x5d,1,0,true,sizes))
    }
    @Test fun motorsAreIndependentClampedAndStopUsesTheActualEightByteProtocol() {
        assertArrayEquals(byteArrayOf(0,8,0,-1,127,0,0,0),UsbXInputProtocol.packet(100,50))
        assertArrayEquals(byteArrayOf(0,8,0,0,-1,0,0,0),UsbXInputProtocol.packet(-1,1000))
        assertArrayEquals(byteArrayOf(0,8,0,0,0,0,0,0),UsbXInputProtocol.packet(0,0))
    }
    @Test fun rumbleNeverRoutesToAnAmbiguousOrDifferentUsbController() {
        val usb=listOf(0x3537 to 0x1040)
        val inputs=listOf(Triple(7,0x3537,0x1040),Triple(8,0x45e,0x28e))
        assertEquals(7,UsbXInputProtocol.inputDeviceId(0x3537,0x1040,usb,inputs))
        assertNull(UsbXInputProtocol.inputDeviceId(0x3537,0x1040,usb+usb,inputs))
        assertNull(UsbXInputProtocol.inputDeviceId(0x3537,0x1040,usb,inputs+Triple(9,0x3537,0x1040)))
        assertNull(UsbXInputProtocol.inputDeviceId(0x3537,0x1040,usb,inputs.drop(1)))
        assertNull(UsbXInputProtocol.inputDeviceId(0,0,listOf(0 to 0),listOf(Triple(7,0,0))))
    }
    private class Io(var accepted: Boolean=true): UsbXInputSession.Io {
        val claims=mutableListOf<Boolean>()
        val packets=mutableListOf<ByteArray>()
        var released=0;var closed=0;var failWrite=false;var throwClaim=false
        override fun claim(force: Boolean): Boolean {
            claims.add(force)
            if(throwClaim) throw SecurityException("denied")
            return accepted
        }
        override fun write(packet: ByteArray): Boolean {
            if(failWrite) throw IllegalStateException("USB detached")
            packets.add(packet);return true
        }
        override fun release() { released++ }
        override fun close() { closed++ }
    }
    @Test fun occupiedOrDeniedInterfaceNeverGetsForceClaimedOrWritten() {
        for(throws in listOf(false,true)) {
            val io=Io(false).apply { throwClaim=throws }
            assertNull(UsbXInputSession.open(io))
            assertEquals(listOf(false),io.claims)
            assertTrue(io.packets.isEmpty())
            assertEquals(0,io.released);assertEquals(1,io.closed)
        }
    }
    @Test fun shutdownAlwaysSendsStopReleasesAndClosesExactlyOnce() {
        val io=Io();val session=UsbXInputSession.open(io)!!
        assertTrue(session.write(60,20));session.close();session.close()
        assertEquals(listOf(false),io.claims)
        assertArrayEquals(UsbXInputProtocol.packet(60,20),io.packets[0])
        assertArrayEquals(UsbXInputProtocol.packet(0,0),io.packets[1])
        assertEquals(1,io.released);assertEquals(1,io.closed)
        assertFalse(session.write(100,100))
    }
    @Test fun detachedReceiverStillReleasesResourcesWhenItsStopPacketFails() {
        val io=Io();val session=UsbXInputSession.open(io)!!
        io.failWrite=true;session.close()
        assertEquals(1,io.released);assertEquals(1,io.closed)
    }
}
