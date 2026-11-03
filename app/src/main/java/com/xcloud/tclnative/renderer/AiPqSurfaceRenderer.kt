package com.xcloud.tclnative.renderer

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import org.webrtc.VideoSink
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

class AiPqSurfaceRenderer(context: Context) : FrameLayout(context) {
    val eglBase: EglBase = EglBase.create()
    var onFirstFrameRendered: (() -> Unit)? = null
    var onRenderError: ((Exception) -> Unit)? = null
    private var currentTrack: VideoTrack? = null
    private var renderer: SurfaceViewRenderer? = null
    private var trackSink: VideoSink? = null
    @Volatile private var generation = 0
    private val lastDeliveryMs = AtomicLong(Long.MIN_VALUE)
    private val presentedToken = AtomicLong()
    private val probePending = AtomicBoolean()
    data class VideoHealth(val presentedToken: Long, val deliveredRecently: Boolean)

    /** One zero-scale callback per watchdog tick: no bitmap allocation or GPU pixel readback. */
    fun probeVideoHealth(): VideoHealth {
        val id = generation
        val view = renderer
        if (view != null && probePending.compareAndSet(false, true)) {
            view.addFrameListener({
                if (generation == id && !released) {
                    presentedToken.incrementAndGet()
                    probePending.set(false)
                }
            }, 0f)
        }
        val delivered = lastDeliveryMs.get()
        return VideoHealth(presentedToken.get(), delivered != Long.MIN_VALUE &&
            android.os.SystemClock.elapsedRealtime() - delivered < 2000)
    }

    /** Keep the same remote track, peer connection, audio and Xbox allocation. UI thread only. */
    fun rebuildSurface() {
        if (released) return
        val track = currentTrack
        replaceRenderer()
        if (track != null) attachTrackNow(track)
    }
    @Volatile private var released = false
    var minimalPostProcessingRequested = false
    private var frameRateHintRequested = false
    private var videoWidth = 1920
    private var videoHeight = 1080
    var nativeUpscaling = true
        set(value) {
            field = value
            renderer?.setEnableHardwareScaler(value)
            updateVideoLayout()
        }

    init { setBackgroundColor(Color.BLACK); keepScreenOn = true }

    /** A new renderer has a new first-frame event for every session, including retries. */
    fun prepareForSession(expectedWidth: Int = 1920, expectedHeight: Int = 1080) {
        check(!released)
        videoWidth = expectedWidth; videoHeight = expectedHeight
        lastDeliveryMs.set(Long.MIN_VALUE)
        presentedToken.set(0)
        replaceRenderer()
    }
    private fun replaceRenderer() {
        detachTrack()
        generation++
        probePending.set(false)
        frameRateHintRequested = false
        renderer?.let { removeView(it); it.release() }
        val id = generation
        renderer = SurfaceViewRenderer(context).also { view ->
            view.init(eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() {
                    post { if (!released && generation == id) onFirstFrameRendered?.invoke() }
                }
                override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                    post { if (!released && generation == id) {
                        videoWidth = if (rotation % 180 == 0) width else height
                        videoHeight = if (rotation % 180 == 0) height else width
                        updateVideoLayout()
                    } }
                }
            })
            view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            view.setEnableHardwareScaler(nativeUpscaling)
            view.disableFpsReduction()
            view.holder.addCallback(object : android.view.SurfaceHolder.Callback {
                private fun frameRate(holder: android.view.SurfaceHolder) {
                    if (!released && generation == id && android.os.Build.VERSION.SDK_INT >= 30 && holder.surface.isValid) {
                        frameRateHintRequested = runCatching {
                            holder.surface.setFrameRate(60f, android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
                        }.isSuccess
                    }
                }
                override fun surfaceCreated(holder: android.view.SurfaceHolder) { frameRate(holder) }
                override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) { frameRate(holder) }
                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) { if (generation == id) frameRateHintRequested = false }
            })
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        updateVideoLayout()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        updateVideoLayout()
    }
    private fun updateVideoLayout() {
        val view = renderer ?: return
        val viewport = VideoScalingPolicy.viewport(width,height,videoWidth,videoHeight,nativeUpscaling)
        val targetWidth = if (nativeUpscaling) LayoutParams.MATCH_PARENT else viewport.width
        val targetHeight = if (nativeUpscaling) LayoutParams.MATCH_PARENT else viewport.height
        val old = view.layoutParams as? LayoutParams
        if (old == null || old.width != targetWidth || old.height != targetHeight || old.gravity != Gravity.CENTER) {
            view.layoutParams = LayoutParams(targetWidth,targetHeight,Gravity.CENTER)
        }
    }
    fun attachVideoTrack(track: VideoTrack) {
        val id = generation
        post {
            if (!released && generation == id) attachTrackNow(track)
        }
    }
    private fun attachTrackNow(track: VideoTrack) {
        if (currentTrack === track) return
        detachTrack()
        val view = renderer ?: return
        val id = generation
        val sink = VideoSink { frame ->
            if (generation == id && !released) {
                lastDeliveryMs.set(android.os.SystemClock.elapsedRealtime())
                view.onFrame(frame)
            }
        }
        try {
            track.addSink(sink)
            trackSink = sink
            currentTrack = track
        } catch (e: Exception) { onRenderError?.invoke(e) }
    }
    fun detachTrack() {
        trackSink?.let { sink -> currentTrack?.removeSink(sink) }
        trackSink = null
        currentTrack = null
    }
    fun clearSession() {
        if (released) return
        generation++
        detachTrack()
        renderer?.clearImage()
        onFirstFrameRendered = null
        onRenderError = null
    }
    /** Android exposes surface/display geometry, but no public API reports TCL AiPQ activation. */
    fun diagnostics(): String {
        val surface = renderer?.holder?.surfaceFrame
        val size = if (surface != null && surface.width() > 0 && surface.height() > 0)
            "${surface.width()}x${surface.height()}" else "n/d"
        val mode = display?.mode
        val output = mode?.let { "${it.physicalWidth}x${it.physicalHeight}@${kotlin.math.round(it.refreshRate).toInt()} Hz" } ?: "n/d"
        return "Surface $size → janela ${width}x$height | Saída Android: $output\n" +
            "Escala da Surface: ${if (nativeUpscaling) "HW solicitada" else "desativada, sem ampliação na janela"} | Cadência 60 Hz: ${if (frameRateHintRequested) "solicitada" else "n/d"} | Pós-processamento mínimo: ${if (minimalPostProcessingRequested) "solicitado" else "padrão"}\n" +
            "AiPQ: não verificável pelo app"
    }

    fun compactDiagnostics(): String {
        val surface = renderer?.holder?.surfaceFrame
        val mode = display?.mode
        return "Surface ${surface?.width() ?: 0}x${surface?.height() ?: 0} → saída Android " +
            (mode?.let { "${it.physicalWidth}x${it.physicalHeight}@${kotlin.math.round(it.refreshRate).toInt()}Hz" } ?: "n/d")
    }

    fun release() {
        if (released) return
        clearSession()
        released = true
        renderer?.release()
        renderer = null
        eglBase.release()
    }
}
