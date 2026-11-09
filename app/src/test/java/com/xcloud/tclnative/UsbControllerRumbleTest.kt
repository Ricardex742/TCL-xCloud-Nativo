package com.xcloud.tclnative

import com.xcloud.tclnative.webrtc.UsbControllerRumble
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,34])
class UsbControllerRumbleTest {
    @Test fun absentReceiverStaysOptInAndDoesNotStartAUsbThreadOnStopOrClose() {
        val before=Thread.getAllStackTraces().keys.count { it.name=="xcloud-usb-rumble" }
        val usb=UsbControllerRumble(RuntimeEnvironment.getApplication())
        assertTrue(usb.candidates().isEmpty())
        assertTrue(usb.describe().contains("Nenhum dispositivo USB"))
        assertFalse(usb.accepts(1))
        usb.stop();usb.setForeground(false);usb.disable();usb.close();usb.close()
        assertFalse(usb.accepts(1))
        assertEquals(before,Thread.getAllStackTraces().keys.count { it.name=="xcloud-usb-rumble" })
    }
}
