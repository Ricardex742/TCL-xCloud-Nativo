package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.*
import android.widget.*
import com.xcloud.tclnative.i18n.*
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.ui.dashboard.*
import com.xcloud.tclnative.webrtc.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[31],qualifiers="w1280dp-h720dp-land-television-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class UsbControllerUiTest {
    private class Port: UsbControllerPort {
        val source=ControllerSource.Usb("demo",0x2dc8,0x100,0,"Demo XInput")
        var value=UsbControllerSnapshot();var activations=0;var tests=0;var captures=0;var exclusive=false
        override fun candidates()=listOf(UsbControllerDescriptor(source,0x81,2,32))
        override fun snapshot()=value
        override fun enable(candidate: UsbControllerDescriptor,exclusive: Boolean) {
            activations++;this.exclusive=exclusive;value=UsbControllerSnapshot(UsbControllerStatus.READY,source,30,29,1,2,
                GamepadState(buttons=16,leftX=.75f,rightTrigger=.5f))
        }
        override fun disable(done: () -> Unit) { value=UsbControllerSnapshot();done() }
        override fun test() { tests++ }
        override fun capture() { captures++ }
    }
    @Before fun reset() {
        val app=RuntimeEnvironment.getApplication();UiText.apply(app,AppLanguage.PORTUGUESE)
        DashboardTheme.apply(app,UiTheme.XBOX_SERIES);AndroidControllerMonitor.clear()
    }
    @After fun cleanup() { ShadowDialog.getLatestDialog()?.dismiss();DashboardTheme.apply(RuntimeEnvironment.getApplication(),UiTheme.GREEN) }
    private fun views(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun latest()=ShadowDialog.getLatestDialog() as ConsoleDialog
    private fun click(d: ConsoleDialog,text: String)=views(d.panel).filterIsInstance<TextView>().single { it.isClickable && it.text.toString().contains(text,true) }.performClick()
    private fun layout(window: Window) {
        val root=window.decorView;val width=window.attributes.width
        repeat(3) { root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(648,View.MeasureSpec.EXACTLY));root.layout(0,0,width,648);shadowOf(Looper.getMainLooper()).idle() }
        val attach=ReflectionHelpers.getField<Any>(root,"mAttachInfo")
        ReflectionHelpers.setField(attach,"mHasWindowFocus",true)
    }
    @Test fun rawUsbNavigatesTheActiveDialogAndHoldingADoesNotClickThroughWindows() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get();a.setContentView(FrameLayout(a))
        val port=Port();val navigation=UsbMenuNavigation(a)
        try {
            val panel=UsbControllerPanel(a,port).show {};layout(panel.window!!)
            assertSame(panel.window,AppControllerWindows.current(a))
            navigation.update(GamepadState(buttons=16))
            val chooser=latest();assertNotSame(panel,chooser);layout(chooser.window!!)
            shadowOf(Looper.getMainLooper()).idleFor(1000,TimeUnit.MILLISECONDS)
            assertEquals(0,port.activations)
            navigation.update(GamepadState());navigation.update(GamepadState(buttons=16))
            assertEquals(1,port.activations);assertFalse(port.exclusive);assertFalse(chooser.isShowing)
            shadowOf(Looper.getMainLooper()).idleFor(150,TimeUnit.MILLISECONDS)
            assertTrue(views(panel.panel).filterIsInstance<TextView>().any { it.text.contains("29 válidos") && it.text.contains("A [0010]") })
            val bitmap=Bitmap.createBitmap(panel.window!!.decorView.width,648,Bitmap.Config.ARGB_8888)
            panel.window!!.decorView.draw(Canvas(bitmap))
            File("build/reports/dashboard/usb-controller-panel.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            click(panel,"Testar vibração");assertEquals(1,port.tests)
            click(panel,"Desativar");assertEquals(UsbControllerStatus.OFF,port.value.status)
            panel.dismiss();navigation.clear();assertNull(AppControllerWindows.current(a)?.takeIf { it===panel.window })
        } finally { navigation.clear();AppControllerWindows.clear(a);controller.pause().stop().destroy() }
    }
    @Test fun exclusiveCaptureRequiresASeparateConfirmationAndDefaultSelectionDoesNotEnableIt() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get();a.setContentView(FrameLayout(a))
        val port=Port()
        try {
            val panel=UsbControllerPanel(a,port).show {};layout(panel.window!!)
            click(panel,"Captura exclusiva")
            val confirm=latest();assertEquals(0,port.activations)
            assertTrue(views(confirm.panel).filterIsInstance<TextView>().any { it.text.contains("controle remoto disponível") })
            click(confirm,"Escolher receptor USB");val chooser=latest();click(chooser,"Demo XInput")
            assertEquals(1,port.activations);assertTrue(port.exclusive)
            latest().dismiss()
        } finally { AppControllerWindows.clear(a);controller.pause().stop().destroy() }
    }
    @Test fun usbProfilesCanBeEditedWithoutCreatingAnAndroidInputDevice() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get();a.setContentView(FrameLayout(a))
        val port=Port();val store=ControllerProfileStore(a)
        try {
            ControllerProfilePanel(a,store,usbSources={ listOf(port.source) }).show {}
            click(latest(),"Demo XInput");click(latest(),"Sensibilidade direita:");click(latest(),"125%")
            assertEquals(125,store.read(port.source.profileKey).rightSensitivity)
            assertEquals(ControllerProfile(),store.read("an-unrelated-android-controller"))
            latest().dismiss()
        } finally { AppControllerWindows.clear(a);controller.pause().stop().destroy() }
    }
    @Test fun directUsbSearchTypesInsideTheAppWithoutAnExternalKeyboard() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get();a.setContentView(FrameLayout(a))
        DashboardTheme.apply(a,UiTheme.XBOX_360)
        val driver=UsbDirectController(a)
        val navigation=UsbMenuNavigation(a)
        ReflectionHelpers.setField(driver,"source",ControllerSource.Usb("demo",1,2,0,"Demo"))
        AppControllerWindows.usb(a,driver)
        try {
            var query: String?=null
            val search=ConsoleDialogs.search(a,"") { query=it };layout(search.window!!)
            val field=views(search.panel).filterIsInstance<EditText>().single()
            assertFalse(field.showSoftInputOnFocus)
            navigation.update(GamepadState(buttons=16));navigation.update(GamepadState())
            navigation.update(GamepadState(buttons=2048));navigation.update(GamepadState())
            assertEquals("B",(search.window!!.currentFocus as TextView).text.toString())
            navigation.update(GamepadState(buttons=16));navigation.update(GamepadState())
            assertEquals("AB",field.text.toString());field.setText("")
            fun key(value: String)=views(search.panel).filterIsInstance<TextView>().single { it.isClickable && it.text.toString()==value }.performClick()
            key("F");key("O");key("R");key("T");key("Apagar");key("T")
            assertEquals("FORT",field.text.toString())
            val bitmap=Bitmap.createBitmap(search.window!!.decorView.width,648,Bitmap.Config.ARGB_8888)
            search.window!!.decorView.draw(Canvas(bitmap))
            File("build/reports/dashboard/usb-controller-search.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            field.append("🚗");field.setSelection(field.text.length);key("Apagar")
            assertEquals("FORT",field.text.toString())
            click(search,"Buscar");assertEquals("FORT",query)
        } finally { navigation.clear();driver.close();AppControllerWindows.clear(a);controller.pause().stop().destroy() }
    }
    @Test fun gameRumbleUsesUsbWithoutAnAndroidIdAndDisablingRumbleStopsTheTransport() {
        val commands=mutableListOf<RumbleCommand>();var stops=0
        val rumble=ControllerRumble({ null }).apply {
            directRumble={ command -> commands+=command;true };stopDirect={ stops++ }
            directDiagnostics={ UsbControllerStatus.READY.label }
        }
        val command=RumbleCommand(0,80,20,0,0,250,0,0)
        rumble.play(command);shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(command),commands)
        assertTrue(rumble.diagnostics().contains(UsbControllerStatus.READY.label))
        rumble.enabled=false;assertEquals(1,stops)
        rumble.play(command);shadowOf(Looper.getMainLooper()).idle();assertEquals(1,commands.size)
    }
}
