package com.xcloud.tclnative

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.xcloud.tclnative.catalog.GameLibrary
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.ui.dashboard.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ConsoleThemeTest {
    @Before fun defaults() {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("app_appearance",0).edit().clear().commit()
        DashboardTheme.apply(app,UiTheme.GREEN)
    }
    @After fun reset() { ShadowDialog.getLatestDialog()?.dismiss();defaults() }
    private fun advance(ms: Long)=shadowOf(Looper.getMainLooper()).idleFor(ms,TimeUnit.MILLISECONDS)
    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY))
        view.layout(0,0,1280,720)
    }
    private fun all(view: View): List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()

    @Test fun automaticIsConservativeAndExplicitMotionStillHonorsPowerAndAccessibility() {
        fun interval(mode: BackgroundMotion=BackgroundMotion.AUTO,scene: ConsoleScene=ConsoleScene.XBOX_360,
                     low: Boolean=false,large: Boolean=false,power: Boolean=false,animations: Boolean=true,shown: Boolean=true)=
            BackgroundMotionPolicy.interval(mode,scene,low,large,power,animations,shown)
        assertEquals(84L,interval())
        assertNull(interval(low=true));assertNull(interval(large=true))
        assertEquals(167L,interval(BackgroundMotion.ON,low=true))
        for(mode in BackgroundMotion.entries) {
            assertNull(interval(mode,power=true));assertNull(interval(mode,animations=false))
            assertNull(interval(mode,shown=false));assertNull(interval(mode,ConsoleScene.NONE))
        }
        assertNull(interval(BackgroundMotion.OFF))
    }

    @Test fun motionChoicePersistsSeparatelyAndInvalidPreferencesFallBackToAutomatic() {
        val app=RuntimeEnvironment.getApplication();val store=AppearanceSettingsStore(app)
        val playback=PlaybackSettingsStore(app).read()
        assertEquals(BackgroundMotion.AUTO,store.motion())
        store.save(UiTheme.XBOX_ORIGINAL)
        for(mode in BackgroundMotion.entries) {
            store.saveMotion(mode);assertEquals(mode,AppearanceSettingsStore(app).motion())
            assertEquals(UiTheme.XBOX_ORIGINAL,store.read());assertEquals(playback,PlaybackSettingsStore(app).read())
        }
        app.getSharedPreferences("app_appearance",0).edit().putString("background_motion","invalid").commit()
        assertEquals(BackgroundMotion.AUTO,store.motion())
    }

    @Test fun allFourScenesReallyMoveWithoutChangingTheBasePaletteOrAllocatingImageAssets() {
        val app=RuntimeEnvironment.getApplication()
        val signatures=mutableSetOf<Int>()
        for(theme in UiTheme.entries.filter { it.scene!=ConsoleScene.NONE }) {
            DashboardTheme.apply(app,theme)
            val painter=ConsoleScenePainter().apply { configure(DashboardTheme.palette,1280,720) }
            fun capture(phase: Float): IntArray {
                val bitmap=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888)
                val canvas=Canvas(bitmap)
                DashboardBackground(false).apply { setBounds(0,0,1280,720) }.draw(canvas)
                painter.draw(canvas,phase)
                File("build/reports/themes/background-${theme.name.lowercase()}-$phase.png").apply { parentFile.mkdirs() }
                    .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                val pixels=IntArray(1280*720);bitmap.getPixels(pixels,0,1280,0,0,1280,720);bitmap.recycle()
                return pixels
            }
            val still=capture(0f);val moved=capture(.25f)
            assertFalse("$theme artwork did not move",still.contentEquals(moved))
            assertArrayEquals("$theme cycle must be deterministic",still,capture(0f))
            signatures.add(still.contentHashCode())
        }
        assertEquals(4,signatures.size)
    }

    @Test @Config(sdk=[24,28,34]) @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun liveBackdropStopsWhenHiddenUnfocusedOffDetachedAndClosed() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get()
        val store=AppearanceSettingsStore(a);store.saveMotion(BackgroundMotion.ON)
        DashboardTheme.apply(a,UiTheme.XBOX_ONE)
        val parent=FrameLayout(a);val view=ConsoleThemeBackdrop(a)
        parent.addView(view,FrameLayout.LayoutParams(-1,-1))
        fun windowVisibility(value: Int) {
            // Legacy Robolectric has no compositor and leaves the visible Activity's window GONE.
            // Supply the actual window state before delivering Android's visibility callback.
            val info=ReflectionHelpers.getField<Any>(view,"mAttachInfo")
            ReflectionHelpers.setField(info,"mWindowVisibility",value)
            view.dispatchWindowVisibilityChanged(value)
        }
        try {
            a.setContentView(parent);layout(parent);advance(20)
            controller.windowFocusChanged(true);windowVisibility(View.VISIBLE);view.onVisibilityAggregated(true)
            advance(1000)
            assertTrue("ticks=${view.renderedTicks}, pending=${view.framePending}, attached=${view.isAttachedToWindow}, shown=${view.isShown}, window=${view.windowVisibility}, focus=${view.hasWindowFocus()}",view.renderedTicks in 5..12)
            assertTrue(view.framePending)
            fun stopped(action: () -> Unit) {
                action();val before=view.renderedTicks;advance(500)
                assertEquals(before,view.renderedTicks);assertFalse(view.framePending)
            }
            stopped { parent.visibility=View.GONE }
            parent.visibility=View.VISIBLE;view.onVisibilityAggregated(true);assertTrue(view.framePending)
            stopped { view.onWindowFocusChanged(false) }
            view.onWindowFocusChanged(true);assertTrue(view.framePending)
            stopped { windowVisibility(View.GONE) }
            windowVisibility(View.VISIBLE);assertTrue(view.framePending)
            stopped { store.saveMotion(BackgroundMotion.OFF) }
            store.saveMotion(BackgroundMotion.ON);advance(100);assertTrue(view.framePending)
            stopped { parent.removeView(view) }
            parent.addView(view);layout(parent);view.onWindowFocusChanged(true);view.onVisibilityAggregated(true)
            assertTrue(view.framePending)
            stopped { view.close() }
            view.onWindowFocusChanged(true);assertFalse(view.framePending)
        } finally { view.close();controller.pause().stop().destroy() }
    }

    @Test fun powerSavingAndLowMemoryAutomaticHaveNoBackgroundTimer() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get()
        val power=shadowOf(a.getSystemService(PowerManager::class.java))
        val memory=shadowOf(a.getSystemService(ActivityManager::class.java))
        val store=AppearanceSettingsStore(a)
        DashboardTheme.apply(a,UiTheme.XBOX_360)
        val view=ConsoleThemeBackdrop(a)
        try {
            memory.setIsLowRamDevice(true)
            a.setContentView(view);layout(view);view.onWindowFocusChanged(true);view.onVisibilityAggregated(true)
            advance(500);assertFalse(view.framePending);assertEquals(0L,view.renderedTicks)
            store.saveMotion(BackgroundMotion.ON);advance(1000);assertTrue(view.renderedTicks in 3..6)
            power.setIsPowerSaveMode(true);a.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));advance(10)
            val before=view.renderedTicks;advance(500);assertFalse(view.framePending);assertEquals(before,view.renderedTicks)
            power.setIsPowerSaveMode(false);a.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));advance(10)
            assertTrue(view.framePending)
        } finally { view.close();memory.setIsLowRamDevice(false);power.setIsPowerSaveMode(false);controller.pause().stop().destroy() }
    }

    @Test fun gameplayPausesBackdropAndReturningRestartsItWithoutChangingTheTheme() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get()
        AppearanceSettingsStore(a).saveMotion(BackgroundMotion.ON);DashboardTheme.apply(a,UiTheme.XBOX_SERIES)
        val catalog=GameDashboardView(a,GameLibrary(a,"motion-demo"),{},{},{},{})
        try {
            a.setContentView(catalog);layout(catalog)
            val backdrop=all(catalog).filterIsInstance<ConsoleThemeBackdrop>().single()
            backdrop.onWindowFocusChanged(true);backdrop.onVisibilityAggregated(true)
            advance(300);assertTrue(backdrop.framePending)
            catalog.pauseArtwork();val before=backdrop.renderedTicks;advance(600)
            assertFalse(backdrop.framePending);assertEquals(before,backdrop.renderedTicks)
            catalog.resumeArtwork();assertTrue(backdrop.framePending)
            DashboardTheme.apply(a,UiTheme.GREEN,catalog);assertFalse(backdrop.framePending)
        } finally { catalog.close();controller.pause().stop().destroy() }
    }

    @Test fun motionMenuExistsOnlyForConsoleThemesAndDoesNotChangeStreamSettings() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get()
        val store=AppearanceSettingsStore(a);val playback=PlaybackSettingsStore(a);val before=playback.read()
        val panel=PlaybackSettingsPanel(a,playback,{fail("Appearance must not restart stream")},{},{},{})
        fun rows()=all((ShadowDialog.getLatestDialog() as ConsoleDialog).body).filterIsInstance<TextView>().filter { it.isClickable }
        try {
            panel.showAppearance();assertFalse(rows().any { it.text.contains("Fundo dinâmico") })
            store.save(UiTheme.XBOX_ORIGINAL);DashboardTheme.apply(a,store.read());panel.showAppearance()
            rows().single { it.text.contains("Fundo dinâmico") }.performClick()
            rows().single { it.text.contains("Desativado") }.performClick()
            assertEquals(BackgroundMotion.OFF,store.motion());assertEquals(before,playback.read())
            assertTrue(rows().any { it.text.toString()=="Fundo dinâmico: Desativado" })
        } finally { controller.pause().stop().destroy() }
    }
}
