package com.xcloud.tclnative.renderer

import android.content.Context
import android.graphics.Color
import android.widget.FrameLayout
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class AiPqSurfaceRenderer(context: Context) : FrameLayout(context) {
    val eglBase: EglBase = EglBase.create()
    var onFirstFrameRendered: (() -> Unit)? = null
    var onRenderError: ((Exception) -> Unit)? = null
    private var currentTrack: VideoTrack? = null
    private var renderer: SurfaceViewRenderer? = null
    private var generation = 0
    private var released = false
    var minimalPostProcessingRequested = false
    private var frameRateHintRequested = false

    init { setBackgroundColor(Color.BLACK); keepScreenOn = true }

    /** A new renderer has a new first-frame event for every session, including retries. */
    fun prepareForSession() {
        check(!released)
        detachTrack()
        generation++
        frameRateHintRequested = false
        renderer?.let { removeView(it); it.release() }
        val id = generation
        renderer = SurfaceViewRenderer(context).also { view ->
            view.init(eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() {
                    post { if (!released && generation == id) onFirstFrameRendered?.invoke() }
                }
                override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {}
            })
            view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            view.setEnableHardwareScaler(true)
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
    }
    fun attachVideoTrack(track: VideoTrack) {
        val id = generation
        post {
            if (!released && generation == id) {
                detachTrack()
                val sink = renderer ?: return@post
                try {
                    track.addSink(sink)
                    currentTrack = track
                } catch (e: Exception) { onRenderError?.invoke(e) }
            }
        }
    }
    fun detachTrack() {
        val sink = renderer
        if (sink != null) currentTrack?.removeSink(sink)
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
            "Escala da Surface: HW solicitada | Cadência 60 Hz: ${if (frameRateHintRequested) "solicitada" else "n/d"} | Pós-processamento mínimo: ${if (minimalPostProcessingRequested) "solicitado" else "padrão"}\n" +
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
