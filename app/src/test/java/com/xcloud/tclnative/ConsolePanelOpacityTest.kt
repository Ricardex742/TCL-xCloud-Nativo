package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import com.xcloud.tclnative.i18n.AppLanguage
import com.xcloud.tclnative.i18n.UiText
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ConsolePanelOpacityTest {
    private val themes=UiTheme.entries.filter { it.scene!=ConsoleScene.NONE }
    @Before fun reset() {
        val app=RuntimeEnvironment.getApplication();UiText.apply(app,AppLanguage.PORTUGUESE)
        app.getSharedPreferences("app_appearance",0).edit().clear().commit();DashboardTheme.apply(app,UiTheme.GREEN)
    }
    @After fun cleanup() { ShadowDialog.getLatestDialog()?.dismiss();reset() }
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun measure(v: View,w: Int,h: Int) {
        repeat(3) {
            v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.AT_MOST))
            v.layout(0,0,v.measuredWidth,v.measuredHeight);shadowOf(Looper.getMainLooper()).idle()
        }
    }
    private fun pixels(b: Bitmap)=IntArray(b.width*b.height).also { b.getPixels(it,0,b.width,0,0,b.width,b.height) }
    private fun snapshot(v: View,name: String): Int {
        val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b))
        val probe=b.getPixel(b.width-20,b.height/2)
        File("build/reports/themes/opacity-$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle()
        return probe
    }

    @Test fun panelBackgroundPreservesOpacityOnEveryRedrawAndAfterResizing() {
        for(theme in themes) {
            val background=ConsolePanelSkin(theme.scene).background()
            for((w,h) in listOf(640 to 420,730 to 480)) {
                background.setBounds(0,0,w,h)
                val first=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);background.draw(Canvas(first));val expected=pixels(first)
                assertTrue("$theme panel surface must cover the catalog",Color.alpha(first.getPixel(w/2,h/2))>=250);first.recycle()
                repeat(5) { redraw ->
                    val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);background.draw(Canvas(b));val actual=pixels(b);b.recycle()
                    assertArrayEquals("$theme background changed opacity on redraw $redraw at $w/$h",expected,actual)
                }
            }
        }
    }

    @Test fun longThemePickerKeepsItsBackgroundWhenFocusedAndScrolledToBothEnds() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get()
        a.setContentView(FrameLayout(a).apply { setBackgroundColor(Color.MAGENTA) });measure(a.window.decorView,1280,720)
        try {
            for(theme in themes) {
                AppearanceSettingsStore(a).save(theme);DashboardTheme.apply(a,theme)
                val settings=PlaybackSettingsPanel(a,PlaybackSettingsStore(a),{fail("Scrolling must not change playback")},{},{},{})
                val dialog=settings.chooseTheme();measure(dialog.panel,1100,648)
                val scroll=all(dialog.panel).filterIsInstance<ScrollView>().single()
                assertTrue("$theme picker must actually require scrolling",dialog.body.height>scroll.height)
                val baseline=snapshot(dialog.panel,"${theme.name.lowercase()}-initial")
                assertTrue("$theme initial background must be readable",Color.alpha(baseline)>=250)
                repeat(3) { pass ->
                    scroll.fullScroll(View.FOCUS_DOWN);shadowOf(Looper.getMainLooper()).idleFor(350,TimeUnit.MILLISECONDS)
                    assertTrue("$theme picker must reach the lower entries",scroll.scrollY>0)
                    assertEquals("$theme panel must not become transparent at the bottom",baseline,snapshot(dialog.panel,"${theme.name.lowercase()}-bottom-$pass"))
                    scroll.fullScroll(View.FOCUS_UP);shadowOf(Looper.getMainLooper()).idleFor(350,TimeUnit.MILLISECONDS)
                    assertEquals("$theme panel must remain stable at the top",baseline,snapshot(dialog.panel,"${theme.name.lowercase()}-top-$pass"))
                    assertEquals(theme,AppearanceSettingsStore(a).read())
                }
                dialog.dismiss()
            }
        } finally { controller.pause().stop().destroy() }
    }
}
