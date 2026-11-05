package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.xcloud.tclnative.catalog.CatalogCache
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.ui.dashboard.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
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
class SessionTransitionTest {
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun layout(v: View,w: Int=1280,h: Int=720) {
        repeat(2) { v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY)); v.layout(0,0,w,h) }
    }
    private fun capture(v: View,name: String) {
        val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        File("build/reports/dashboard/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) }; b.recycle()
    }
    @Test fun cachedArtworkIsVisualOnlyAndCannotRestoreInputCapabilitiesOrUnknownGames() {
        val f=File.createTempFile("transition-art",".json")
        val cache=CatalogCache(f)
        cache.save(listOf(GameTitle("GAME","Jogo salvo","PRODUCT",posterUrl="https://art.example/poster.png",
            heroUrl="https://art.example/hero.png",supportedInputTypes=setOf("NativeTouch"))))
        val restored=cache.artwork("GAME")!!
        assertEquals("Jogo salvo",restored.name); assertEquals("https://art.example/hero.png",restored.heroUrl)
        assertTrue(restored.supportedInputTypes.isEmpty()); assertFalse(restored.gamePass)
        assertNull(cache.artwork("OTHER"))
        f.writeText("not json"); assertNull(cache.artwork("GAME")); f.delete()
    }
    @Test fun entryExitAndOrdinaryStartupReleasePreviousArtworkAndStayInsideTheWindow() {
        val c=Robolectric.buildActivity(Activity::class.java).setup(); val a=c.get()
        val view=SessionTransitionView(a); a.setContentView(view)
        val url="https://art.example/transition-demo.jpg"
        val image=BitmapFactory.decodeFile("../docs/screenshots/gameplay.jpg")!!
        ArtworkBackend.get(a).cache.put(ArtworkRequest.create(url,1280,720),image)
        val game=GameTitle("DEMO","Partida de demonstração",heroUrl=url)
        try {
            view.showStartup(game,"Preparando seu jogo…"); shadowOf(Looper.getMainLooper()).idle()
            layout(view)
            val banner=all(view).filterIsInstance<ImageView>().single { it.contentDescription=="Banner de Partida de demonstração" }
            assertSame(image,(banner.drawable as BitmapDrawable).bitmap)
            capture(view,"transition-shortcut")
            view.showExit(game); layout(view)
            assertTrue(view.exiting)
            assertTrue(all(view).filterIsInstance<TextView>().any { it.text.toString()=="Saindo do jogo…" })
            capture(view,"transition-exit")
            view.dismiss(); assertNull(banner.drawable)
            view.showStartup(null,"Carregando seu catálogo…")
            val logo=all(view).filterIsInstance<ImageView>().single { it.contentDescription=="Logo xCLOUD" }
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(logo.drawable==null && System.nanoTime()<deadline) { shadowOf(Looper.getMainLooper()).idleFor(16,TimeUnit.MILLISECONDS); Thread.sleep(5) }
            layout(view); assertEquals(View.VISIBLE,logo.visibility); assertNull(banner.drawable)
            capture(view,"transition-startup")
            for((w,h) in listOf(1280 to 590,1920 to 1080,3840 to 2160)) {
                layout(view,w,h)
                all(view).filter { it.parent===view && it.visibility==View.VISIBLE }.forEach {
                    assertTrue(it.left>=0 && it.top>=0 && it.right<=w && it.bottom<=h)
                }
            }
        } finally { view.close(); c.pause().stop().destroy() }
    }
}
