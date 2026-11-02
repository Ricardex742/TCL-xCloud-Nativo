package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import com.xcloud.tclnative.net.awaitResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

/** Bounded cache/parallelism, cancelled recycled requests and no image work on the UI thread. */
internal class PosterLoader(context: Context, private val scope: CoroutineScope) {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val client = OkHttpClient.Builder().cache(Cache(File(context.cacheDir, "game_art"), 64L*1024*1024))
        .callTimeout(10, TimeUnit.SECONDS).build()
    private val slots = Semaphore(4)
    private val jobs = WeakHashMap<ImageView, Job>()
    fun load(view: ImageView, url: String?, onReady: (Boolean) -> Unit = {}) {
        jobs.remove(view)?.cancel()
        view.setImageDrawable(null)
        onReady(false)
        if (url == null) return
        cache.get(url)?.let { view.setImageBitmap(it); onReady(true); return }
        jobs[view] = scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                slots.withPermit {
                    cache.get(url) ?: try {
                        client.newCall(Request.Builder().url(url).build()).awaitResponse().use { response ->
                            if (!response.isSuccessful) return@use null
                            val source = response.body?.source() ?: return@use null
                            source.request(8L*1024*1024 + 1)
                            if (source.buffer.size > 8L*1024*1024) return@use null
                            val bytes = source.readByteArray()
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            var sample = 1
                            while (bounds.outWidth / sample > 640 || bounds.outHeight / sample > 960) sample *= 2
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                                inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565
                            })?.also { cache.put(url, it) }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                }
            }
            if (isActive) {
                if (bitmap != null) view.setImageBitmap(bitmap)
                onReady(bitmap != null)
            }
        }
    }
    fun cancel(view: ImageView) { jobs.remove(view)?.cancel(); view.setImageDrawable(null) }
    fun close() {
        jobs.values.toList().forEach { it.cancel() }; jobs.clear(); cache.evictAll()
        client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.cache?.close()
    }
}
