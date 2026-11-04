package com.xcloud.tclnative

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import com.xcloud.tclnative.renderer.HdrStatus
import com.xcloud.tclnative.ui.OfficialCloudActivity
import com.xcloud.tclnative.ui.GameTouchView
import com.xcloud.tclnative.ui.dashboard.PlaybackSettingsPanel
import com.xcloud.tclnative.settings.PlaybackSettingsStore
import com.xcloud.tclnative.web.OfficialCloudPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class OfficialCloudTest {
    @Test fun officialLinksUseValidatedProductIdsAndNavigationStaysWithinTheLoginFlow() {
        assertEquals("https://www.xbox.com/pt-BR/play/launch/fortnite/BT5P2X999VH2",OfficialCloudPolicy.launchUrl("Fortnite","bt5p2x999vh2"))
        assertNull(OfficialCloudPolicy.launchUrl("Fortnite","../other?token=x"))
        assertNull(OfficialCloudPolicy.launchUrl("Fortnite",null))
        for(url in listOf("https://www.xbox.com/pt-BR/play/launch/fortnite/BT5P2X999VH2",
            "https://login.live.com/oauth20_authorize.srf","https://account.xbox.com/")) assertTrue(url,OfficialCloudPolicy.allowed(url))
        for(url in listOf("javascript:alert(1)","file:///sdcard/login.html","http://www.xbox.com/play",
            "https://www.xbox.com.evil.test/play","https://www.xbox.com@evil.test/","https://www.xbox.com:8443/play"))
            assertFalse(url,OfficialCloudPolicy.allowed(url))
        val ua="Mozilla/5.0 (Linux; Android 14; Device; wv) AppleWebKit/537.36 Version/4.0 Chrome/130.0.1.2 Mobile Safari/537.36"
        val mobile=OfficialCloudPolicy.mobileUserAgent(ua)
        assertTrue(mobile.contains("Android 14")); assertTrue(mobile.contains("Chrome/130.0.1.2 Mobile"))
        assertFalse(mobile.contains("; wv")); assertFalse(mobile.contains("Version/4.0"))
    }
    private fun all(view: View): List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    @Test fun officialActivityHasItsOwnProcessAndNoFullscreenTouchOverlayOrNativeCredentialInjection() {
        val intent=Intent().putExtra("game_name","Fortnite").putExtra("product_id","BT5P2X999VH2")
        val controller=Robolectric.buildActivity(OfficialCloudActivity::class.java,intent).setup()
        val activity=controller.get()
        val views=all(activity.window.decorView)
        val web=views.filterIsInstance<WebView>().single()
        assertEquals("https://www.xbox.com/pt-BR/play/launch/fortnite/BT5P2X999VH2",shadowOf(web).lastLoadedUrl)
        assertTrue(web.settings.javaScriptEnabled); assertTrue(web.settings.domStorageEnabled)
        assertFalse(web.settings.allowFileAccess); assertFalse(web.settings.allowContentAccess)
        assertFalse(web.settings.offscreenPreRaster)
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW,web.settings.mixedContentMode)
        assertEquals(View.LAYER_TYPE_NONE,web.layerType)
        assertFalse(views.any { it is GameTouchView })
        assertTrue(shadowOf(web).lastAdditionalHttpHeaders.isNullOrEmpty())
        val info=activity.packageManager.getActivityInfo(ComponentName(activity,OfficialCloudActivity::class.java),0)
        assertFalse(info.exported); assertTrue(info.processName.endsWith(":official_web"))
        activity.onKeyDown(KeyEvent.KEYCODE_BACK,KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK))
        assertTrue(org.robolectric.shadows.ShadowDialog.getLatestDialog().isShowing)
        controller.pause().stop().destroy()
    }
    @Test fun hdr10DisplaySupportDoesNotForceHdrForTheEightBitSdrStream() {
        assertTrue(HdrStatus.displayLabel(intArrayOf(Display.HdrCapabilities.HDR_TYPE_HDR10)).contains("HDR10 anunciado"))
        assertTrue(HdrStatus.displayLabel(intArrayOf(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)).contains("não anunciado"))
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get(); val before=activity.window.colorMode
        val panel=PlaybackSettingsPanel(activity,PlaybackSettingsStore(activity),{},{},{},{})
        val dialog=panel.showHdrStatus()
        val text=all(dialog.body).filterIsInstance<android.widget.TextView>().joinToString { it.text }
        assertTrue(text.contains("H.264 de 8 bits")); assertTrue(text.contains("SDR")); assertTrue(text.contains("BT.2020"))
        assertEquals(before,activity.window.colorMode)
        dialog.dismiss(); controller.pause().stop().destroy()
    }
}
