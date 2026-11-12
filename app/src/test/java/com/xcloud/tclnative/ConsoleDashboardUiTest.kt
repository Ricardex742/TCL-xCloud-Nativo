package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xcloud.tclnative.catalog.*
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.ui.dashboard.*
import okhttp3.mockwebserver.*
import okio.Buffer
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
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ConsoleDashboardUiTest {
    private lateinit var server: MockWebServer
    private val themes=listOf(UiTheme.XBOX_360,UiTheme.XBOX_ONE,UiTheme.XBOX_SERIES,UiTheme.XBOX_ORIGINAL,UiTheme.XBOX_360_NXE,UiTheme.XBOX_360_METRO)
    @Before fun setup() {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("app_appearance",0).edit().clear().commit()
        AppearanceSettingsStore(app).saveMotion(BackgroundMotion.OFF)
        DashboardTheme.apply(app,UiTheme.GREEN)
        server=MockWebServer().apply {
            dispatcher=object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if(request.path!!.startsWith("/hero-0.jpg")) return MockResponse().setHeader("Content-Type","image/jpeg").setBody(Buffer().write(javaClass.getResourceAsStream("/showcase/hero-0.jpg")!!.use { it.readBytes() }))
                    val i=request.path!!.substringAfter("poster-").substringBefore('.').toInt()
                    return MockResponse().setHeader("Content-Type","image/png").setBody(Buffer().write(javaClass.getResourceAsStream("/showcase/poster-$i.png")!!.use { it.readBytes() }))
                }
            };start()
        }
    }
    @After fun reset() { server.shutdown();DashboardTheme.apply(RuntimeEnvironment.getApplication(),UiTheme.GREEN) }
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun games()=listOf("007 First Light","1000xRESIST","15 in 1 Solitaire","If You're a Ghost Call Me Here","30 Birds","33 Immortals").mapIndexed { i,n ->
        GameTitle("CONSOLE-$i",n,"PRODUCT-$i",posterUrl=server.url("/poster-$i.png").toString(),tileUrl=server.url("/poster-$i.png").toString(),heroUrl=if(i==0) server.url("/hero-0.jpg").toString() else null,seriesOptimized=i!=2)
    }
    private fun layout(v: GameDashboardView,w: Int=1280,h: Int=720) {
        v.awaitCatalog()
        repeat(3) {
            v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
            v.layout(0,0,w,h);shadowOf(Looper.getMainLooper()).idle()
        }
        // Keep the requested phone viewport after the simulated Activity's queued TV traversal.
        v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
        v.layout(0,0,w,h)
        assertEquals(w,v.width);assertEquals(h,v.height)
    }
    private fun capture(v: View,name: String) {
        val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b))
        File("build/reports/themes/immersive-$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle()
    }
    private fun awaitArtwork(v: View) {
        val limit=System.currentTimeMillis()+10000
        while(all(v).filterIsInstance<FixedArtworkView>().none { it.isShown && it.drawable is BitmapDrawable } && System.currentTimeMillis()<limit) {
            Thread.sleep(15);shadowOf(Looper.getMainLooper()).idleFor(16,TimeUnit.MILLISECONDS)
        }
        assertTrue(all(v).filterIsInstance<FixedArtworkView>().any { it.isShown && it.drawable is BitmapDrawable })
        shadowOf(Looper.getMainLooper()).idleFor(240,TimeUnit.MILLISECONDS)
        repeat(20) { Thread.sleep(5);shadowOf(Looper.getMainLooper()).idleFor(16,TimeUnit.MILLISECONDS) }
    }
    private fun click(v: View,label: String) {
        all(v).filterIsInstance<TextView>().single { it.isShown && it.isClickable && it.text.toString()==label }.performClick()
    }
    @Test fun eachConsoleHasItsOwnWorkingHomeAndCatalogRatherThanTheSixColumnGreenLayout() {
        for(theme in themes) {
            DashboardTheme.apply(RuntimeEnvironment.getApplication(),theme)
            val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get()
            val library=GameLibrary(a,"layout-${theme.name}")
            val played=mutableListOf<String>();val data=games();val view=GameDashboardView(a,library,{played+=it.titleId},{},{},{})
            try {
                a.setContentView(view);view.setGames(data);view.selectTab(LibraryTab.ALL)
                view.setProfile(XboxProfile("Jogador","Perfil de demonstração",subscription="ULTIMATE"))
                view.setGamePassProducts(setOf("PRODUCT-1","PRODUCT-5"));layout(view)
                val grid=all(view).filterIsInstance<RecyclerView>().single();val adapter=grid.adapter
                val manager=grid.layoutManager as GridLayoutManager
                assertEquals(6,adapter!!.itemCount)
                assertEquals(if(theme==UiTheme.XBOX_ONE || theme==UiTheme.XBOX_360_METRO) 3 else 1,manager.spanCount)
                assertEquals(if(theme==UiTheme.XBOX_SERIES || theme==UiTheme.XBOX_360_NXE) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL,manager.orientation)
                if(theme==UiTheme.XBOX_ORIGINAL) {
                    assertEquals(View.GONE,grid.visibility);capture(view,"xbox_original-home")
                    click(view,LibraryTab.ALL.label);layout(view)
                    assertEquals(View.VISIBLE,grid.visibility)
                } else { view.restoreFocus();layout(view) }
                assertFalse(all(view).filterIsInstance<TextView>().any { it.isShown && it.text.startsWith("Nenhum jogo disponível") })
                awaitArtwork(view);capture(view,theme.name.lowercase())
                val holder=grid.findViewHolderForAdapterPosition(1)!!;holder.itemView.requestFocus();layout(view)
                assertEquals(data[1].titleId,view.selectedGame!!.titleId)
                view.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_Y))
                assertTrue(library.isFavorite(data[1].titleId))
                view.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_A))
                assertEquals(listOf(data[1].titleId),played)
                if(theme==UiTheme.XBOX_ORIGINAL) {
                    view.back();layout(view);assertEquals(View.GONE,grid.visibility)
                    assertNotNull("Original must restore category focus: "+all(view).filter { it.isFocusable }.joinToString { "${(it as? TextView)?.text} shown=${it.isShown} visible=${it.visibility} focused=${it.hasFocus()} size=${it.width}x${it.height}" },view.findFocus());assertTrue(view.findFocus()!!.isShown)
                    click(view,LibraryTab.ALL.label);layout(view);assertSame(adapter,grid.adapter)
                }
                view.setSearch("33 Immortals");layout(view)
                assertSame(adapter,grid.adapter);assertEquals(1,adapter.itemCount)
                grid.findViewHolderForAdapterPosition(0)!!.itemView.performClick()
                assertEquals(data[5].titleId,played.last())
            } finally { view.close();controller.pause().stop().destroy() }
        }
    }
    @Test fun originalEmptyCategoryCanReturnWithoutAnInvisibleFocusTarget() {
        DashboardTheme.apply(RuntimeEnvironment.getApplication(),UiTheme.XBOX_ORIGINAL)
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get()
        val view=GameDashboardView(a,GameLibrary(a,"empty-original"),{},{},{},{})
        try {
            a.setContentView(view);view.setGames(games());layout(view)
            click(view,LibraryTab.FAVORITES.label);layout(view)
            val grid=all(view).filterIsInstance<RecyclerView>().single()
            assertEquals(0,grid.adapter!!.itemCount)
            val back=all(view).filterIsInstance<TextView>().single { it.isShown && it.text.toString()=="Voltar" }
            assertTrue(back.isFocusable);back.performClick();layout(view)
            assertEquals(View.GONE,grid.visibility)
            assertNotNull("Empty Original return: "+all(view).filter { it.isFocusable }.joinToString { "${(it as? TextView)?.text} shown=${it.isShown} visible=${it.visibility} focused=${it.hasFocus()} size=${it.width}x${it.height}" },view.findFocus());assertTrue(view.findFocus()!!.isShown)
        } finally { view.close();controller.pause().stop().destroy() }
    }
    @Test fun delayedEmptyHistoryDoesNotCoverTheOriginalHomeCategories() {
        DashboardTheme.apply(RuntimeEnvironment.getApplication(),UiTheme.XBOX_ORIGINAL)
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get()
        val view=GameDashboardView(a,GameLibrary(a,"original-delayed-history"),{},{},{},{})
        try {
            a.setContentView(view);layout(view)
            view.setGames(games());view.awaitCatalog()
            view.setRecentHistory(emptyList());view.awaitCatalog()
            val message=all(view).filterIsInstance<TextView>().single { it.text.toString()=="Seus jogos recentes do Xbox Cloud Gaming aparecerão aqui." }
            assertFalse("An asynchronous empty response must leave categories clear",message.isShown)
            val category=all(view).filterIsInstance<TextView>().single { it.isShown && it.isClickable && it.text.toString()==LibraryTab.RECENT.label }
            category.performClick();layout(view)
            assertTrue("The explanation belongs inside the empty category",message.isShown)
            view.back();layout(view);assertFalse(message.isShown)
        } finally { view.close();controller.pause().stop().destroy() }
    }
    @Test fun allConsoleCompositionsFitWidePhoneAndTvWithoutCroppingInteractiveContent() {
        for(theme in themes) {
            DashboardTheme.apply(RuntimeEnvironment.getApplication(),theme)
            val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get()
            val view=GameDashboardView(a,GameLibrary(a,"bounds-${theme.name}"),{},{},{},{})
            try {
                a.setContentView(view);view.setGames(games());view.selectTab(LibraryTab.ALL)
                for((w,h) in listOf(1452 to 689,2340 to 1080,1920 to 1080)) {
                    layout(view,w,h)
                    if(theme==UiTheme.XBOX_ORIGINAL) { click(view,LibraryTab.ALL.label);layout(view,w,h) }
                    val grid=all(view).filterIsInstance<RecyclerView>().single()
                    assertTrue("$theme grid within viewport $w/$h",grid.left>=0 && grid.top>=0 && grid.right<=w && grid.bottom<=h)
                    val footerActions=all(view).filterIsInstance<TextView>().filter { it.isShown && it.text.toString() in setOf("SELECIONAR","VOLTAR","FAVORITAR","BUSCAR","CONFIGURAÇÕES") }
                    assertEquals(5,footerActions.size)
                    footerActions.forEach { v ->
                        val r=Rect();v.getDrawingRect(r);view.offsetDescendantRectToMyCoords(v,r)
                        assertTrue("$theme footer clipped: $r",r.left>=0 && r.top>=0 && r.right<=w && r.bottom<=h)
                    }
                    if(w==1452) capture(view,"${theme.name.lowercase()}-phone")
                    if(theme==UiTheme.XBOX_ORIGINAL) { view.back();layout(view,w,h) }
                }
            } finally { view.close();controller.pause().stop().destroy() }
        }
    }
}
