package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.widget.ImageView
import com.xcloud.tclnative.ui.dashboard.PosterLoader
import com.xcloud.tclnative.ui.dashboard.ArtworkBackend
import com.xcloud.tclnative.ui.dashboard.ArtworkPolicy
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.OkHttpClient
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
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ArtworkLoadingTest {
    @Test fun memoryPressureReleasesTheCacheWithoutRecyclingDisplayedArtAndLowEndDecodesStayBounded() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        val server=MockWebServer().apply { start() }
        server.enqueue(MockResponse().setBody(Buffer().write(png(Color.BLUE,1920,1080))))
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val backend=ArtworkBackend(activity,ArtworkPolicy.forDevice(256,false,4,true),OkHttpClient())
        val loader=PosterLoader(activity,scope,backend)
        try {
            val image=ImageView(activity)
            loader.load(image,server.url("/hero.png").toString(),1920,1080)
            await { image.drawable is BitmapDrawable }
            val bitmap=(image.drawable as BitmapDrawable).bitmap
            assertTrue(bitmap.width*bitmap.height<=230400)
            assertEquals(16.0/9,bitmap.width.toDouble()/bitmap.height,.01)
            assertTrue(backend.cache.size()>0)
            activity.application.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
            assertEquals(0,backend.cache.size())
            assertFalse(bitmap.isRecycled)
            assertEquals(Color.BLUE,bitmap.getPixel(0,0))
            loader.load(image,server.url("/hero.png").toString(),1920,1080)
            assertSame(bitmap,(image.drawable as BitmapDrawable).bitmap)
            assertEquals(1,server.requestCount)
        } finally { loader.close();scope.cancel();backend.close();server.shutdown();controller.pause().stop().destroy() }
    }
    @Test fun recyclingTheLastConsumerInterruptsASlowBodyAndLetsTheNextCoverStart() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup(); val activity=controller.get()
        val server=MockWebServer().apply { start() }
        val blue=png(Color.BLUE)
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest)=MockResponse().setBody(Buffer().write(blue)).apply {
                if(request.path=="/slow.png") throttleBody(1,2,TimeUnit.SECONDS)
            }
        }
        val bodyStarted=CountDownLatch(1)
        val client=OkHttpClient.Builder().eventListener(object: okhttp3.EventListener() {
            override fun responseBodyStart(call: okhttp3.Call) { bodyStarted.countDown() }
        }).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val backend=ArtworkBackend(activity,ArtworkPolicy(4*1024*1024,1),client)
        val loader=PosterLoader(activity,scope,backend)
        try {
            val image=ImageView(activity)
            loader.load(image,server.url("/slow.png").toString(),192,320)
            await { bodyStarted.count==0L }
            assertNotNull(server.takeRequest(1,TimeUnit.SECONDS))
            loader.load(image,server.url("/new.png").toString(),192,320)
            val next=server.takeRequest(1,TimeUnit.SECONDS)
            assertNotNull("A cancelled slow body must not hold the only artwork worker",next)
            assertEquals("/new.png",next!!.path)
            await { image.drawable is BitmapDrawable }
            assertEquals(Color.BLUE,(image.drawable as BitmapDrawable).bitmap.getPixel(0,0))
        } finally { loader.close(); scope.cancel(); backend.close(); server.shutdown(); controller.pause().stop().destroy() }
    }
    private fun png(color: Int,width: Int=32,height: Int=48): ByteArray {
        val bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); bitmap.recycle(); stream.toByteArray()
        }
    }
    @Test fun repeatedBindingsShareOneRequestAndCancellingOneViewKeepsTheOtherAlive() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup(); val activity=controller.get()
        val server=MockWebServer().apply { start() }
        val blue=png(Color.BLUE)
        server.enqueue(MockResponse().setBody(Buffer().write(blue)).setBodyDelay(300,TimeUnit.MILLISECONDS))
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val backend=ArtworkBackend(activity,ArtworkPolicy(4*1024*1024,2),OkHttpClient())
        val loader=PosterLoader(activity,scope,backend)
        val first=ImageView(activity)
        val second=object: ImageView(activity) {
            var updates=0
            override fun setImageBitmap(bitmap: Bitmap?) { updates++; super.setImageBitmap(bitmap) }
            override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) { updates++; super.setImageDrawable(drawable) }
        }
        try {
            val url=server.url("/shared.png").toString()
            loader.load(first,url,192,320); loader.load(second,url,192,320)
            await { server.requestCount==1 }
            loader.cancel(first)
            repeat(20) { loader.load(second,url,192,320) }
            await { second.drawable is BitmapDrawable }
            assertNull(first.drawable); assertEquals(1,server.requestCount)
            val bitmap=(second.drawable as BitmapDrawable).bitmap
            val before=second.updates
            repeat(20) { loader.load(second,url,192,320) }
            assertEquals(before,second.updates)
            assertSame(bitmap,(second.drawable as BitmapDrawable).bitmap)
            assertEquals(Color.BLUE,bitmap.getPixel(0,0))
        } finally { loader.close(); scope.cancel(); backend.close(); server.shutdown(); controller.pause().stop().destroy() }
    }

    @Test fun coldCoversRespectParallelismAndDecodeToThumbnailSizeWhileTheUiKeepsRunning() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup(); val activity=controller.get()
        val server=MockWebServer().apply { start() }
        val blue=png(Color.BLUE,720,1080)
        val release=CountDownLatch(1)
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                release.await(5,TimeUnit.SECONDS)
                return MockResponse().setBody(Buffer().write(blue))
            }
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val backend=ArtworkBackend(activity,ArtworkPolicy(4*1024*1024,2),OkHttpClient())
        val loader=PosterLoader(activity,scope,backend)
        val images=List(4) { ImageView(activity) }
        try {
            images.forEachIndexed { i,image -> loader.load(image,server.url("/$i.png").toString(),192,320) }
            await { server.requestCount>=2 }
            assertNotNull(server.takeRequest(1,TimeUnit.SECONDS)); assertNotNull(server.takeRequest(1,TimeUnit.SECONDS))
            assertNull("Only two downloads may occupy the cold-load budget",server.takeRequest(200,TimeUnit.MILLISECONDS))
            var uiRan=false
            android.os.Handler(Looper.getMainLooper()).post { uiRan=true }
            shadowOf(Looper.getMainLooper()).idle(); assertTrue(uiRan)
            release.countDown()
            await { images.all { it.drawable is BitmapDrawable } }
            assertEquals(4,server.requestCount)
            images.forEach {
                val bitmap=(it.drawable as BitmapDrawable).bitmap
                assertTrue(bitmap.width<=192 && bitmap.height<=320)
                assertTrue(bitmap.allocationByteCount<=192*320*4)
                assertEquals(Color.BLUE,bitmap.getPixel(0,0))
            }
        } finally { release.countDown(); loader.close(); scope.cancel(); backend.close(); server.shutdown(); controller.pause().stop().destroy() }
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
            val callbacks=mutableListOf<Boolean>()
            next.load(thumbnail, server.url("/new.png").toString(),notifyPlaceholder=false) { callbacks.add(it) }
            assertEquals(listOf(true),callbacks) // A home shortcut must be pinned once, after its icon is ready.
            assertEquals(Color.BLUE, (thumbnail.drawable as BitmapDrawable).bitmap.getPixel(0,0))
            assertEquals(before, server.requestCount) // The loading screen reuses the catalog's cache.
            next.close()
        } finally {
            loader.close(); scope.cancel(); server.shutdown(); controller.pause().stop().destroy()
        }
    }
}
