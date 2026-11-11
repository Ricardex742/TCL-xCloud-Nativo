package com.xcloud.tclnative

import android.app.Activity
import android.view.InputDevice
import android.view.View
import com.xcloud.tclnative.webrtc.JoystickDispatchPreference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class JoystickDispatchPreferenceTest {
    private fun source(view: View)=ReflectionHelpers.getField<Int>(view,"mUnbufferedInputSource")
    @Test fun immediateJoystickDispatchUsesTheRealPlatformRequestAndRestoresBufferedMenus() {
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val root=c.get().window.decorView
            assertTrue(root.isAttachedToWindow)
            val preference=JoystickDispatchPreference(root)
            preference.request(true)
            assertEquals(InputDevice.SOURCE_CLASS_JOYSTICK,source(root))
            assertEquals(0,source(root) and InputDevice.SOURCE_CLASS_POINTER)
            assertTrue(preference.diagnostics().contains("solicitado"))
            preference.request(false) // Focus/menu/background/end-of-session all restore the same default.
            assertEquals(InputDevice.SOURCE_CLASS_NONE,source(root))
            preference.request(true)
            assertEquals(InputDevice.SOURCE_CLASS_JOYSTICK,source(root))
            preference.request(false)
            assertEquals(InputDevice.SOURCE_CLASS_NONE,source(root))
        } finally { c.pause().stop().destroy() }
    }
    @Test fun anUnattachedViewIsNotReportedAsAnActiveUnbufferedWindow() {
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val view=View(c.get())
            val preference=JoystickDispatchPreference(view)
            preference.request(true)
            assertFalse(view.isAttachedToWindow)
            assertEquals(InputDevice.SOURCE_CLASS_NONE,source(view))
            assertTrue(preference.diagnostics().contains("padrão"))
            c.get().setContentView(view)
            assertTrue(view.isAttachedToWindow)
            preference.request(true)
            assertEquals(InputDevice.SOURCE_CLASS_JOYSTICK,source(view))
            preference.request(false)
        } finally { c.pause().stop().destroy() }
    }
    @Test @Config(sdk=[28]) fun android9KeepsTheExistingInputPathWithoutCallingTheNewApi() {
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val preference=JoystickDispatchPreference(c.get().window.decorView)
            preference.request(true);preference.request(false)
            assertTrue(preference.diagnostics().contains("indisponível nesta API"))
        } finally { c.pause().stop().destroy() }
    }
    @Test fun finalMenuStateKeepsTheGameplayRequestHistoryUntilTheNextSession() {
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val root=c.get().window.decorView
            val preference=JoystickDispatchPreference(root)
            preference.beginSession()
            assertTrue(preference.diagnostics().contains("pedido durante a sessão: não"))
            preference.request(true)
            preference.request(false)
            assertEquals(InputDevice.SOURCE_CLASS_NONE,source(root))
            assertTrue(preference.diagnostics().contains("despacho padrão; pedido durante a sessão: sim"))
            preference.beginSession()
            assertTrue(preference.diagnostics().contains("pedido durante a sessão: não"))
        } finally { c.pause().stop().destroy() }
    }
}
