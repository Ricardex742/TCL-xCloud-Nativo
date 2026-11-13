package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.*
import android.widget.TextView
import com.xcloud.tclnative.auth.OAuthToken
import com.xcloud.tclnative.auth.PreferencesTokenStore
import com.xcloud.tclnative.auth.XalAuthenticator
import com.xcloud.tclnative.i18n.*
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.ui.dashboard.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class RegionBypassUiTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        app.getSharedPreferences("network_access", 0).edit().clear().commit()
        UiText.apply(app, AppLanguage.PORTUGUESE); DashboardTheme.apply(app, UiTheme.GREEN)
    }
    @After fun cleanup() { (ShadowDialog.getLatestDialog() as? ConsoleDialog)?.dismiss() }
    private fun all(v: View): List<View> = listOf(v) + if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun layout(v: View, w: Int=1280, h: Int=720) {
        repeat(2) { v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); v.layout(0,0,w,h); shadowOf(Looper.getMainLooper()).idle() }
    }
    private fun capture(v: View, name: String) {
        val bitmap=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(bitmap))
        File("build/reports/dashboard/region-bypass-$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    private fun option(dialog: ConsoleDialog, text: String)=all(dialog.panel).filterIsInstance<TextView>().single { it.isClickable && it.text.toString().endsWith(text,true) }
    @Test fun preferenceSurvivesRestartRejectsUnknownValuesAndPreservesLoginAndPlayback() {
        val store=RegionBypassStore(app); assertEquals(RegionBypass.OFF,store.read())
        val tokens=PreferencesTokenStore(app); val before=OAuthToken("t=demo","demo-refresh",3600000,XalAuthenticator.XCLOUD_CLIENT_ID)
        tokens.write(before); val playback=PlaybackSettingsStore(app).read()
        store.save(RegionBypass.SOUTH_KOREA)
        assertEquals(RegionBypass.SOUTH_KOREA,RegionBypassStore(app).read())
        store.save(RegionBypass.OFF); assertEquals(RegionBypass.OFF,RegionBypassStore(app).read())
        app.getSharedPreferences("network_access",0).edit().putString("region_bypass","unknown-country").commit()
        assertEquals(RegionBypass.OFF,RegionBypassStore(app).read())
        assertEquals(before,tokens.read());assertEquals(playback,PlaybackSettingsStore(app).read())
    }
    @Test fun loginExposesTheCountryPickerBeforeQrAndAfterARejectedConnection() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get();var signIns=0
        val view=ConsoleLoginView(a,{signIns++},{},{})
        try {
            a.setContentView(view);layout(view);capture(view,"login")
            val bypass=all(view).filterIsInstance<TextView>().single { it.isClickable && it.text.contains("BYPASS") }
            assertTrue(bypass.isEnabled);bypass.performClick()
            val picker=ShadowDialog.getLatestDialog() as ConsoleDialog
            layout(picker.window!!.decorView,picker.window!!.attributes.width,648);capture(picker.window!!.decorView,"countries")
            repeat(5) {
                picker.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN))
                layout(picker.window!!.decorView,picker.window!!.attributes.width,648)
            }
            assertTrue((picker.window!!.currentFocus as TextView).text.toString().endsWith("Polônia",true))
            capture(picker.window!!.decorView,"last-country")
            picker.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_A))
            assertEquals(RegionBypass.POLAND,RegionBypassStore(a).read());assertEquals(0,signIns)
            assertTrue(bypass.text.contains("POLÔNIA"));assertFalse(view.isQr)
            view.showConnecting("Conectando");assertFalse(bypass.isEnabled)
            view.showError("AUTH_GSSV: HTTP 403");assertTrue(bypass.isEnabled)
            bypass.performClick();val retry=ShadowDialog.getLatestDialog() as ConsoleDialog
            option(retry,"Desativado").performClick()
            assertEquals(RegionBypass.OFF,RegionBypassStore(a).read());assertEquals(0,signIns)
            view.beginQr();layout(view);capture(view,"qr")
            assertTrue(bypass.isShown)
        } finally { view.close();controller.pause().stop().destroy() }
    }
    @Test fun networkSettingsOfferTheSameSavedCountryWithoutChangingPlayback() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get()
        try {
            val store=PlaybackSettingsStore(a);val before=store.read()
            val panel=PlaybackSettingsPanel(a,store,{},{},{},{})
            val network=panel.showNetwork()
            all(network.panel).filterIsInstance<TextView>().single { it.isClickable && it.text.contains("Bypass",true) }.performClick()
            val picker=ShadowDialog.getLatestDialog() as ConsoleDialog
            option(picker,"Estados Unidos").performClick()
            assertEquals(RegionBypass.UNITED_STATES,RegionBypassStore(a).read())
            assertEquals(before,store.read())
            val returned=ShadowDialog.getLatestDialog() as ConsoleDialog
            assertTrue(all(returned.panel).filterIsInstance<TextView>().any { it.text.contains("Estados Unidos",true) })
            returned.dismiss()
        } finally { controller.pause().stop().destroy() }
    }
}
