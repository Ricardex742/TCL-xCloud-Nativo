package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.xcloud.tclnative.catalog.GameLibrary
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.ui.dashboard.GameDashboardView
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DashboardArtworkTest {
    private fun all(view: View): List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun layout(view: View) {
        repeat(2) {
            view.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY))
            view.layout(0,0,1280,720)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
    private fun await(condition: () -> Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
        while(!condition() && System.nanoTime()<end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue(condition())
    }
    @Test fun coldCatalogUsesVisibleThumbnailsAndMetadataAndFavoritesDoNotReloadThem() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup(); val activity=controller.get()
        val server=MockWebServer().apply { start() }
        val source=Bitmap.createBitmap(720,1080,Bitmap.Config.RGB_565).apply { eraseColor(Color.BLUE) }
        val bytes=ByteArrayOutputStream().use { source.compress(Bitmap.CompressFormat.PNG,100,it); source.recycle(); it.toByteArray() }
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest)=MockResponse().setBody(Buffer().write(bytes))
        }
        val library=GameLibrary(activity,"cold-artwork")
        val games=(0 until 60).map { GameTitle("art-$it","Jogo %02d".format(it),posterUrl=server.url("/$it.png").toString()) }
        var played: String?=null
        val view=GameDashboardView(activity,library,{ played=it.titleId },{},{},{})
        try {
            activity.setContentView(view); view.setGames(games); layout(view)
            val grid=all(view).filterIsInstance<RecyclerView>().single()
            val images=all(grid).filterIsInstance<ImageView>()
            assertTrue(images.isNotEmpty())
            await { images.all { it.drawable is BitmapDrawable } }
            val bitmaps=images.map { (it.drawable as BitmapDrawable).bitmap }
            assertTrue(bitmaps.all { it.width<=192 && it.height<=320 })
            assertTrue("Cold requests=${server.requestCount}, attached covers=${images.size}; must not fetch the whole catalog",server.requestCount<games.size/2)
            val first=grid.getChildAt(0); first.requestFocus()
            // Metadata with the same image URL keeps the bitmap and the selected game.
            view.setGames(games.map { it.copy(seriesOptimized=true) }); layout(view)
            val count=server.requestCount
            view.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_Y))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(library.isFavorite(games.first().titleId))
            images.forEachIndexed { i,image -> assertSame(bitmaps[i],(image.drawable as BitmapDrawable).bitmap) }
            assertEquals(count,server.requestCount)
            first.requestFocus()
            view.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_A))
            assertEquals(games.first().titleId,played)
        } finally { view.close(); server.shutdown(); controller.pause().stop().destroy() }
    }
}
