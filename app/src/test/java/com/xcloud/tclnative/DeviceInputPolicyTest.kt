package com.xcloud.tclnative

import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.util.SparseArray
import android.view.InputDevice
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.signaling.GameTitle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowInputDevice
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DeviceInputPolicyTest {
    private val native=GameTitle("FORTNITE","Fortnite",supportedInputTypes=setOf("Controller","nativetouch"))
    private val standard=GameTitle("OTHER","Other",supportedInputTypes=setOf("Controller"))
    private fun installInputs(vararg sources: Int) {
        // Test-only framework fixture; production uses the public InputDevice API.
        val manager=ReflectionHelpers.callStaticMethod<InputManager>(InputManager::class.java,"getInstance")
        val devices=SparseArray<InputDevice>()
        sources.forEachIndexed { index,source ->
            val device=ShadowInputDevice.makeInputDeviceNamed("Input $index")
            ReflectionHelpers.setField(device,"mId",index)
            ReflectionHelpers.setField(device,"mSources",source)
            devices.put(index,device)
        }
        ReflectionHelpers.setField(manager,"mInputDevices",devices)
    }
    @Test @Config(sdk=[25,28],qualifiers="land-finger")
    fun genericBoxWithAFalseTouchscreenFeatureAndOnlyMouseOrControllerKeepsTheHudOff() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        try {
            shadowOf(a.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN,true)
            assertEquals(Configuration.TOUCHSCREEN_FINGER,a.resources.configuration.touchscreen)
            installInputs(InputDevice.SOURCE_MOUSE,InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK,
                InputDevice.SOURCE_KEYBOARD,InputDevice.SOURCE_TOUCHPAD)
            val device=InputDeviceProfile.read(a)
            assertFalse(device.television);assertTrue(device.touchscreen);assertTrue(device.configuredTouchscreen)
            assertFalse(device.directTouchInput);assertFalse(device.touchDevice)
            for(preference in TouchMode.entries) {
                assertEquals(TouchMode.OFF,DeviceInputPolicy.resolve(device,preference,native))
                assertEquals(TouchMode.OFF,DeviceInputPolicy.resolve(device,preference,standard))
            }
        } finally { c.pause().stop().destroy() }
    }
    @Test @Config(sdk=[25,28],qualifiers="land-finger")
    fun realTouchscreenKeepsNativeAndVirtualTouchEvenWithAnAttachedMouseOrGamepad() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        try {
            shadowOf(a.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN,true)
            installInputs(InputDevice.SOURCE_TOUCHSCREEN or InputDevice.SOURCE_STYLUS,
                InputDevice.SOURCE_MOUSE,InputDevice.SOURCE_GAMEPAD)
            val device=InputDeviceProfile.read(a)
            assertTrue(device.directTouchInput);assertTrue(device.touchDevice)
            assertEquals(TouchMode.NATIVE,DeviceInputPolicy.resolve(device,TouchMode.AUTO,native))
            assertEquals(TouchMode.VIRTUAL,DeviceInputPolicy.resolve(device,TouchMode.AUTO,standard))
            assertEquals(TouchMode.VIRTUAL,DeviceInputPolicy.resolve(device,TouchMode.VIRTUAL,native))
            assertEquals(TouchMode.OFF,DeviceInputPolicy.resolve(device,TouchMode.OFF,native))
        } finally { c.pause().stop().destroy() }
    }
    @Test @Config(sdk=[25,28],qualifiers="land-notouch")
    fun aNoTouchScreenConfigurationPreventsAutoHudEvenWhenTheFeatureAndInputAreAdvertised() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        try {
            shadowOf(a.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN,true)
            installInputs(InputDevice.SOURCE_TOUCHSCREEN)
            val device=InputDeviceProfile.read(a)
            assertTrue(device.touchscreen);assertTrue(device.directTouchInput)
            assertFalse(device.configuredTouchscreen);assertFalse(device.touchDevice)
            assertEquals(TouchMode.OFF,DeviceInputPolicy.resolve(device,TouchMode.AUTO,standard))
        } finally { c.pause().stop().destroy() }
    }
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
