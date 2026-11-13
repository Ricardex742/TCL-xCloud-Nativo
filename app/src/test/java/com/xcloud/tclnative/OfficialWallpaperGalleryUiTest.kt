package com.xcloud.tclnative

import android.app.Activity
import android.graphics.*
import android.os.Looper
import android.view.*
import android.widget.*
import com.xcloud.tclnative.i18n.*
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.ui.dashboard.*
import okhttp3.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class OfficialWallpaperGalleryUiTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun reset() {
        app.getSharedPreferences("app_appearance",0).edit().clear().commit();UiText.apply(app,AppLanguage.PORTUGUESE)
        AppearanceSettingsStore(app).saveMotion(BackgroundMotion.OFF);DashboardTheme.apply(app,UiTheme.XBOX_SERIES)
    }
    @After fun cleanup() { (ShadowDialog.getLatestDialog() as? ConsoleDialog)?.dismiss();DashboardTheme.apply(app,UiTheme.GREEN) }
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun layout(v: View,w: Int=1280,h: Int=720) {
        repeat(3) { v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);shadowOf(Looper.getMainLooper()).idle() }
    }
    private fun capture(d: ConsoleDialog,name: String) {
        val v=d.window!!.decorView;layout(v,d.window!!.attributes.width,648)
        val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b))
        File("build/reports/themes/official-$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle()
    }
    private fun click(d: ConsoleDialog,text: String)=all(d.panel).filterIsInstance<TextView>().single { it.isClickable && it.text.contains(text) }.performClick()
    private fun tiles(d: ConsoleDialog)=all(all(d.panel).filterIsInstance<HorizontalScrollView>().single()).filterIsInstance<FrameLayout>().filter { it.isClickable }
    private fun await(predicate: () -> Boolean) {
        val limit=System.currentTimeMillis()+10000
        while(!predicate() && System.currentTimeMillis()<limit) { Thread.sleep(10);shadowOf(Looper.getMainLooper()).idle() }
        assertTrue("Image/UI state did not arrive",predicate())
    }
    private fun backend(a: Activity,requests: ConcurrentLinkedQueue<String>,fail: Boolean=false,gate: java.util.concurrent.CountDownLatch?=null): ArtworkBackend {
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request();requests+=request.url.toString()
            gate?.await(5,TimeUnit.SECONDS)
            val png=javaClass.getResourceAsStream("/wallpapers/${if(request.url.toString().contains("Xbox-360")) "xbox-360" else "xbox-series"}.png")!!.use { it.readBytes() }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if(fail) 404 else 200).message("Fixture")
                .body((if(fail) byteArrayOf() else png).toResponseBody("image/png".toMediaType())).build()
        }.build()
        return ArtworkBackend(a,client=client)
    }
    @Test fun officialCategoryLoadsOnlyVisibleArtAndKeepsSourceWhenFavoritedAndSelected() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();a.setContentView(FrameLayout(a));layout(a.window.decorView)
        val requests=ConcurrentLinkedQueue<String>();val images=backend(a,requests)
        try {
            val gallery=SeriesWallpaperGallery(a,emptyList(),OfficialXboxWallpapers.read(a),images) {}.show();capture(gallery,"gallery")
            click(gallery,"Oficiais Xbox");layout(gallery.window!!.decorView,gallery.window!!.attributes.width,648)
            assertEquals(SeriesWallpaperGallery.PAGE_SIZE,tiles(gallery).size)
            val first=tiles(gallery).first();first.requestFocus()
            val apply=all(gallery.panel).filterIsInstance<TextView>().single { it.isClickable && it.text=="USAR FUNDO" }
            await { apply.isEnabled };capture(gallery,"xbox-360")
            assertTrue("The catalog must not download hundreds of hidden images",requests.distinct().size<20)
            gallery.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_X))
            val store=SeriesPersonalizationStore(a);assertTrue(store.favorites().single().official)
            apply.performClick()
            assertEquals("XBOX 360 Logo",store.selected().name);assertEquals(WallpaperCategory.OFFICIAL,store.selected().category)
        } finally { images.close();c.pause().stop().destroy() }
    }
    @Test fun controllerCrossesPagesAndSearchReachesTheFinalOfficialBackground() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();a.setContentView(FrameLayout(a));layout(a.window.decorView)
        val requests=ConcurrentLinkedQueue<String>();val images=backend(a,requests)
        val items=(0 until 65).map { i -> OfficialXboxWallpapers.entry("Official demo %02d".format(i),
            "https://assets.xboxservices.com/assets/00/00/00000000-0000-0000-0000-${i.toString().padStart(12,'0')}.png")!! }
        try {
            val gallery=SeriesWallpaperGallery(a,emptyList(),items,images) {}.show();capture(gallery,"pagination")
            click(gallery,"Oficiais Xbox");tiles(gallery).first().requestFocus()
            repeat(27) { gallery.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT)) }
            layout(gallery.window!!.decorView,gallery.window!!.attributes.width,648)
            assertEquals("Official demo 27",gallery.window!!.currentFocus!!.contentDescription)
            repeat(40) { gallery.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));shadowOf(Looper.getMainLooper()).idle() }
            assertEquals("Official demo 64",gallery.window!!.currentFocus!!.contentDescription)
            assertTrue(tiles(gallery).size<=SeriesWallpaperGallery.PAGE_SIZE)
            click(gallery,"Pesquisar fundos")
            val search=ShadowDialog.getLatestDialog() as ConsoleDialog
            all(search.panel).filterIsInstance<EditText>().single().setText("demo 64")
            click(search,"BUSCAR");layout(gallery.window!!.decorView,gallery.window!!.attributes.width,648)
            assertEquals(1,tiles(gallery).size);assertEquals("Official demo 64",tiles(gallery).single().contentDescription)
            capture(gallery,"search");gallery.dismiss()
        } finally { images.close();c.pause().stop().destroy() }
    }
    @Test fun unavailableImageDoesNotEnableApplyOrSilentlySelectAReplacement() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();a.setContentView(FrameLayout(a));layout(a.window.decorView)
        val images=backend(a,ConcurrentLinkedQueue(),fail=true)
        val item=OfficialXboxWallpapers.read(a).first()
        try {
            val gallery=SeriesWallpaperGallery(a,emptyList(),listOf(item),images) {}.show()
            click(gallery,"Oficiais Xbox");tiles(gallery).first().requestFocus()
            await { all(gallery.panel).filterIsInstance<TextView>().any { it.visibility==View.VISIBLE && it.text=="Imagem indisponível. Tente novamente." } }
            val apply=all(gallery.panel).filterIsInstance<TextView>().single { it.isClickable && it.text=="USAR FUNDO" }
            assertFalse(apply.isEnabled);assertFalse(SeriesPersonalizationStore(a).read().gallery)
            capture(gallery,"unavailable");gallery.dismiss()
        } finally { images.close();c.pause().stop().destroy() }
    }
}
