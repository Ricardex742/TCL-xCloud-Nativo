package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.xcloud.tclnative.auth.CloudRegion
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.ui.dashboard.*
import com.xcloud.tclnative.webrtc.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionControlsUiTest {
    private fun all(view: View): List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun choose(dialog: ConsoleDialog,text: String) { all(dialog.body).filterIsInstance<TextView>().single { it.isClickable && it.text.contains(text) }.performClick() }
    private fun prepare(activity: Activity) {
        val root=activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY))
        root.layout(0,0,1280,720);shadowOf(Looper.getMainLooper()).idle()
    }
    private fun capture(dialog: ConsoleDialog,name: String) {
        dialog.panel.measure(View.MeasureSpec.makeMeasureSpec(1100,View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.AT_MOST))
        dialog.panel.layout(0,0,dialog.panel.measuredWidth,dialog.panel.measuredHeight)
        val bitmap=Bitmap.createBitmap(dialog.panel.width,dialog.panel.height,Bitmap.Config.ARGB_8888)
        dialog.panel.draw(Canvas(bitmap))
        File("build/reports/dashboard/$name.png").apply { parentFile.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    @Test fun gameOptionsSaveWithoutChangingDefaultsAndReopenWithTheNewValues() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get().apply { setContentView(FrameLayout(this)) }
        prepare(activity)
        val store=SessionOptionsStore(activity,"ui-account")
        val panel=SessionOptionsPanel(activity,store,listOf(CloudRegion("BrazilSouth","https://region.example")),"game","Teste") {}
        val menu=panel.show();capture(menu,"session-options-0.31.6")
        choose(menu,"Bitrate máximo")
        choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"15.0 Mbps")
        assertEquals(15000,store.read("game").maxBitrateKbps)
        assertEquals(0,store.read().maxBitrateKbps)
        assertTrue(all((ShadowDialog.getLatestDialog() as ConsoleDialog).body).filterIsInstance<TextView>().any { it.text.contains("15.0 Mbps") })
        (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
        choose(panel.region(),"BrazilSouth")
        assertEquals("BrazilSouth",store.read("game").region)
        (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
        choose(panel.locale(),"English (US)")
        assertEquals("en-US",store.read("game").locale)
        (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss();controller.pause().stop().destroy()
    }
    @Test fun audioAndImageControlsPersistWithoutEnablingMicrophoneCapture() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get().apply { setContentView(FrameLayout(this)) }
        prepare(activity)
        val store=PlaybackSettingsStore(activity);store.save(PlaybackSettings())
        var changes=0
        val panel=PlaybackSettingsPanel(activity,store,{changes++},{},{},{})
        val audio=panel.showAudio();capture(audio,"audio-options-0.31.6")
        choose(audio,"Ganho de áudio");choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"175%")
        assertEquals(175,store.read().audioGain);assertFalse(store.read().microphone)
        choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"Microfone nas próximas")
        choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"Disponível — iniciar mudo")
        assertTrue(store.read().microphone)
        assertNull(shadowOf(activity).lastRequestedPermission)
        (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
        choose(panel.showImageAdjustments(),"Brilho:");all((ShadowDialog.getLatestDialog() as ConsoleDialog).body).filterIsInstance<TextView>().single { it.isClickable && it.text.toString().replace("○", "").replace("✓", "").trim()=="25%" }.performClick()
        assertEquals(25,store.read().brightness)
        assertTrue(changes>=3)
        (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss();controller.pause().stop().destroy()
    }
    @Test fun gyroStopsOnNativeTouchAndReleasesTheCameraIfSensorEventsStop() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        val manager=activity.getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager
        val sensor=ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE);shadowOf(manager).addSensor(sensor)
        var changes=0;val gyro=GyroController(activity,{0},{changes++})
        gyro.start(GyroMode.ALWAYS,100,true)
        assertFalse(shadowOf(manager).hasListener(gyro));assertTrue(gyro.status.contains("toque nativo"))
        gyro.start(GyroMode.ALWAYS,100,false);assertTrue(shadowOf(manager).hasListener(gyro))
        val event=ShadowSensorManager.createSensorEvent(3);event.sensor=sensor;event.values[0]=1f;event.values[1]=2f
        gyro.onSensorChanged(event)
        assertTrue(gyro.apply(GamepadState()).rightX>0)
        shadowOf(Looper.getMainLooper()).idleFor(101,TimeUnit.MILLISECONDS)
        assertEquals(GamepadState(),gyro.apply(GamepadState()));assertEquals(2,changes)
        gyro.stop();assertFalse(shadowOf(manager).hasListener(gyro))
        controller.pause().stop().destroy()
    }
    @Test fun categoriesAndBackKeepTheUserInTheRightSectionWithoutChangingPreferences() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get().apply { setContentView(FrameLayout(this)) };prepare(activity)
        val store=PlaybackSettingsStore(activity);store.save(PlaybackSettings())
        val pages=mutableListOf<SettingsPage>();var changes=0
        val panel=PlaybackSettingsPanel(activity,store,{changes++},{},{},{},onPageEntered={pages.add(it)})
        val root=panel.show();capture(root,"categorized-settings-0.31.7")
        val rows=all(root.body).filterIsInstance<TextView>().filter { it.isClickable }.map { it.text.toString() }
        assertEquals(listOf("Vídeo e transmissão","Imagem e tela","Aparência e temas","Rede e sessão","Controles e toque","Áudio e microfone","Diagnósticos","Biblioteca e conta"),rows)
        choose(root,"Rede e sessão");choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"IPv4 / IPv6")
        assertEquals(0,changes);assertEquals(PlaybackSettings(),store.read())
        choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"Preferir IPv4")
        assertEquals(IpPreference.IPV4,store.read().ipPreference);assertEquals(SettingsPage.NETWORK,pages.last())
        (ShadowDialog.getLatestDialog() as ConsoleDialog).cancel();shadowOf(Looper.getMainLooper()).idle();assertEquals(SettingsPage.ROOT,pages.last())
        choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"Controles e toque")
        choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"Giroscópio")
        capture(ShadowDialog.getLatestDialog() as ConsoleDialog,"native-gyro-options-0.31.7")
        assertEquals(SettingsPage.GYRO,pages.last())
        (ShadowDialog.getLatestDialog() as ConsoleDialog).cancel();shadowOf(Looper.getMainLooper()).idle();assertEquals(SettingsPage.CONTROLS,pages.last())
        (ShadowDialog.getLatestDialog() as ConsoleDialog).cancel();shadowOf(Looper.getMainLooper()).idle();assertEquals(SettingsPage.ROOT,pages.last())
        (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss();controller.pause().stop().destroy()
    }
    @Test fun nativeGyroIsExplicitOptInAndRequiresASensorWithoutRequestingPermissions() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get().apply { setContentView(FrameLayout(this)) };prepare(activity)
        val store=PlaybackSettingsStore(activity);store.save(PlaybackSettings())
        val panel=PlaybackSettingsPanel(activity,store,{},{},{},{})
        val manager=activity.getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager
        val sensor=ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE);shadowOf(manager).addSensor(sensor)
        choose(panel.showGyro(),"Toque nativo Xbox")
        assertFalse(store.read().gyroNativeTouch);assertEquals(GyroMode.OFF,store.read().gyroMode)
        choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"Ativado — experimental")
        assertTrue(store.read().gyroNativeTouch);assertEquals(GyroMode.ALWAYS,store.read().gyroMode)
        assertTrue(PlaybackSettingsStore(activity).read().gyroNativeTouch)
        assertNull(shadowOf(activity).lastRequestedPermission)
        (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
        var changes=0;val gyro=GyroController(activity,{0},{changes++})
        gyro.start(GyroMode.ALWAYS,100,true,true);assertTrue(shadowOf(manager).hasListener(gyro))
        val event=ShadowSensorManager.createSensorEvent(3);event.sensor=sensor;event.values[1]=2f
        gyro.onSensorChanged(event);assertEquals(.5f,gyro.apply(GamepadState()).rightX,0f)
        shadowOf(Looper.getMainLooper()).idleFor(101,TimeUnit.MILLISECONDS)
        assertEquals(GamepadState(),gyro.apply(GamepadState()));assertEquals(2,changes)
        gyro.start(GyroMode.LT,100,true,true);gyro.onSensorChanged(event)
        assertEquals(GamepadState(),gyro.apply(GamepadState()))
        assertEquals(.5f,gyro.apply(GamepadState(leftTrigger=1f)).rightX,0f)
        gyro.stop();assertFalse(shadowOf(manager).hasListener(gyro));assertEquals(GamepadState(),gyro.apply(GamepadState()))
        controller.pause().stop().destroy()
    }
}
