package com.xcloud.tclnative

import android.app.Activity
import android.graphics.*
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.graphics.ColorUtils
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
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ConsoleIdentityUiTest {
    private val themes=UiTheme.entries.filter { it.scene!=ConsoleScene.NONE }
    @Before fun defaults() {
        val app=RuntimeEnvironment.getApplication();UiText.apply(app,AppLanguage.PORTUGUESE)
        app.getSharedPreferences("app_appearance",0).edit().clear().commit();DashboardTheme.apply(app,UiTheme.GREEN)
    }
    @After fun cleanup() { ShadowDialog.getLatestDialog()?.dismiss();defaults() }
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun layout(v: View,w: Int,h: Int) {
        repeat(3) { v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.AT_MOST));v.layout(0,0,v.measuredWidth,v.measuredHeight);shadowOf(Looper.getMainLooper()).idle() }
    }
    private fun capture(v: View,name: String): Int {
        val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b))
        File("build/reports/themes/identity-$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) }
        val pixels=IntArray(b.width*b.height);b.getPixels(pixels,0,b.width,0,0,b.width,b.height);b.recycle();return pixels.contentHashCode()
    }
    private fun render(d: ConsoleDialog,name: String): Int {
        layout(d.panel,1100,648);assertTrue(d.panel.height<=648)
        all(d.panel).filter { it.isShown && it.isClickable }.forEach { view ->
            assertTrue("Visible interactive control must have area: ${view.contentDescription}",view.width>0 && view.height>0)
        }
        return capture(d.panel,name)
    }
    @Test fun eachConsoleUsesItsOwnSettingsLayoutAndKeepsCategoriesAndBackReachable() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get();a.setContentView(FrameLayout(a));layout(a.window.decorView,1280,720)
        val signatures=mutableSetOf<Int>();val store=PlaybackSettingsStore(a);val before=store.read()
        try {
            for(theme in themes) {
                DashboardTheme.apply(a,theme)
                val settings=PlaybackSettingsPanel(a,store,{fail("Appearance must not change playback")},{},{},{})
                val d=settings.show();val items=all(d.body).filterIsInstance<TextView>().filter { it.isClickable }
                assertEquals(8,items.size);assertTrue(all(d.panel).any { it is ConsoleBrandView && it.isShown })
                signatures+=render(d,"settings-${theme.name.lowercase()}")
                if(theme.scene in listOf(ConsoleScene.XBOX_ONE,ConsoleScene.XBOX_SERIES,ConsoleScene.XBOX_360_METRO)) {
                    assertSame(items[0].parent,items[1].parent);assertNotSame(items[1].parent,items[2].parent)
                } else assertSame(d.body,items[0].parent)
                items[0].requestFocus();render(d,"settings-focus-${theme.name.lowercase()}")
                val row=items[0];val b=Bitmap.createBitmap(row.width,row.height,Bitmap.Config.ARGB_8888)
                row.background.draw(Canvas(b));val bg=b.getPixel(row.width/2,row.height/2);b.recycle()
                assertTrue("$theme focused text must remain readable",ColorUtils.calculateContrast(row.currentTextColor,bg)>=3.0)
                items.first { it.text.toString()=="Aparência e temas" }.performClick()
                val appearance=ShadowDialog.getLatestDialog() as ConsoleDialog
                assertTrue(all(appearance.body).filterIsInstance<TextView>().any { it.text.contains("Tema do aplicativo") })
                appearance.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_B));d.dismiss()
                assertEquals(before,store.read())
            }
            assertEquals(6,signatures.size)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun themedSearchSubmitsClearsAndCancelsWithoutChangingTheQueryOnBack() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get();a.setContentView(FrameLayout(a));layout(a.window.decorView,1280,720)
        val signatures=mutableSetOf<Int>()
        try {
            for(theme in themes) {
                DashboardTheme.apply(a,theme);val submitted=mutableListOf<String>()
                val d=ConsoleDialogs.search(a,"Forza") { submitted+=it }
                val field=all(d.panel).filterIsInstance<EditText>().single();assertEquals("Forza",field.text.toString())
                signatures+=render(d,"search-${theme.name.lowercase()}")
                field.setText("Hades");field.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
                assertFalse(d.isShowing);assertEquals(listOf("Hades"),submitted)
                val clear=ConsoleDialogs.search(a,"Saved") { submitted+=it }
                all(clear.panel).filterIsInstance<TextView>().single { it.text.toString()=="LIMPAR BUSCA" }.performClick()
                assertEquals(listOf("Hades",""),submitted)
                val back=ConsoleDialogs.search(a,"Unchanged") { submitted+=it }
                back.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_B));assertFalse(back.isShowing)
                assertEquals(2,submitted.size)
            }
            assertEquals(6,signatures.size)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun catalogDetailsRemainAccessibleInAccountSettingsWithoutChangingPlayback() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get();a.setContentView(FrameLayout(a));layout(a.window.decorView,1280,720)
        try {
            DashboardTheme.apply(a,UiTheme.XBOX_SERIES);var refresh=0
            val s=PlaybackSettingsPanel(a,PlaybackSettingsStore(a),{fail("No playback mutation")},{},{refresh++},{},catalogInformation={ "Catálogo de demonstração: capas parciais." })
            val account=s.showAccount();all(account.body).filterIsInstance<TextView>().single { it.text.toString()=="Informações do catálogo" }.performClick()
            val info=ShadowDialog.getLatestDialog() as ConsoleDialog
            assertTrue(all(info.body).filterIsInstance<TextView>().any { it.text.toString()=="Catálogo de demonstração: capas parciais." })
            render(info,"catalog-information")
            info.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_B))
            shadowOf(Looper.getMainLooper()).idle()
            val returned=ShadowDialog.getLatestDialog() as ConsoleDialog
            all(returned.body).filterIsInstance<TextView>().single { it.text.toString()=="Atualizar catálogo" }.performClick();assertEquals(1,refresh)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun badgesHaveCompactThemeSpecificSurfacesWithTransparentSpaceAroundThem() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get();val signatures=mutableSetOf<Int>()
        try {
            for(theme in themes) {
                DashboardTheme.apply(a,theme);val badges=GameBadgesView(a);a.setContentView(badges);badges.bind(true,true,1f);layout(badges,202,52)
                val pass=badges.getChildAt(0);val series=badges.getChildAt(1)
                assertTrue(pass.right<series.left && series.right<=202);assertTrue(pass.height<=26 && series.height<=26)
                signatures+=capture(badges,"badges-${theme.name.lowercase()}")
                val b=Bitmap.createBitmap(202,52,Bitmap.Config.ARGB_8888);badges.draw(Canvas(b));assertEquals(0,Color.alpha(b.getPixel(201,51)));b.recycle()
            };assertEquals(6,signatures.size)
        } finally { controller.pause().stop().destroy() }
    }
}
