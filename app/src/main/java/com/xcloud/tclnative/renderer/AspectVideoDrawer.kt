package com.xcloud.tclnative.renderer

import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon
import org.webrtc.ImageAdjustmentDrawer
import org.webrtc.FsrUpscaler

/** Removes only EglRenderer's centered aspect crop; decoder transforms/rotation remain intact. */
object AspectTransform {
    fun undoCrop(input: FloatArray, sourceAspect: Float, layoutAspect: Float, output: FloatArray) {
        val sx=if(sourceAspect>layoutAspect) layoutAspect/sourceAspect else 1f
        val sy=if(sourceAspect>layoutAspect) 1f else sourceAspect/layoutAspect
        if(!sx.isFinite() || !sy.isFinite() || sx<=0 || sy<=0) { input.copyInto(output); return }
        val tx=(1f-1f/sx)/2f
        val ty=(1f-1f/sy)/2f
        for(row in 0..3) {
            output[row]=input[row]/sx
            output[4+row]=input[4+row]/sy
            output[8+row]=input[8+row]
            output[12+row]=input[12+row]+input[row]*tx+input[4+row]*ty
        }
    }
}

/** The default path remains stock WebRTC; FSR allocates its passes only after an explicit opt-in. */
class AspectVideoDrawer(@Volatile var stretch: Boolean,
    private val fsrFactory: (() -> FsrUpscaler)? = null,
    private val delegate: RendererCommon.GlDrawer = GlRectDrawer(),
    private val onFsrFailure: (String) -> Unit = {}) : RendererCommon.GlDrawer {
    @Volatile var sourceAspect = 16f/9
    @Volatile var layoutAspect = 16f/9
    @Volatile var sourceWidth=1920
    @Volatile var sourceHeight=1080
    @Volatile var sharpness=0
    @Volatile var saturation=100
    @Volatile var brightness=0
    @Volatile var contrast=100
    @Volatile var fsrEnabled=false
    @Volatile var fsrSharpness=50
    @Volatile private var fsr: FsrUpscaler?=null
    private var adjusted: ImageAdjustmentDrawer? = null
    private var failureReported=false
    private fun checkFailure() {
        if(!failureReported) fsr?.failureReason()?.let { failureReported=true;onFsrFailure(it) }
    }
    fun fsrDiagnostics(): String = if(!fsrEnabled) "AMD FSR 1.0.2: desligado"
        else fsr?.diagnostics() ?: "AMD FSR 1.0.2: solicitado; aguardando vídeo"
    private fun upscaler(width: Int,height: Int): FsrUpscaler? {
        if(!fsrEnabled) { fsr?.release();fsr=null;return null }
        return (fsr ?: fsrFactory?.invoke()?.also { fsr=it })?.also {
            it.configure(if(stretch) sourceWidth else width,if(stretch) sourceHeight else height,fsrSharpness,saturation)
            it.setColorAdjustments(brightness,contrast)
        }
    }
    private fun drawer(width: Int,height: Int): RendererCommon.GlDrawer {
        if(sharpness==0 && saturation==100 && brightness==0 && contrast==100) return delegate
        return (adjusted ?: ImageAdjustmentDrawer().also { adjusted=it }).also {
            it.configure(sharpness,saturation,if(stretch) sourceWidth else width,if(stretch) sourceHeight else height,brightness,contrast)
        }
    }
    private val transformed=FloatArray(16)
    private fun matrix(input: FloatArray): FloatArray {
        if(!stretch) return input
        AspectTransform.undoCrop(input,sourceAspect,layoutAspect,transformed)
        return transformed
    }
    override fun drawOes(texture: Int, texMatrix: FloatArray, frameWidth: Int, frameHeight: Int,
                         x: Int, y: Int, width: Int, height: Int) {
        val m=matrix(texMatrix)
        if(upscaler(frameWidth,frameHeight)?.drawOes(texture,m,frameWidth,frameHeight,x,y,width,height)==true) return
        checkFailure()
        drawer(frameWidth,frameHeight).drawOes(texture,m,frameWidth,frameHeight,x,y,width,height)
    }
    override fun drawRgb(texture: Int, texMatrix: FloatArray, frameWidth: Int, frameHeight: Int,
                         x: Int, y: Int, width: Int, height: Int) {
        val m=matrix(texMatrix)
        if(upscaler(frameWidth,frameHeight)?.drawRgb(texture,m,frameWidth,frameHeight,x,y,width,height)==true) return
        checkFailure()
        drawer(frameWidth,frameHeight).drawRgb(texture,m,frameWidth,frameHeight,x,y,width,height)
    }
    override fun drawYuv(textures: IntArray, texMatrix: FloatArray, frameWidth: Int, frameHeight: Int,
                         x: Int, y: Int, width: Int, height: Int) {
        val m=matrix(texMatrix)
        if(upscaler(frameWidth,frameHeight)?.drawYuv(textures,m,frameWidth,frameHeight,x,y,width,height)==true) return
        checkFailure()
        drawer(frameWidth,frameHeight).drawYuv(textures,m,frameWidth,frameHeight,x,y,width,height)
    }
    override fun release() {
        try { fsr?.release() } finally {
            fsr=null
            try { adjusted?.release() } finally { adjusted=null;delegate.release() }
        }
    }
}
