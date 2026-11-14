package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.LruCache
import android.widget.ImageView
import com.xcloud.tclnative.net.awaitResponse
import com.xcloud.tclnative.settings.DevicePerformancePolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** UI bindings reuse identical requests; background work is shared and cancelled after the last consumer. */
internal class PosterLoader(context: Context, private val scope: CoroutineScope,
                            private val backend: ArtworkBackend = ArtworkBackend.get(context), frameBound: Boolean=false,
                            maxConcurrent: Int?=null) {
    private class Binding(val request: ArtworkRequest, var onReady: (Boolean) -> Unit) {
        var job: Job? = null
        var ready=false
        var queued=false
    }
    private val bindings=WeakHashMap<ImageView,Binding>()
    private var paused=false
    private val loads=maxConcurrent?.let { Semaphore(it.coerceAtLeast(1)) }
    private val deliveries=if(frameBound) ArtworkFrameQueue() else null
    fun pause() {
        paused=true
        bindings.forEach { (view,binding) -> binding.job?.cancel(); view.setImageDrawable(null) }
        bindings.clear(); deliveries?.clear(); backend.cache.evictAll()
    }
    fun resume() { paused=false }
    fun load(view: ImageView, originalUrl: String?, maxWidth: Int = 720, maxHeight: Int = 1080,
             notifyPlaceholder: Boolean = true, onReady: (Boolean) -> Unit = {}) {
        if(paused || originalUrl==null) { cancel(view); onReady(false); return }
        val size=backend.policy.sizeFor(maxWidth,maxHeight)
        val request=ArtworkRequest.create(originalUrl,size.width,size.height)
        val previous=bindings[view]
        if(previous?.request==request) {
            previous.onReady=onReady
            if(previous.ready) { onReady(true); return }
            if(previous.queued || previous.job?.isActive==true) { if(notifyPlaceholder) onReady(false); return }
        }
        bindings.remove(view)?.job?.cancel()
        val binding=Binding(request,onReady)
        bindings[view]=binding
        backend.cache.get(request)?.let {
            deliver(view,binding,it); return
        }
        view.setImageDrawable(null)
        if(notifyPlaceholder) onReady(false)
        binding.job=scope.launch {
            suspend fun receive() {
                val shared=backend.acquire(request)
                try {
                    val bitmap=shared.result.await()
                    if(isActive && bindings[view]===binding) {
                        if(bitmap!=null) deliver(view,binding,bitmap) else binding.onReady(false)
                    }
                } finally {
                    backend.release(request,shared)
                }
            }
            try { loads?.withPermit { receive() } ?: receive() }
            finally { binding.job=null }
        }
    }
    private fun deliver(view: ImageView,binding: Binding,bitmap: Bitmap) {
        binding.queued=true
        val action={
            if(!paused && bindings[view]===binding) {
                binding.queued=false; binding.ready=true; view.setImageBitmap(bitmap); binding.onReady(true)
            }
        }
        deliveries?.post(view,action) ?: action()
    }
    fun cancel(view: ImageView) { bindings.remove(view)?.job?.cancel(); deliveries?.remove(view); view.setImageDrawable(null) }
    /** Drop queued/in-flight placeholders so a newly focused preview can take the next slot. */
    fun cancelPending() {
        bindings.entries.toList().filter { !it.value.ready }.forEach { (view,binding) ->
            bindings.remove(view);binding.job?.cancel();deliveries?.remove(view)
        }
    }
    fun close() { bindings.values.toList().forEach { it.job?.cancel() }; bindings.clear(); deliveries?.clear() }
}

