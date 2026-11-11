package com.xcloud.tclnative

import com.xcloud.tclnative.settings.GyroMode
import com.xcloud.tclnative.webrtc.*
import org.junit.Assert.*
import org.junit.Test

class AuxiliaryAudioInputTest {
    @Test fun microphoneRequiresEveryConsentSessionAndForegroundCondition() {
        for(mask in 0..31) assertEquals(mask==31,MicrophonePolicy.canCapture(mask and 1!=0,mask and 2!=0,mask and 4!=0,mask and 8!=0,mask and 16!=0))
    }
    @Test fun audioAmplificationRampsUpDropsOnClippingAndHonorsMuteAndUnsupportedFormats() {
        val guard=AudioPeakGuard();guard.set(300)
        assertEquals(1.0,guard.effective,0.0)
        val quiet=byteArrayOf(0,1,0,-1)
        repeat(70) { guard.observe(quiet,2,(it+1)*100L) }
        assertEquals(3.0,guard.effective,.00001)
        guard.observe(byteArrayOf(-1,127,0,-128),2,7100)
        assertTrue(guard.effective<=1.0)
        guard.set(0);assertEquals(0.0,guard.effective,0.0)
        guard.set(200);guard.observe(quiet,4,7200)
        assertFalse(guard.measured);assertEquals(1.0,guard.effective,0.0)
    }
    @Test fun gyroHasExplicitActivationAndLandscapeRotationWithBoundedFiniteAxes() {
        assertFalse(GyroAim.active(GyroMode.OFF,GamepadState(leftTrigger=1f)))
        assertFalse(GyroAim.active(GyroMode.LT,GamepadState()))
        assertTrue(GyroAim.active(GyroMode.LT,GamepadState(leftTrigger=1f)))
        assertTrue(GyroAim.active(GyroMode.LB,GamepadState(buttons=4096)))
        val landscape=GyroAim.axes(1f,2f,1,100)
        assertTrue(landscape.first<0);assertTrue(landscape.second>0)
        assertEquals(0f to 0f,GyroAim.axes(Float.NaN,0f,0,100))
        assertEquals(-1f,GyroAim.axes(100f,0f,1,300).first,0f)
    }
    @Test fun ipv6PreferenceReordersWithoutChangingPrioritiesOrDroppingIpv4() {
        val four=org.webrtc.IceCandidate("0",0,"candidate:1 1 UDP 100 192.0.2.1 1234 typ host")
        val six=org.webrtc.IceCandidate("0",0,"candidate:2 1 UDP 99 2001:db8::1 1234 typ host")
        val input=listOf(four,six)
        assertSame(input,IceFamilyPolicy.ordered(input,false))
        assertEquals(listOf(six,four),IceFamilyPolicy.ordered(input,true))
        assertSame(four,IceFamilyPolicy.ordered(input,true)[1])
        assertEquals(listOf(four),IceFamilyPolicy.ordered(listOf(four),true))
    }
}
