package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.widget.ImageView
import com.xcloud.tclnative.ui.dashboard.PosterLoader
import kotlinx.coroutines.*
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
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ArtworkLoadingTest {
    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); bitmap.recycle(); stream.toByteArray()
        }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
        }
        assertTrue("Artwork loading did not finish", condition())
    }
    @Test fun realPngDownloadsAndDecodesWhileAStaleRequestCannotReplaceTheNewGameCover() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val red = png(Color.RED); val blue = png(Color.BLUE)
        val server = MockWebServer().apply { start() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path == "/old.png")
                MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(red)).setBodyDelay(500, TimeUnit.MILLISECONDS)
            else MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(blue))
        }
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val loader = PosterLoader(activity, scope)
        try {
            val image = ImageView(activity)
            loader.load(image, server.url("/old.png").toString())
            await { server.requestCount >= 1 }
            loader.load(image, server.url("/new.png").toString())
            await { image.drawable is BitmapDrawable }
            assertEquals(Color.BLUE, (image.drawable as BitmapDrawable).bitmap.getPixel(0,0))
            Thread.sleep(600); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Color.BLUE, (image.drawable as BitmapDrawable).bitmap.getPixel(0,0))
            val before = server.requestCount
            loader.close()
            val next = PosterLoader(activity, scope)
            val thumbnail = ImageView(activity)
            next.load(thumbnail, server.url("/new.png").toString())
            assertEquals(Color.BLUE, (thumbnail.drawable as BitmapDrawable).bitmap.getPixel(0,0))
            assertEquals(before, server.requestCount) // The loading screen reuses the catalog's cache.
            next.close()
        } finally {
            loader.close(); scope.cancel(); server.shutdown(); controller.pause().stop().destroy()
        }
    }
}
