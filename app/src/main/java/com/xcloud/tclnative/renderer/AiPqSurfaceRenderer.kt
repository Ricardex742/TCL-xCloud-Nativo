package com.xcloud.tclnative.renderer

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.widget.FrameLayout
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class AiPqSurfaceRenderer(context: Context) : FrameLayout(context) {

    companion object {
        private const val TAG = "AiPqSurfaceRenderer"
        private const val STREAM_WIDTH = 1920
        private const val STREAM_HEIGHT = 1080
    }

    val eglBase: EglBase = EglBase.create()
    val webrtcSurfaceView: SurfaceViewRenderer = SurfaceViewRenderer(context)
    private var currentVideoTrack: VideoTrack? = null

    init {
        setBackgroundColor(Color.BLACK)
        keepScreenOn = true

        // Inicializa o renderizador nativo C++ / OpenGL / Hardware Overlay
        webrtcSurfaceView.init(eglBase.eglBaseContext, null)
        webrtcSurfaceView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)

        // CRUCIAL PARA A TCL P755 (AiPQ 4K) E DISPOSITIVOS FRACOS:
        // Ativa o Hardware Scaler do SurfaceFlinger e fixa a geometria base em 1080p
        webrtcSurfaceView.setEnableHardwareScaler(true)
        webrtcSurfaceView.holder.setFixedSize(STREAM_WIDTH, STREAM_HEIGHT)

        addView(
            webrtcSurfaceView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        Log.i(TAG, "SurfaceViewRenderer Nativo + Hardware Scaler 1080p->4K inicializado!")
    }

    /**
     * Liga o fluxo de vídeo H.264 que chega do servidor Xbox Cloud Gaming diretamente ao ecrã
     */
    fun attachVideoTrack(videoTrack: VideoTrack) {
        post {
            try {
                currentVideoTrack?.removeSink(webrtcSurfaceView)
                currentVideoTrack = videoTrack
                videoTrack.addSink(webrtcSurfaceView)
                Log.i(TAG, "VideoTrack H.264 anexado ao Hardware Overlay com sucesso!")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao anexar VideoTrack: ${e.message}", e)
            }
        }
    }

    fun release() {
        try {
            currentVideoTrack?.removeSink(webrtcSurfaceView)
            webrtcSurfaceView.release()
            eglBase.release()
        } catch (ignored: Exception) {}
    }
}