/** One bounded HTTP/memory cache and background dispatcher for catalog and loading-screen images. */
internal class ArtworkBackend(context: Context,
                              val policy: ArtworkPolicy = devicePolicy(context),
                              private val client: OkHttpClient = OkHttpClient.Builder()
                                  .cache(Cache(File(context.cacheDir,"game_art"),64L*1024*1024))
                                  .callTimeout(12,TimeUnit.SECONDS).build(),
                              private val decode: (File,ArtworkSize) -> Bitmap?=ArtworkImageDecoder::decode) : ComponentCallbacks2 {
    private val application=context.applicationContext
    val cache=object : LruCache<ArtworkRequest,Bitmap>(policy.memoryCacheBytes) {
        override fun sizeOf(key: ArtworkRequest,value: Bitmap)=value.allocationByteCount
    }
    init { application.registerComponentCallbacks(this) }
    override fun onConfigurationChanged(newConfig: Configuration) = Unit
    override fun onLowMemory() { cache.evictAll() }
    override fun onTrimMemory(level: Int) {
        // Evict references only: a displayed/shared bitmap must never be recycled here.
        when {
            level>=ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> cache.evictAll()
            level>=ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> cache.trimToSize(cache.size()/2)
        }
    }
    private val workerId=AtomicInteger()
    private val dispatcher=Executors.newFixedThreadPool(policy.parallelism) { runnable ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); runnable.run() },
            "xCloud-Art-${workerId.incrementAndGet()}").apply { isDaemon=true }
    }.asCoroutineDispatcher()
    private val work=CoroutineScope(SupervisorJob()+dispatcher)
    private val slots=Semaphore(policy.parallelism)
    internal class Shared {
        lateinit var result: Deferred<Bitmap?>
        var consumers=0
        @Volatile var call: Call?=null
    }
    private val inFlight=mutableMapOf<ArtworkRequest,Shared>()
    @Synchronized fun acquire(request: ArtworkRequest): Shared {
        val shared=inFlight.getOrPut(request) {
            Shared().also { entry -> entry.result=work.async(start=CoroutineStart.LAZY) {
                slots.withPermit { cache.get(request) ?: fetch(request,entry) }
            } }
        }
        shared.consumers++; shared.result.start()
        return shared
    }
    @Synchronized fun release(request: ArtworkRequest,shared: Shared) {
        if(inFlight[request]!==shared) return
        if(--shared.consumers==0) {
            inFlight.remove(request); shared.result.cancel()
            shared.call?.cancel() // Also interrupts body reads after awaitResponse has returned headers.
        }
    }
    private suspend fun fetch(request: ArtworkRequest,shared: Shared): Bitmap? {
      var temporary: File?=null
      var owned: Bitmap?=null
      return try {
        val call=client.newCall(Request.Builder().url(request.url).build())
        shared.call=call
        currentCoroutineContext().ensureActive()
        call.awaitResponse().use responseBody@ { response ->
            if(!response.isSuccessful) return@responseBody null
            val body=response.body ?: return@responseBody null
            if(body.contentLength()>MAX_IMAGE_BYTES) return@responseBody null
            val directory=File(application.cacheDir,"artwork_pending").apply { mkdirs() }
            val file=File.createTempFile("image-",".tmp",directory).also { temporary=it }
            body.byteStream().use { input -> file.outputStream().use { output ->
                val buffer=ByteArray(64*1024)
                var total=0L
                while(true) {
                    currentCoroutineContext().ensureActive()
                    val count=input.read(buffer)
                    if(count<0) break
                    total+=count
                    if(total>MAX_IMAGE_BYTES) return@responseBody null
                    output.write(buffer,0,count)
                }
            } }
            currentCoroutineContext().ensureActive()
            val fitted=decode(file,request.size)?.also { owned=it } ?: return@responseBody null
            currentCoroutineContext().ensureActive()
            cache.put(request,fitted)
            owned=null // Cache and visible consumers now own this bitmap; never recycle it below.
            fitted
        }
    } catch(cancelled: CancellationException) { throw cancelled }
      catch(_: OutOfMemoryError) { cache.evictAll();null }
      catch(_: Exception) { null }
      finally { owned?.recycle();temporary?.delete();shared.call?.cancel();shared.call=null }
    }

    @Synchronized internal fun close() {
        application.unregisterComponentCallbacks(this)
        inFlight.values.forEach { it.call?.cancel() }
        work.cancel(); dispatcher.close(); client.cache?.close()
    }
    companion object {
        private const val MAX_IMAGE_BYTES=8L*1024*1024
        private fun devicePolicy(context: Context): ArtworkPolicy {
            val manager=context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            return ArtworkPolicy.forDevice(manager.memoryClass,manager.isLowRamDevice,Runtime.getRuntime().availableProcessors(),
                DevicePerformancePolicy.read(context).constrainedMemory)
        }
        @Volatile private var instance: ArtworkBackend?=null
        fun get(context: Context)=instance ?: synchronized(this) {
            instance ?: ArtworkBackend(context.applicationContext).also { instance=it }
        }
    }
}
