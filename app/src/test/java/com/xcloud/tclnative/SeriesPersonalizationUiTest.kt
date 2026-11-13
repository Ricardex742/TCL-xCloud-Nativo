package com.xcloud.tclnative

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.*
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
import org.robolectric.shadows.ShadowDialog
import okhttp3.mockwebserver.*
import okio.Buffer
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SeriesPersonalizationUiTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun reset() {
        app.getSharedPreferences("app_appearance",0).edit().clear().commit();UiText.apply(app,AppLanguage.PORTUGUESE)
        AppearanceSettingsStore(app).saveMotion(BackgroundMotion.OFF);DashboardTheme.apply(app,UiTheme.XBOX_SERIES)
    }
    @After fun cleanup() { (ShadowDialog.getLatestDialog() as? ConsoleDialog)?.dismiss();DashboardTheme.apply(app,UiTheme.GREEN) }
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun layout(v: View,w: Int=1280,h: Int=720) {
        (v as? GameDashboardView)?.awaitCatalog()
        repeat(4) {
            v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
            v.layout(0,0,w,h);shadowOf(Looper.getMainLooper()).idle()
        }
    }
    private fun dialog()=ShadowDialog.getLatestDialog() as ConsoleDialog
    private fun click(d: ConsoleDialog,label: String)=all(d.panel).filterIsInstance<TextView>().single { it.isClickable && it.text.contains(label) }.performClick()
    private fun capture(v: View,name: String) {
        val image=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(image))
        File("build/reports/themes/series-$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
    }
    private fun capture(d: ConsoleDialog,name: String) {
        val root=d.window!!.decorView
        layout(root,d.window!!.attributes.width,648);capture(root,name)
    }
    @Test fun galleryPreviewIsNotASelectionAndFavoritesAreIndependentAndReachableByController() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();a.setContentView(FrameLayout(a));layout(a.window.decorView)
        val store=SeriesPersonalizationStore(a);val before=PlaybackSettingsStore(a).read();var back=0
        try {
            val gallery=SeriesWallpaperGallery(a,emptyList()) { back++ }.show();capture(gallery,"wallpaper-gallery")
            assertFalse(store.read().gallery)
            val scroll=all(gallery.panel).filterIsInstance<HorizontalScrollView>().single()
            val tiles=all(scroll).filterIsInstance<FrameLayout>().filter { it.isClickable }
            assertEquals(6,tiles.size);tiles.first().requestFocus()
            repeat(12) {
                gallery.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));shadowOf(Looper.getMainLooper()).idle()
            }
            assertSame(tiles.last(),gallery.window!!.currentFocus);assertFalse(store.read().gallery)
            gallery.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_X))
            assertTrue(store.isFavorite("platinum"));assertFalse(store.read().gallery)
            click(gallery,"USAR FUNDO")
            assertTrue(store.read().gallery);assertEquals("platinum",store.selected().id);assertEquals(1,back)
            assertEquals(before,PlaybackSettingsStore(a).read())
            val favorites=SeriesWallpaperGallery(a,emptyList()) {}.show()
            click(favorites,"Favoritos");capture(favorites,"wallpaper-favorites")
            favorites.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_X))
            assertTrue(store.favorites().isEmpty());assertNotNull(favorites.window!!.currentFocus)
            favorites.dismiss()
        } finally { c.pause().stop().destroy() }
    }
    @Test fun guideUsesTheSelectedSideAndActionsAndKeepsExistingDismissCallbacks() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();a.setContentView(FrameLayout(a));layout(a.window.decorView)
        val store=SeriesPersonalizationStore(a);var resumed=0;var cleanups=0
        try {
            for(side in listOf(SeriesGuideSide.LEFT,SeriesGuideSide.RIGHT)) {
                store.save(store.read().copy(guide=side))
                val guide=GameMenuPanel.show(a,{}, {}, {}, {},profile=XboxProfile("DEMO PLAYER","DEMO PLAYER",subscription="ULTIMATE"))
                guide.addCleanup { cleanups++ };guide.setOnDismissListener { resumed++ }
                capture(guide,"guide-${side.name.lowercase()}")
                assertEquals(if(side==SeriesGuideSide.LEFT) Gravity.LEFT else Gravity.RIGHT,guide.window!!.attributes.gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
                assertTrue(guide.window!!.attributes.width<700)
                click(guide,"Retomar partida");shadowOf(Looper.getMainLooper()).idle()
                assertFalse(guide.isShowing)
            }
            assertEquals(2,cleanups);assertEquals(2,resumed)
            store.save(store.read().copy(guide=SeriesGuideSide.OFF))
            val original=GameMenuPanel.show(a,{}, {}, {}, {})
            assertEquals(Gravity.CENTER,original.window!!.attributes.gravity);original.dismiss()
        } finally { c.pause().stop().destroy() }
    }
    @Test fun gridReachesTheEndWithoutEscapingAndHomeKeepsItsCarouselOnPhoneAndTv() {
        for(size in listOf(1280 to 720,1452 to 689)) {
            val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
            val store=SeriesPersonalizationStore(a);store.save(store.read().copy(libraryGrid=true))
            val games=(0 until 70).map { GameTitle("GRID-$it","Game %02d".format(it),"PRODUCT-$it",gamePass=true,seriesOptimized=true) }
            val played=mutableListOf<String>();val view=GameDashboardView(a,GameLibrary(a,"grid-test"),{ played+=it.titleId },{},{},{})
            try {
                a.setContentView(view);view.setGames(games);view.selectTab(LibraryTab.ALL);layout(view,size.first,size.second);view.restoreFocus();layout(view,size.first,size.second)
                val grid=all(view).filterIsInstance<RecyclerView>().single();val manager=grid.layoutManager as GridLayoutManager
                assertEquals(RecyclerView.VERTICAL,manager.orientation);assertEquals(6,manager.spanCount)
                fun press(key: Int) { assertTrue(view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,key)));layout(view,size.first,size.second) }
                repeat(11) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
                assertEquals("GRID-66",view.selectedGame?.titleId)
                repeat(3) { press(KeyEvent.KEYCODE_DPAD_RIGHT) };assertEquals("GRID-69",view.selectedGame?.titleId)
                repeat(3) { press(KeyEvent.KEYCODE_DPAD_RIGHT);press(KeyEvent.KEYCODE_DPAD_DOWN) }
                assertEquals("GRID-69",view.selectedGame?.titleId);assertTrue(grid.hasFocus())
                val selected=grid.findViewHolderForAdapterPosition(69)!!.itemView
                val pixels=Bitmap.createBitmap(selected.width,selected.height,Bitmap.Config.ARGB_8888);selected.draw(Canvas(pixels))
                assertTrue("Bottom focus border must remain inside the final item",(pixels.height-12 until pixels.height).any { y ->
                    (0 until pixels.width).count { x -> pixels.getPixel(x,y)==0xff63b814.toInt() }>pixels.width*.75
                });pixels.recycle()
                view.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_A));assertEquals(listOf("GRID-69"),played)
                capture(view,"library-grid-${size.first}")
                view.selectTab(LibraryTab.RECENT);layout(view,size.first,size.second)
                assertEquals(RecyclerView.HORIZONTAL,manager.orientation);assertEquals(1,manager.spanCount)
                view.selectTab(LibraryTab.ALL);layout(view,size.first,size.second)
                store.save(store.read().copy(libraryGrid=false));shadowOf(Looper.getMainLooper()).idle();layout(view,size.first,size.second)
                assertEquals(RecyclerView.HORIZONTAL,manager.orientation)
            } finally { view.close();c.pause().stop().destroy() }
        }
    }
    @Test fun badgeTogglesKeepMetadataIntactAndHaveNoEffectOnOtherThemes() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();val store=SeriesPersonalizationStore(a)
        try {
            val badges=GameBadgesView(a);badges.bind(true,true,1f)
            assertEquals(2,all(badges).filterIsInstance<ImageView>().count { it.visibility==View.VISIBLE })
            store.save(store.read().copy(gamePassBadge=false));badges.bind(true,true,1f)
            val xs=all(badges).filterIsInstance<ImageView>().single { it.visibility==View.VISIBLE }
            assertEquals(8,(xs.layoutParams as FrameLayout.LayoutParams).leftMargin)
            store.save(store.read().copy(seriesBadge=false));badges.bind(true,true,1f);assertEquals(View.GONE,badges.visibility)
            DashboardTheme.apply(a,UiTheme.XBOX_ONE);badges.bind(true,true,1f)
            assertEquals(View.VISIBLE,badges.visibility)
            assertEquals(2,all(badges).filterIsInstance<ImageView>().count { it.visibility==View.VISIBLE })
        } finally { c.pause().stop().destroy() }
    }
    @Test fun wallpaperArtworkHasDistinctVisibleColorsAndMotion() {
        val signatures=mutableSetOf<Int>()
        for(style in WallpaperStyle.entries) {
            val art=SeriesWallpaperArt(style);art.setBounds(0,0,640,360)
            fun render(): IntArray {
                val b=Bitmap.createBitmap(640,360,Bitmap.Config.ARGB_8888);art.draw(Canvas(b))
                val pixels=IntArray(640*360);b.getPixels(pixels,0,640,0,0,640,360);b.recycle();return pixels
            }
            val still=render();signatures+=still.contentHashCode()
            assertTrue("$style must not be an opaque black rectangle",still.count { Color.red(it)+Color.green(it)+Color.blue(it)>60 }>10000)
            assertTrue(still.all { Color.alpha(it)==255 })
            art.phase=.25f;assertFalse("$style must visibly move",still.contentEquals(render()))
        }
        assertEquals(WallpaperStyle.entries.size,signatures.size)
    }
    @Test fun modalPanelsKeepTheBackgroundFrameAndGameplayStopsRotationWork() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        val store=SeriesPersonalizationStore(a)
        store.configureShuffle(WallpaperShuffle.HOURLY,WallpaperCategory.ALL,SeriesWallpaper.builtIns)
        val backdrop=ConsoleThemeBackdrop(a)
        try {
            a.setContentView(backdrop);layout(backdrop);backdrop.onWindowFocusChanged(true)
            assertTrue(backdrop.rotationPending)
            fun pixels(): IntArray {
                val b=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888);backdrop.draw(Canvas(b))
                val result=IntArray(1280*720);b.getPixels(result,0,1280,0,0,1280,720);b.recycle();return result
            }
            val visible=pixels()
            backdrop.onWindowFocusChanged(false)
            assertFalse(backdrop.rotationPending);assertArrayEquals(visible,pixels())
            backdrop.onWindowFocusChanged(true);assertTrue(backdrop.rotationPending)
            backdrop.setPresentationEnabled(false);assertFalse(backdrop.rotationPending);assertFalse(backdrop.framePending)
            backdrop.setPresentationEnabled(true);assertTrue(backdrop.rotationPending);assertArrayEquals(visible,pixels())
            backdrop.close();assertFalse(backdrop.rotationPending)
        } finally { backdrop.close();c.pause().stop().destroy() }
    }
    @Test fun appearanceChoicesReturnToTheirCallerAndOnlyChangeSeriesPreferences() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();a.setContentView(FrameLayout(a));layout(a.window.decorView)
        AppearanceSettingsStore(a).save(UiTheme.XBOX_SERIES)
        val media=PlaybackSettingsStore(a);val original=media.read();var returned=0
        try {
            val panel=PlaybackSettingsPanel(a,media,{}, {}, {}, {})
            val appearance=panel.showAppearance(onBack={ returned++ })
            click(appearance,"Personalizar Series X|S");capture(dialog(),"personalization")
            click(dialog(),"Guia:");click(dialog(),"Direita")
            click(dialog(),"Biblioteca completa:");click(dialog(),"Grade")
            click(dialog(),"Selos nas capas");click(dialog(),"Game Pass:")
            val settings=SeriesPersonalizationStore(a).read()
            assertEquals(SeriesGuideSide.RIGHT,settings.guide);assertTrue(settings.libraryGrid);assertFalse(settings.gamePassBadge)
            assertEquals(original,media.read())
            click(dialog(),"VOLTAR");click(dialog(),"VOLTAR");click(dialog(),"VOLTAR")
            assertEquals(1,returned)
            AppearanceSettingsStore(a).save(UiTheme.GREEN)
            val normal=PlaybackSettingsPanel(a,media,{}, {}, {}, {}).showAppearance()
            assertFalse(all(normal.panel).filterIsInstance<TextView>().any { it.isClickable && it.text.contains("Personalizar Series") })
            normal.dismiss()
        } finally { c.pause().stop().destroy() }
    }
    @Test fun catalogArtworkAppearsInTheGridGalleryAndSavedBackgroundAndReleasesDuringGameplay() {
        val server=MockWebServer().apply {
            dispatcher=object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val index=request.path!!.substringAfter("poster-").substringBefore('.').toInt()
                    val bytes=javaClass.getResourceAsStream("/showcase/poster-${index%6}.png")!!.use { it.readBytes() }
                    return MockResponse().setHeader("Content-Type","image/png").setBody(Buffer().write(bytes))
                }
            };start()
        }
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        val store=SeriesPersonalizationStore(a);store.save(store.read().copy(libraryGrid=true))
        val names=listOf("007 First Light","1000xRESIST","15 in 1 Solitaire","If You're a Ghost Call Me Here","30 Birds","33 Immortals")
        val games=names.mapIndexed { i,name -> GameTitle("ART-$i",name,"ART-PRODUCT-$i",
            posterUrl=server.url("/poster-$i.png").toString(),tileUrl=server.url("/poster-$i.png").toString(),
            heroUrl=server.url("/poster-$i.png").toString(),seriesOptimized=true) }
        val view=GameDashboardView(a,GameLibrary(a,"series-artwork"),{},{},{},{})
        fun await(condition: () -> Boolean) {
            val end=System.currentTimeMillis()+10000
            while(!condition() && System.currentTimeMillis()<end) { Thread.sleep(15);shadowOf(Looper.getMainLooper()).idle() }
            assertTrue("Artwork did not arrive",condition())
        }
        try {
            a.setContentView(view);view.setGames(games);view.setProfile(XboxProfile("DEMO PLAYER","DEMO PLAYER",subscription="ULTIMATE"))
            view.setGamePassProducts(setOf("ART-PRODUCT-1","ART-PRODUCT-5"));view.selectTab(LibraryTab.ALL);layout(view);view.restoreFocus();layout(view)
            val grid=all(view).filterIsInstance<RecyclerView>().single()
            await { grid.childCount==6 && all(grid).filterIsInstance<FixedArtworkView>().all { it.drawable is BitmapDrawable } }
            capture(view,"library-artwork")
            val gallery=SeriesWallpaperGallery(a,view.wallpaperChoices()) {}.show()
            click(gallery,"Jogos");capture(gallery,"gallery-artwork")
            val scroll=all(gallery.panel).filterIsInstance<HorizontalScrollView>().single()
            val first=all(scroll).filterIsInstance<FrameLayout>().first { it.isClickable }
            first.requestFocus()
            await { all(gallery.panel).filterIsInstance<FixedArtworkView>().first().drawable is BitmapDrawable }
            capture(gallery,"gallery-artwork")
            click(gallery,"USAR FUNDO")
            assertEquals("game:ART-0",store.selected().id)
            val backdrop=all(view).filterIsInstance<ConsoleThemeBackdrop>().single();backdrop.onWindowFocusChanged(true)
            await { backdrop.hasGalleryArtwork };capture(view,"saved-game-background")
            view.pauseArtwork();assertFalse(backdrop.hasGalleryArtwork);assertFalse(backdrop.rotationPending)
            view.resumeArtwork();layout(view);backdrop.onWindowFocusChanged(true)
            await { backdrop.hasGalleryArtwork }
        } finally { view.close();c.pause().stop().destroy();server.shutdown() }
    }
    @Test fun openingShuffleSurvivesCatalogRecreationAndChangesWhenTheAppActivityReopens() {
        SeriesPersonalizationStore(app).configureShuffle(WallpaperShuffle.OPEN,WallpaperCategory.XBOX,SeriesWallpaper.builtIns.take(2))
        fun render(a: Activity): Int {
            val background=ConsoleThemeBackdrop(a)
            return try {
                a.setContentView(background);layout(background);background.onWindowFocusChanged(true)
                val b=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888);background.draw(Canvas(b))
                val pixels=IntArray(1280*720);b.getPixels(pixels,0,1280,0,0,1280,720);b.recycle();pixels.contentHashCode()
            } finally { background.close() }
        }
        val first=Robolectric.buildActivity(Activity::class.java).setup()
        val initial=try {
            val signature=render(first.get());assertEquals(signature,render(first.get()));signature
        } finally { first.pause().stop().destroy() }
        val reopened=Robolectric.buildActivity(Activity::class.java).setup()
        try { assertNotEquals(initial,render(reopened.get())) } finally { reopened.pause().stop().destroy() }
    }
}
