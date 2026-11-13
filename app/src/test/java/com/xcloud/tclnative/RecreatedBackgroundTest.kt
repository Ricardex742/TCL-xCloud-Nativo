package com.xcloud.tclnative

import android.app.Activity
import android.app.ActivityManager
import android.graphics.*
import android.os.Looper
import android.view.*
import android.widget.*
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class RecreatedBackgroundTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun reset() {
        app.getSharedPreferences("app_appearance",0).edit().clear().commit()
        UiText.apply(app,AppLanguage.PORTUGUESE);DashboardTheme.apply(app,UiTheme.XBOX_SERIES)
    }
    @After fun cleanup() { ShadowDialog.getLatestDialog()?.dismiss();DashboardTheme.apply(app,UiTheme.GREEN) }
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun layout(v: View,w: Int=1280,h: Int=720) {
        repeat(3) { v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);shadowOf(Looper.getMainLooper()).idle() }
    }
    private fun advance(ms: Long)=shadowOf(Looper.getMainLooper()).idleFor(ms,TimeUnit.MILLISECONDS)
    private fun pixels(art: MotionWallpaperArt,phase: Float,w: Int=640,h: Int=360): IntArray {
        art.setBounds(0,0,w,h);art.phase=phase
        val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);art.draw(Canvas(b))
        return IntArray(w*h).also { b.getPixels(it,0,w,0,0,w,h);b.recycle() }
    }
    @Test fun catalogueUsesExplicitRecreationLabelsAndPersistsCanonicalEntriesIndependentlyFromPlayback() {
        val entries=RecreatedXboxBackgrounds.entries
        assertTrue(entries.size>=50);assertEquals(entries.size,entries.map { it.id }.toSet().size)
        assertEquals(RecreationFamily.entries.toSet(),entries.map { it.family }.toSet())
        val store=SeriesPersonalizationStore(app);val playback=PlaybackSettingsStore(app).read()
        entries.forEach { design ->
            val entry=design.wallpaper();assertTrue(entry.label.startsWith("Recriação •"));assertFalse(entry.official)
            assertEquals(entry,SeriesWallpaper.parse(entry.json()));assertEquals(WallpaperCategory.RECREATIONS,entry.category)
        }
        val selected=entries.first { it.family==RecreationFamily.CORE }.wallpaper()
        store.select(selected);store.toggleFavorite(selected)
        val restarted=SeriesPersonalizationStore(app)
        assertEquals(selected,restarted.selected());assertTrue(restarted.isFavorite(selected.id))
        assertEquals(playback,PlaybackSettingsStore(app).read())
        assertNull(SeriesWallpaper.parse("""{"id":"recreation:unknown","name":"pretend","url":"https://evil.example/art.png"}"""))
        val forged=org.json.JSONObject(selected.json()).put("name","Official original").put("url","https://evil.example/art.png")
        assertEquals(selected,SeriesWallpaper.parse(forged.toString()))
    }
    @Test fun everyDesignChangesItsElementsAndEveryFamilyHasASeamlessCycle() {
        val signatures=mutableSetOf<Int>()
        RecreatedXboxBackgrounds.entries.forEach { design ->
            val art=RecreatedWallpaperArt(design)
            val a=pixels(art,0f,320,180);val b=pixels(art,.24f,320,180)
            assertFalse("Static design: ${design.name}",a.contentEquals(b));signatures+=a.contentHashCode()
        }
        assertTrue("Palettes and motifs must create variation",signatures.size>=35)
        RecreatedXboxBackgrounds.entries.distinctBy { it.family }.forEach { design ->
            val art=RecreatedWallpaperArt(design)
            assertArrayEquals("Loop seam in ${design.family}",pixels(art,0f),pixels(art,1f))
            art.lowDetail=true;assertFalse(pixels(art,.15f).contentEquals(pixels(art,.65f)))
            val b=Bitmap.createBitmap(960,540,Bitmap.Config.ARGB_8888);art.lowDetail=false;art.phase=.22f;art.setBounds(0,0,960,540);art.draw(Canvas(b))
            File("build/reports/recreations/${design.family.name.lowercase()}.png").apply { parentFile!!.mkdirs() }.outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle()
        }
    }
    @Test fun chosenArtworkUsesTrustedPublicImagesAndSurvivesRoundTrip() {
        val choices=RecreatedXboxBackgrounds.wallpapers(app)
        assertEquals(RecreatedXboxBackgrounds.entries.size,choices.size)
        assertTrue(choices.any { it.artUrl!=null })
        choices.forEach { entry ->
            entry.artUrl?.let { assertTrue(OfficialXboxWallpapers.trustedAsset(it)) }
            assertEquals(entry,SeriesWallpaper.parse(entry.json()))
        }
    }
    @Test fun everyPublicArtworkCanBeAnimatedWithAnExplicitEffectAndSavedWithoutChangingItsImage() {
        val official=OfficialXboxWallpapers.read(app)
        assertTrue(official.size>600)
        official.forEach { original -> ArtAnimationEffect.entries.forEach { effect ->
            val animated=RecreatedXboxBackgrounds.animatedArtwork(original,effect)!!
            assertEquals(original.artUrl,animated.artUrl)
            assertEquals(original,RecreatedXboxBackgrounds.originalArtwork(animated))
            assertEquals(effect,RecreatedXboxBackgrounds.artworkEffect(animated))
            assertNotNull(animated.recreation);assertFalse(animated.official)
            assertEquals(animated,SeriesWallpaper.parse(animated.json()))
        } }
        val selected=RecreatedXboxBackgrounds.animatedArtwork(official.first(),ArtAnimationEffect.FIRE)!!
        val store=SeriesPersonalizationStore(app);store.select(selected);store.toggleFavorite(selected)
        assertEquals(selected,SeriesPersonalizationStore(app).selected());assertTrue(store.isFavorite(selected.id))
        val bad=org.json.JSONObject(selected.json()).put("url","https://evil.example/image.png")
        assertNull(SeriesWallpaper.parse(bad.toString()))
        assertNull(SeriesWallpaper.parse(selected.copy(id=selected.id+":UNKNOWN").json()))
        assertNull(SeriesWallpaper.parse(selected.copy(id="recreation-art:bad:AUTO").json()))
    }
    @Test fun particlesKeepTheOriginalArtworkStillWhileWaterOnlyDeformsItsLowerRegion() {
        val bitmap=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888)
        for(y in 0 until 180) for(x in 0 until 320) bitmap.setPixel(x,y,Color.rgb(x*255/320,y*255/180,if((x/5+y/5)%2==0) 40 else 210))
        fun base(effect: ArtAnimationEffect,phase: Float): IntArray {
            val entry=RecreatedXboxBackgrounds.animatedArtwork(OfficialXboxWallpapers.read(app).first(),effect)!!
            val art=RecreatedWallpaperArt(entry.recreation!!).apply { this.phase=phase }
            val image=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888)
            art.drawArtwork(Canvas(image),bitmap,320,180)
            return IntArray(320*180).also { image.getPixels(it,0,320,0,0,320,180);image.recycle() }
        }
        assertArrayEquals(base(ArtAnimationEffect.PARTICLES,0f),base(ArtAnimationEffect.PARTICLES,.3f))
        val a=base(ArtAnimationEffect.WATER,0f);val b=base(ArtAnimationEffect.WATER,.3f)
        assertArrayEquals(a.copyOfRange(0,320*65),b.copyOfRange(0,320*65))
        assertFalse(a.copyOfRange(320*100,a.size).contentEquals(b.copyOfRange(320*100,b.size)))
        assertArrayEquals(base(ArtAnimationEffect.WATER,0f),base(ArtAnimationEffect.WATER,1f))
        bitmap.recycle()
    }
    @Test fun galleryAnimatesTheChosenOriginalWithPreviewAndOnlyAppliesAfterConfirmation() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();a.setContentView(FrameLayout(a));layout(a.window.decorView)
        val original=OfficialXboxWallpapers.read(a).first { it.name.contains("Sea of Thieves",true) }
        val backend=ArtworkBackend(a)
        val demo=File("build/reports/recreations/demo-art.jpg")
        val bitmap=if(demo.isFile) BitmapFactory.decodeFile(demo.path) else javaClass.getResourceAsStream("/showcase/poster-0.png")!!.use { BitmapFactory.decodeStream(it) }
        for((w,h) in listOf(768 to 432,256 to 144)) {
            val size=backend.policy.sizeFor(w,h)
            val scaled=Bitmap.createScaledBitmap(bitmap,size.width,size.height,true)
            for(url in listOfNotNull(original.artUrl,original.previewUrl)) backend.cache.put(ArtworkRequest.create(url,size.width,size.height),scaled)
        }
        try {
            val gallery=SeriesWallpaperGallery(a,emptyList(),listOf(original),backend,recreatedItems=emptyList(),back={}).show()
            fun click(d: ConsoleDialog,label: String)=all(d.panel).filterIsInstance<TextView>().single { it.isClickable && it.text.toString().equals(label,true) }.performClick()
            layout(gallery.window!!.decorView,gallery.window!!.attributes.width,648);click(gallery,"Oficiais Xbox")
            layout(gallery.window!!.decorView,gallery.window!!.attributes.width,648)
            val strip=all(gallery.panel).filterIsInstance<HorizontalScrollView>().single()
            all(strip).filterIsInstance<FrameLayout>().first { it.isClickable }.requestFocus()
            click(gallery,"Animar esta arte")
            val effects=ShadowDialog.getLatestDialog() as ConsoleDialog
            assertTrue(effects.isShowing);click(effects,ArtAnimationEffect.WATER.label)
            layout(gallery.window!!.decorView,gallery.window!!.attributes.width,648)
            assertTrue(gallery.isShowing);assertFalse(SeriesPersonalizationStore(a).read().gallery)
            val preview=all(gallery.panel).filterIsInstance<MotionArtworkView>().single()
            assertTrue(preview.drawable is RecreatedWallpaperArt)
            assertTrue(all(gallery.panel).filterIsInstance<TextView>().any { it.text.contains("Arte original com animação") })
            val image=Bitmap.createBitmap(gallery.window!!.decorView.width,648,Bitmap.Config.ARGB_8888)
            gallery.window!!.decorView.draw(Canvas(image))
            File("build/reports/recreations/gallery-real-art.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
            if(demo.isFile) {
                for(effect in listOf(ArtAnimationEffect.WATER,ArtAnimationEffect.FIRE,ArtAnimationEffect.MIST)) {
                    val art=RecreatedWallpaperArt(RecreatedXboxBackgrounds.animatedArtwork(original,effect)!!.recreation!!).apply { hasArtwork=true;setBounds(0,0,960,540) }
                    repeat(48) { i ->
                        art.phase=i/48f;val frame=Bitmap.createBitmap(960,540,Bitmap.Config.ARGB_8888);val canvas=Canvas(frame)
                        art.drawArtwork(canvas,bitmap,960,540);art.draw(canvas)
                        File("build/reports/recreations/${effect.name.lowercase()}/frame-%03d.png".format(i)).apply { parentFile!!.mkdirs() }.outputStream().use { frame.compress(Bitmap.CompressFormat.PNG,100,it) };frame.recycle()
                    }
                }
            }
            click(gallery,"USAR FUNDO")
            val selected=SeriesPersonalizationStore(a).selected()
            assertEquals(original.artUrl,selected.artUrl);assertEquals(ArtAnimationEffect.WATER,RecreatedXboxBackgrounds.artworkEffect(selected))
            assertFalse(gallery.isShowing);assertFalse(preview.framePending)
        } finally { backend.close();bitmap.recycle();c.pause().stop().destroy() }
    }
    @Test fun previewIsExplicitlyLabelledAndItsTimerStopsWithTheDialog() {
        AppearanceSettingsStore(app).saveMotion(BackgroundMotion.ON)
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();a.setContentView(FrameLayout(a));layout(a.window.decorView)
        try {
            val dialog=SeriesWallpaperGallery(a,emptyList(),emptyList(),back={}).show();layout(dialog.window!!.decorView,dialog.window!!.attributes.width,648)
            all(dialog.panel).filterIsInstance<TextView>().first { it.isClickable && it.text.toString().equals("Recriações",true) }.performClick()
            layout(dialog.window!!.decorView,dialog.window!!.attributes.width,648)
            val strip=all(dialog.panel).filterIsInstance<HorizontalScrollView>().single()
            all(strip).filterIsInstance<FrameLayout>().first { it.isClickable }.requestFocus()
            layout(dialog.window!!.decorView,dialog.window!!.attributes.width,648)
            assertTrue(all(dialog.panel).filterIsInstance<TextView>().any { it.text.contains("não é o arquivo original") })
            val preview=all(dialog.panel).filterIsInstance<MotionArtworkView>().single()
            preview.onWindowFocusChanged(true);preview.onVisibilityAggregated(true);advance(200)
            assertTrue(preview.framePending);assertTrue(preview.ticks>0)
            val ticks=preview.ticks;dialog.dismiss();advance(500);assertEquals(ticks,preview.ticks);assertFalse(preview.framePending)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun gameplayStopsTheBackgroundAndLowMemoryAutomaticHasNoAnimationTimer() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        val memory=shadowOf(a.getSystemService(ActivityManager::class.java))
        memory.setMemoryInfo(ActivityManager.MemoryInfo().apply { totalMem=900L*1024*1024 })
        val store=SeriesPersonalizationStore(a);store.select(RecreatedXboxBackgrounds.entries.first { it.family==RecreationFamily.CORE }.wallpaper())
        val background=ConsoleThemeBackdrop(a)
        try {
            a.setContentView(background);layout(background);background.onWindowFocusChanged(true);background.onVisibilityAggregated(true)
            advance(500);assertFalse(background.framePending);assertEquals(0L,background.renderedTicks)
            AppearanceSettingsStore(a).saveMotion(BackgroundMotion.ON);advance(700)
            assertTrue(background.framePending);assertTrue(background.renderedTicks in 4..8)
            background.setPresentationEnabled(false);val ticks=background.renderedTicks;advance(1000)
            assertFalse(background.framePending);assertEquals(ticks,background.renderedTicks)
        } finally { background.close();c.pause().stop().destroy() }
    }
}
