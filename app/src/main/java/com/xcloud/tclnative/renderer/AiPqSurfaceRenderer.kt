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

    init { setBackgroundColor(Color.BLACK); keepScreenOn = true }

    /** A new renderer has a new first-frame event for every session, including retries. */
    fun prepareForSession() {
        check(!released)
        detachTrack()
        generation++
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
    fun release() {
        if (released) return
        clearSession()
        released = true
        renderer?.release()
        renderer = null
        eglBase.release()
    }
}
