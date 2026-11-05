package com.xcloud.tclnative

import android.app.Activity
import android.content.pm.PackageManager
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.signaling.GameTitle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DeviceInputPolicyTest {
    private val native=GameTitle("FORTNITE","Fortnite",supportedInputTypes=setOf("Controller","nativetouch"))
    private val standard=GameTitle("OTHER","Other",supportedInputTypes=setOf("Controller"))
    @Test fun phoneDefaultsToNativeAndHonorsExclusiveVirtualAndPhysicalPreferences() {
        val phone=InputDeviceProfile(false,true)
        assertEquals(TouchMode.NATIVE,DeviceInputPolicy.resolve(phone,TouchMode.AUTO,native))
        assertEquals(TouchMode.NATIVE,DeviceInputPolicy.resolve(phone,TouchMode.NATIVE,native))
        assertEquals(TouchMode.VIRTUAL,DeviceInputPolicy.resolve(phone,TouchMode.VIRTUAL,native))
        assertEquals(TouchMode.OFF,DeviceInputPolicy.resolve(phone,TouchMode.OFF,native))
        assertEquals(TouchMode.VIRTUAL,DeviceInputPolicy.resolve(phone,TouchMode.AUTO,standard))
        assertEquals(TouchMode.VIRTUAL,DeviceInputPolicy.resolve(phone,TouchMode.NATIVE,standard))
    }
    @Test fun televisionNeverStartsTouchOrHudEvenIfItAdvertisesATouchscreen() {
        for(touch in listOf(false,true)) for(preference in TouchMode.entries) {
            assertEquals(TouchMode.OFF,DeviceInputPolicy.resolve(InputDeviceProfile(true,touch),preference,native))
        }
        assertEquals(TouchMode.OFF,DeviceInputPolicy.resolve(InputDeviceProfile(false,false),TouchMode.AUTO,native))
        val c=Robolectric.buildActivity(Activity::class.java).setup(); val a=c.get()
        shadowOf(a.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK,true)
        shadowOf(a.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN,true)
        assertFalse(InputDeviceProfile.read(a).touchDevice)
        c.pause().stop().destroy()
    }
}
