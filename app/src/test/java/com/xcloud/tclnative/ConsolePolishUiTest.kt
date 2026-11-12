package com.xcloud.tclnative

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.xcloud.tclnative.catalog.*
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.signaling.*
import com.xcloud.tclnative.ui.dashboard.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ConsolePolishUiTest {
    private lateinit var server: MockWebServer
    @Before fun defaults() {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("app_appearance",0).edit().clear().commit()
        AppearanceSettingsStore(app).saveMotion(BackgroundMotion.OFF);DashboardTheme.apply(app,UiTheme.GREEN)
        val bright=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val white=ByteArrayOutputStream().also { bright.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray();bright.recycle()
        server=MockWebServer().apply {
            dispatcher=object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path=request.path!!
                    if(path.startsWith("/missing")) return MockResponse().setResponseCode(404)
                    val data=when { path.startsWith("/white") -> white
                        path.startsWith("/hero") -> javaClass.getResourceAsStream("/showcase/hero-0.jpg")!!.use { it.readBytes() }
                        else -> javaClass.getResourceAsStream("/showcase/poster-${path.substringAfter("poster-").substringBefore('.')}.png")!!.use { it.readBytes() } }
                    return MockResponse().setHeader("Content-Type","image/png").setBody(Buffer().write(data))
                }
            };start()
        }
    }
    @After fun cleanup() { server.shutdown();DashboardTheme.apply(RuntimeEnvironment.getApplication(),UiTheme.GREEN) }
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun data(bright: Boolean=false)=listOf("007 First Light","Battlefield™ 6","EA SPORTS FC™ 26 para Xbox Series X|S","Forza Horizon 6","Hades II","Microsoft Flight Simulator 2024").mapIndexed { i,n ->
        GameTitle("POLISH-$i",n,"PRODUCT-$i",posterUrl=server.url("/poster-$i.png").toString(),tileUrl=server.url("/poster-$i.png").toString(),heroUrl=server.url(if(bright) "/white.png" else "/hero.jpg").toString(),seriesOptimized=true)
    }
    private fun layout(v: View,w: Int=1280,h: Int=720) {
        (v as? GameDashboardView)?.awaitCatalog()
        repeat(3) { v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);shadowOf(Looper.getMainLooper()).idle() }
        v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h)
    }
    private fun settled(v: View) {
        repeat(70) { Thread.sleep(5);shadowOf(Looper.getMainLooper()).idleFor(16,TimeUnit.MILLISECONDS) }
        layout(v)
    }
    private fun bitmap(v: View)=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
    private fun capture(v: View,name: String) {
        val b=bitmap(v);File("build/reports/themes/polish-$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle()
    }
    private fun dashboard(a: Activity,bright: Boolean=false,play: (GameTitle)->Unit={}): GameDashboardView =
        GameDashboardView(a,GameLibrary(a,"polish-demo-${DashboardTheme.palette.theme.name}"),play,{},{},{}).also {
            a.setContentView(it);it.setGames(data(bright));it.setProfile(XboxProfile("Jogador","Perfil de demonstração",subscription="ULTIMATE"));it.setRecentHistory(data().map { g -> g.titleId });it.selectTab(LibraryTab.ALL)
            layout(it);it.restoreFocus();layout(it);settled(it)
        }

    @Test fun oneFocusRemainsVisibleOnAllFourEdgesAndTitlesFitTheirReservedLine() {
        DashboardTheme.apply(RuntimeEnvironment.getApplication(),UiTheme.XBOX_ONE)
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get()
        val played=mutableListOf<String>();val v=dashboard(a,play={played+=it.titleId})
        try {
            val grid=all(v).filterIsInstance<RecyclerView>().single()
            val card=grid.findViewHolderForAdapterPosition(1)!!.itemView
            card.requestFocus();layout(v)
            assertEquals("POLISH-1",v.selectedGame!!.titleId);assertSame(card,v.findFocus())
            val b=bitmap(card)
            for((x,y) in listOf(3 to card.height/2,(card.width-4) to card.height/2,card.width/2 to 3,card.width/2 to (card.height-4))) {
                val color=b.getPixel(x,y)
                assertTrue("Focus edge missing at $x/$y: ${color.toUInt().toString(16)}",Color.red(color)>235 && Color.green(color)>235 && Color.blue(color)>235)
            };b.recycle()
            val title=all(card).filterIsInstance<TextView>().single { it.text.toString()=="Battlefield™ 6" }
            assertTrue(title.layout.getLineBottom(0)<=title.height-title.compoundPaddingBottom)
            capture(v,"one-focus")
            v.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_A));assertEquals(listOf("POLISH-1"),played)
        } finally { v.close();controller.pause().stop().destroy() }
    }

    @Test fun seriesUsesVectorNavigationAndLargerSquareSelectionWithAReadableBrightBackdrop() {
        DashboardTheme.apply(RuntimeEnvironment.getApplication(),UiTheme.XBOX_SERIES)
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get()
        val played=mutableListOf<String>();val v=dashboard(a,true) { played+=it.titleId }
        try {
            val grid=all(v).filterIsInstance<RecyclerView>().single()
            val first=grid.findViewHolderForAdapterPosition(0)!!.itemView
            val second=grid.findViewHolderForAdapterPosition(1)!!.itemView
            assertTrue(first.width>second.width)
            val neighbor=bitmap(second);assertEquals("Small tiles must leave the hero visible above the cover",0,Color.alpha(neighbor.getPixel(second.width/2,10)));neighbor.recycle()
            val image=all(first).filterIsInstance<FixedArtworkView>().single()
            assertEquals(image.width,image.height)
            assertFalse(all(v).filterIsInstance<TextView>().any { it.isShown && it.text.toString() in setOf("GP","⌂","▶","JOGAR") })
            val b=bitmap(v);val bg=b.getPixel(18,190);assertTrue("Bright art must be shaded for text",Color.red(bg)<95 && Color.green(bg)<95 && Color.blue(bg)<95);b.recycle()
            second.requestFocus();layout(v);settled(v)
            assertSame(second,v.findFocus());assertTrue(second.width>first.width)
            val secondImage=all(second).filterIsInstance<FixedArtworkView>().single();assertEquals(secondImage.width,secondImage.height)
            val imageBounds=Rect();secondImage.getDrawingRect(imageBounds)
            (second as ViewGroup).offsetDescendantRectToMyCoords(secondImage,imageBounds)
            val focus=bitmap(second);val x=imageBounds.left-2;val y=imageBounds.centerY()
            val edge=focus.getPixel(x,y);assertTrue(Color.green(edge)>170 && Color.green(edge)>Color.red(edge));focus.recycle()
            capture(v,"series-bright")
            v.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_A));assertEquals(listOf("POLISH-1"),played)
            layout(v,2260,1080)
            assertEquals("Square cover at fractional phone scaling",secondImage.width,secondImage.height)
        } finally { v.close();controller.pause().stop().destroy() }
    }

    @Test fun bladesHasAnAccessibleSearchAboveTheProfileAndStrongerTabLabels() {
        DashboardTheme.apply(RuntimeEnvironment.getApplication(),UiTheme.XBOX_360)
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get();val v=dashboard(a)
        try {
            val search=all(v).single { it.contentDescription=="Pesquisar jogos" }
            val profile=all(v).single { it.contentDescription?.contains("Abrir configurações")==true }
            assertTrue(search.bottom<profile.top);assertTrue(search.width>=80)
            val tabs=all(v).filterIsInstance<TextView>().filter { it.isShown && it.isClickable && it.contentDescription?.contains(", selecionado")==true }
            assertEquals(1,tabs.size);assertTrue(tabs.single().textSize>=32);assertTrue(tabs.single().typeface.isBold)
            all(v).filterIsInstance<TextView>().filter { it.isShown && it.rotation!=0f }.forEach { assertTrue(it.textSize>=23);assertTrue(it.typeface.isBold) }
            capture(v,"360-tabs")
        } finally { v.close();controller.pause().stop().destroy() }
    }

    @Test fun eachConsoleQueueRetainsRealEstimatesStagesAndCancellationAcrossThemeChanges() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get();var cancelled=0
        val view=GameLoadingView(a) { cancelled++ }
        try {
            a.setContentView(view);view.begin(data()[0],0);view.updateQueue(QueueStatus(0,7000,QueueEstimate(787),0),8000)
            for(theme in listOf(UiTheme.XBOX_360,UiTheme.XBOX_ONE,UiTheme.XBOX_SERIES,UiTheme.XBOX_ORIGINAL,UiTheme.XBOX_360_NXE,UiTheme.XBOX_360_METRO,UiTheme.GREEN)) {
                DashboardTheme.apply(a,theme,a.findViewById(android.R.id.content));layout(view);settled(view)
                val labels=all(view).filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(labels.contains("Cerca de 13 min 7s"));assertTrue(labels.contains("8s"));assertEquals(LoadingPhase.QUEUED,view.phase)
                capture(view,"queue-${theme.name.lowercase()}")
                layout(view,1452,689)
                val cancel=all(view).single { it.contentDescription=="Cancelar espera e voltar ao catálogo" }
                assertTrue(cancel.isShown && cancel.isFocusable && cancel.isClickable)
                assertTrue(cancel.left>=0 && cancel.top>=0 && cancel.right<=1452 && cancel.bottom<=689)
            }
            view.updateProgress("ICE: connection");assertEquals(LoadingPhase.CONNECTING,view.phase)
            view.updateProgress("VIDEO: first packet");assertEquals(LoadingPhase.VIDEO,view.phase)
            view.cancelFromController();assertEquals(1,cancelled)
        } finally { view.close();controller.pause().stop().destroy() }
    }

    @Test fun nxeHasPerspectiveCardsAndMetroHasAnAsymmetricMosaicWithWorkingSelection() {
        for(theme in listOf(UiTheme.XBOX_360_NXE,UiTheme.XBOX_360_METRO)) {
            DashboardTheme.apply(RuntimeEnvironment.getApplication(),theme)
            val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get()
            val played=mutableListOf<String>();val v=dashboard(a,play={played+=it.titleId})
            try {
                val grid=all(v).filterIsInstance<RecyclerView>().single()
                val manager=grid.layoutManager as androidx.recyclerview.widget.GridLayoutManager
                val first=grid.findViewHolderForAdapterPosition(0)!!.itemView
                val second=grid.findViewHolderForAdapterPosition(1)!!.itemView
                if(theme==UiTheme.XBOX_360_NXE) {
                    assertEquals(RecyclerView.HORIZONTAL,manager.orientation);assertTrue(first.width>second.width)
                    val cover=all(second).filterIsInstance<FixedArtworkView>().single().parent as View
                    assertTrue("NXE neighbor must recede in perspective",cover.rotationY < -10)
                } else {
                    assertEquals(3,manager.spanCount);assertEquals(2,manager.spanSizeLookup.getSpanSize(0));assertEquals(1,manager.spanSizeLookup.getSpanSize(1))
                    assertTrue("Metro lead tile spans two columns",first.width>second.width*1.8f)
                    assertFalse("Loaded artwork must hide the placeholder behind it",all(first).filterIsInstance<TextView>().any { it.isShown && it.text.toString()=="007 FIRST LIGHT" })
                }
                capture(v,"era-${theme.name.lowercase()}")
                second.requestFocus();layout(v)
                assertSame(second,v.findFocus());assertEquals("POLISH-1",v.selectedGame!!.titleId)
                v.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_Y));layout(v);assertTrue(second.contentDescription.contains("favorito"))
                v.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_A));assertEquals(listOf("POLISH-1"),played)
            } finally { v.close();controller.pause().stop().destroy() }
        }
    }

    @Test fun phoneSearchIsLargerAndTheSeriesNoticeCannotCoverNavigationOrSubscription() {
        for(theme in UiTheme.entries) {
            DashboardTheme.apply(RuntimeEnvironment.getApplication(),theme)
            val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get();val v=dashboard(a)
            try {
                v.setMetadataProgress(CatalogService.Progress(2,8,true,false));layout(v,1452,689)
                val search=all(v).single { it.contentDescription=="Pesquisar jogos" }
                assertTrue("$theme phone search must have a larger target",search.width>=72 && search.height>=72)
                if(theme==UiTheme.XBOX_SERIES) {
                    fun area(child: View)=Rect(0,0,child.width,child.height).also { v.offsetDescendantRectToMyCoords(child,it) }
                    val notice=all(v).filterIsInstance<TextView>().single { it.text.startsWith("ALGUMAS CAPAS INDISPONÍVEIS") }
                    val profile=all(v).single { it.contentDescription?.contains("Abrir configurações")==true }
                    val tab=all(v).filterIsInstance<TextView>().single { it.isShown && it.isClickable && it.contentDescription?.contains(", selecionado")==true }
                    val subscription=all(profile).filterIsInstance<TextView>().single { it.text.toString()=="GAME PASS ULTIMATE" }
                    assertFalse(Rect.intersects(area(notice),area(profile)));assertFalse(Rect.intersects(area(notice),area(tab.parent as View)))
                    assertTrue(area(profile).contains(area(subscription)))
                    assertTrue(subscription.layout.getLineBottom(0)<=subscription.height)
                    capture(v,"series-notice-phone")
                }
            } finally { v.close();controller.pause().stop().destroy() }
        }
    }

    @Test fun oneFallsBackFromMissingPanoramicArtworkAndHasNoLooseGreenStripe() {
        DashboardTheme.apply(RuntimeEnvironment.getApplication(),UiTheme.XBOX_ONE)
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get();val v=dashboard(a)
        try {
            v.setGames(data().mapIndexed { i,g -> if(i==0) g.copy(heroUrl=server.url("/missing.jpg").toString()) else g });layout(v);v.restoreFocus();settled(v)
            val hero=all(v).filterIsInstance<FixedArtworkView>().single { it.contentDescription=="Ícone de 007 First Light" }
            assertTrue("Missing panorama must use the existing cover",hero.drawable is BitmapDrawable)
            assertEquals(android.widget.ImageView.ScaleType.FIT_CENTER,hero.scaleType)
            val b=bitmap(v);val color=b.getPixel(56,535);assertTrue("No decorative stripe between art and play button",Color.green(color)<120);b.recycle()
            capture(v,"one-artwork-fallback")
        } finally { v.close();controller.pause().stop().destroy() }
    }
}
