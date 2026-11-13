package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.*
import android.webkit.WebView
import android.widget.*
import com.xcloud.tclnative.auth.BrowserAuthorization
import com.xcloud.tclnative.i18n.*
import com.xcloud.tclnative.ui.dashboard.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MobileLoginUiTest {
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    @Test fun mobileOffersNormalMicrosoftLoginAndQrAndTvKeepsQrFirst() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        UiText.apply(a,AppLanguage.PORTUGUESE)
        try {
            for(mobile in listOf(true,false)) {
                var qr=0;var browser=0
                val view=ConsoleLoginView(a,{qr++},{},{},browserSignIn={browser++},preferBrowser=mobile)
                a.setContentView(view)
                view.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY));view.layout(0,0,1280,720)
                shadowOf(Looper.getMainLooper()).idle()
                val normal=all(view).filterIsInstance<TextView>().single { it.isClickable && it.text=="ENTRAR COM MICROSOFT" }
                val qrButton=all(view).filterIsInstance<TextView>().single { it.isClickable && it.text=="ENTRAR COM QR CODE" }
                assertTrue(normal.isShown);assertTrue(qrButton.isShown)
                val screenshot=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(screenshot))
                File("build/reports/themes/login-${if(mobile) "mobile" else "tv"}-methods.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG,100,it)
                };screenshot.recycle()
                view.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_A))
                assertEquals(if(mobile) 1 else 0,browser);assertEquals(if(mobile) 0 else 1,qr)
                normal.performClick();qrButton.performClick();assertEquals(if(mobile) 2 else 1,browser)
                view.showConnecting("Connecting");normal.performClick();qrButton.performClick()
                assertEquals(if(mobile) 2 else 1,browser)
                view.beginQr();assertFalse(all(view).filterIsInstance<TextView>().any { it.isShown && it.text=="ENTRAR COM MICROSOFT" })
                view.landing();assertTrue(all(view).filterIsInstance<TextView>().any { it.isShown && it.text=="ENTRAR COM MICROSOFT" })
                view.close()
            }
        } finally { c.pause().stop().destroy() }
    }
    @Test fun microsoftWindowLoadsOnlyTheOfficialAuthorizationAndReturnsOneValidatedCode() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();var codes=0;var cancels=0
        try {
            val request=BrowserAuthorization.create(System.currentTimeMillis())
            val dialog=MicrosoftLoginDialog(a,request,{assertEquals("approved",it);codes++},{cancels++},{fail(it)})
            dialog.show()
            val web=all(dialog.window!!.decorView).filterIsInstance<WebView>().single()
            assertEquals(request.url,shadowOf(web).lastLoadedUrl)
            assertFalse(web.settings.allowFileAccess);assertFalse(web.settings.allowContentAccess)
            val redirect=BrowserAuthorization.REDIRECT.toHttpUrl().newBuilder().addQueryParameter("code","approved")
                .addQueryParameter("state",request.url.toHttpUrl().queryParameter("state")).build().toString()
            val client=web.webViewClient
            assertTrue(client.shouldOverrideUrlLoading(web,"file:///sdcard/anything"))
            assertTrue(client.shouldOverrideUrlLoading(web,redirect));assertEquals(1,codes);assertEquals(0,cancels)
            assertTrue(client.shouldOverrideUrlLoading(web,redirect));assertEquals(1,codes);assertFalse(dialog.isShowing)
            val cancelled=MicrosoftLoginDialog(a,BrowserAuthorization.create(System.currentTimeMillis()),{fail("cancelled login")},{cancels++},{fail(it)})
            cancelled.show();cancelled.cancel();shadowOf(Looper.getMainLooper()).idle();assertEquals(1,cancels)
        } finally { c.pause().stop().destroy() }
    }
}
