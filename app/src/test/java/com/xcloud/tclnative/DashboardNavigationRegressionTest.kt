package com.xcloud.tclnative

import android.app.Activity
import android.graphics.*
import android.os.Looper
import android.view.*
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.xcloud.tclnative.catalog.*
import com.xcloud.tclnative.i18n.*
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.ui.dashboard.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DashboardNavigationRegressionTest {
    @Before fun reset() {
        val app=RuntimeEnvironment.getApplication();UiText.apply(app,AppLanguage.PORTUGUESE)
        app.getSharedPreferences("app_appearance",0).edit().clear().commit()
        AppearanceSettingsStore(app).saveMotion(BackgroundMotion.OFF);DashboardTheme.apply(app,UiTheme.GREEN)
    }
    @After fun cleanup() { reset() }
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun layout(v: GameDashboardView,w: Int=1280,h: Int=720) {
        v.awaitCatalog()
        repeat(5) {
            v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
            v.layout(0,0,w,h);shadowOf(Looper.getMainLooper()).idle()
        }
    }
    private fun games()=(0 until 32).map { GameTitle("NAV-$it","Game %02d — Long title for the dashboard".format(it),"PRODUCT-$it",seriesOptimized=true) }
    private fun grid(v: View)=all(v).filterIsInstance<RecyclerView>().single()
    private fun selected(v: GameDashboardView,index: Int) {
        assertEquals("NAV-$index",v.selectedGame?.titleId)
        val card=grid(v).findViewHolderForAdapterPosition(index)!!.itemView
        assertSame("Focus must remain on the game",card,v.findFocus())
        assertTrue("Selected game clipped on the left",card.left>=grid(v).paddingLeft)
        assertTrue("Selected game clipped on the right",card.right<=grid(v).width-grid(v).paddingRight)
    }
    private fun capture(v: View,name: String) {
        val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b))
        File("build/reports/themes/regression-$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle()
    }
    @Test fun horizontalDpadReachesEveryGameInBothDirectionsIncludingKeyRepeatsAndSpatialSearch() {
        for(theme in listOf(UiTheme.XBOX_360_NXE,UiTheme.XBOX_SERIES)) {
            DashboardTheme.apply(RuntimeEnvironment.getApplication(),theme)
            val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();val played=mutableListOf<String>()
            val v=GameDashboardView(a,GameLibrary(a,"navigation-${theme.name}"),{played+=it.titleId},{},{},{})
            try {
                a.setContentView(v);v.setGames(games());v.selectTab(LibraryTab.ALL);layout(v);v.restoreFocus();layout(v);selected(v,0)
                for(i in 1..31) {
                    // Alternate the Activity's direct route and Android's view key dispatch.
                    val event=KeyEvent(0,0,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT,i%4)
                    assertTrue(if(i%2==0) v.handleKey(event) else v.dispatchKeyEvent(event))
                    layout(v);selected(v,i)
                }
                repeat(3) { v.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));layout(v);selected(v,31) }
                v.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_A));assertEquals(listOf("NAV-31"),played)
                for(i in 30 downTo 0) { v.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_LEFT));layout(v);selected(v,i) }
                // Joystick/Android focus searches must use the same ordered path.
                grid(v).focusSearch(v.findFocus(),View.FOCUS_RIGHT);layout(v);selected(v,1)
                // Several events can arrive before a frame is laid out.
                repeat(8) { v.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT)) }
                layout(v);selected(v,9)
                capture(v,"carousel-${theme.name.lowercase()}")
                val up=v.findFocus()!!.nextFocusUpId
                assertFalse(v.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_UP)))
                assertTrue(v.findViewById<View>(up).requestFocus());layout(v)
                assertFalse("Explicit vertical exit stays outside the carousel",grid(v).hasFocus())
                v.restoreFocus();layout(v)
                repeat(15) { v.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT)) }
                v.back();layout(v)
                assertFalse("Back must cancel pending off-screen movement",grid(v).hasFocus())
            } finally { v.close();c.pause().stop().destroy() }
        }
    }
    @Test fun recycledCaptionsLosePreviousThemeGradientsAndSeriesOverlayStaysInsideItsCover() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        val library=GameLibrary(a,"caption-regression");library.toggleFavorite("NAV-0")
        val v=GameDashboardView(a,library,{},{},{},{})
        try {
            a.setContentView(v);v.setGames(games());v.selectTab(LibraryTab.ALL);layout(v);v.restoreFocus();layout(v)
            val themes=listOf(UiTheme.XBOX_SERIES,UiTheme.XBOX_360,UiTheme.XBOX_360_METRO,UiTheme.XBOX_360,UiTheme.XBOX_360_NXE,UiTheme.XBOX_360,UiTheme.XBOX_SERIES,UiTheme.XBOX_ORIGINAL,UiTheme.XBOX_ONE,UiTheme.GREEN,UiTheme.XBOX_360)
            for(theme in themes) {
                DashboardTheme.apply(a,theme,a.findViewById(android.R.id.content));layout(v)
                val g=grid(v)
                repeat(g.childCount) { i ->
                    val card=g.getChildAt(i)
                    val caption=all(card).filterIsInstance<TextView>().singleOrNull { it.text.startsWith("Game ") } ?: return@repeat
                    val cover=all(card).filterIsInstance<FixedArtworkView>().single().parent as ViewGroup
                    when(theme.scene) {
                        ConsoleScene.XBOX_360,ConsoleScene.XBOX_ORIGINAL,ConsoleScene.XBOX_ONE -> { assertNull("$theme retained an overlay",caption.background);assertEquals(0,caption.paddingLeft);assertSame(card,caption.parent) }
                        ConsoleScene.XBOX_SERIES,ConsoleScene.XBOX_360_METRO -> {
                            assertSame("Gradient must be clipped by the actual cover",cover,caption.parent)
                            if(caption.visibility==View.VISIBLE) { assertEquals(cover.width,caption.width);assertEquals(cover.height,caption.bottom) };assertTrue(cover.clipToOutline)
                        }
                        else -> Unit
                    }
                }
                capture(v,"captions-${theme.name.lowercase()}")
            }
        } finally { v.close();c.pause().stop().destroy() }
    }
    @Test fun favoriteMarkerHasDistinctScalableArtworkForEveryConsoleIdentity() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();val view=FavoriteBadgeView(a);val signatures=mutableSetOf<Int>()
        try {
            for(theme in UiTheme.entries.filter { it.scene!=ConsoleScene.NONE }) {
                DashboardTheme.apply(a,theme);view.refresh()
                view.measure(View.MeasureSpec.makeMeasureSpec(96,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(96,View.MeasureSpec.EXACTLY));view.layout(0,0,96,96)
                val b=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888);view.draw(Canvas(b))
                val colors=IntArray(96*96);b.getPixels(colors,0,96,0,0,96,96);signatures+=colors.contentHashCode()
                assertTrue(Color.alpha(b.getPixel(48,48))>240)
                b.recycle();capture(view,"favorite-${theme.name.lowercase()}")
                assertFalse(view.isFocusable);assertFalse(view.isClickable)
            };assertEquals(6,signatures.size)
        } finally { c.pause().stop().destroy() }
    }
}
