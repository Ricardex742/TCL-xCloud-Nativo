package com.xcloud.tclnative.renderer

import android.content.Context
import android.view.SurfaceHolder
import org.webrtc.SurfaceViewRenderer

/** Keep stock WebRTC/EGL rendering; override only holder sizing while an FSR target is selected. */
open class OutputSurfaceViewRenderer(context: Context): SurfaceViewRenderer(context) {
    // The Java superclass calls getHolder() in its constructor. lateinit avoids replacing that proxy.
    private lateinit var sizedHolder: OutputSurfaceHolder
    var onBufferFailure: (() -> Unit)?=null
    override fun getHolder(): SurfaceHolder {
        if(!::sizedHolder.isInitialized) sizedHolder=OutputSurfaceHolder(super.getHolder()) { onBufferFailure?.invoke() }
        return sizedHolder
    }
    fun setOutputBuffer(size: VideoViewport?) { (holder as OutputSurfaceHolder).setOutput(size) }
}

/** WebRTC re-applies setSizeFromLayout on layout/frame changes; retain the requested buffer size. */
internal class OutputSurfaceHolder(private val original: SurfaceHolder,private val failed: () -> Unit): SurfaceHolder by original {
    private var output: VideoViewport?=null
    private var applied: VideoViewport?=null
    private var layoutApplied=false
    fun setOutput(size: VideoViewport?) {
        if(output==size) return
        output=size
        setSizeFromLayout()
    }
    override fun setFixedSize(width: Int,height: Int) {
        val wanted=output ?: VideoViewport(width,height)
        if(!layoutApplied && applied==wanted) return
        try {
            original.setFixedSize(wanted.width,wanted.height)
            applied=wanted;layoutApplied=false
        } catch(_: RuntimeException) {
            output=null;applied=null;layoutApplied=false
            try { original.setSizeFromLayout();layoutApplied=true } catch(_: RuntimeException) { /* Notify once below. */ }
            failed()
        }
    }
    override fun setSizeFromLayout() {
        val wanted=output
        if(wanted!=null) { setFixedSize(wanted.width,wanted.height);return }
        if(!layoutApplied) { original.setSizeFromLayout();applied=null;layoutApplied=true }
    }
}
