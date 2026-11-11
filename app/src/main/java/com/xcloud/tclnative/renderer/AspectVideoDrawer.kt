package com.xcloud.tclnative.renderer

import com.xcloud.tclnative.i18n.UiText

import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon
import org.webrtc.ImageAdjustmentDrawer
import org.webrtc.FsrUpscaler
import org.webrtc.ImageTreatmentPipeline
import com.xcloud.tclnative.settings.ImageTreatmentSettings

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
    private val treatmentFactory: (() -> ImageTreatmentPipeline)? = null,
    private val onTreatmentFailure: (String) -> Unit = {},
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
    @Volatile var sgsrEnabled=false
    @Volatile var reconstructionLevel=0
    @Volatile var reconstructionStrength=50
    @Volatile var imageComparison=0
    @Volatile var treatment=ImageTreatmentSettings()
    @Volatile var onDraw: (() -> Unit)?=null
    @Volatile private var pipeline: ImageTreatmentPipeline?=null
    private var lastTreatment: ImageTreatmentSettings?=null
    private var treatmentOptions=org.webrtc.TreatmentOptions.OFF
    private var treatmentFailureReported=false
    @Volatile var onFsrDraw: (() -> Unit)?=null
    @Volatile private var fsr: FsrUpscaler?=null
    private var adjusted: ImageAdjustmentDrawer? = null
    private var failureReported=false
    private fun checkFailure() {
        if(!failureReported) fsr?.failureReason()?.let { failureReported=true;onFsrFailure(it) }
    }
    fun isFsrBypassed(): Boolean = fsr?.isBypassed()==true
    fun treatmentDiagnostics(): String = pipeline?.diagnostics() ?: "Treatment: off"
    fun effectiveNeural(): Int = pipeline?.effectiveNeural() ?: 0
    fun effectiveTemporal(): Int = pipeline?.effectiveTemporal() ?: 0
    fun fsrDiagnostics(): String = if(reconstructionLevel>0) "xCloud reconstruction: ${pipeline?.diagnostics() ?: "waiting for video"}"
        else if(sgsrEnabled) "SGSR 1: ${pipeline?.diagnostics() ?: "waiting for video"}"
        else if(!fsrEnabled) UiText.text("AMD FSR 1.0.2: desligado")
        else fsr?.diagnostics() ?: UiText.text("AMD FSR 1.0.2: solicitado; aguardando vídeo")
    private fun upscaler(width: Int,height: Int): FsrUpscaler? {
        if(!fsrEnabled) { fsr?.release();fsr=null;return null }
        return (fsr ?: fsrFactory?.invoke()?.also { fsr=it })?.also {
            it.configure(if(stretch) sourceWidth else width,if(stretch) sourceHeight else height,fsrSharpness,saturation)
            it.setColorAdjustments(brightness,contrast)
            it.setTreatmentFinishing(treatment.sharpening.ordinal,treatment.deband>0)
        }
    }
    private fun enhanced(kind: ImageTreatmentPipeline.Input,texture: Int,textures: IntArray?,matrix: FloatArray,
        fw: Int,fh: Int,x: Int,y: Int,w: Int,h: Int,upscaler: FsrUpscaler?): Boolean {
        val value=treatment
        if(!value.active && !sgsrEnabled && reconstructionLevel==0) { pipeline?.release();pipeline=null;return false }
        val engine=pipeline ?: treatmentFactory?.invoke()?.also { pipeline=it } ?: return false
        if(lastTreatment!=value) { lastTreatment=value;treatmentOptions=value.glOptions() }
        engine.configure(treatmentOptions,sharpness,fsrSharpness,saturation,brightness,contrast)
        engine.configureReconstruction(reconstructionLevel,reconstructionStrength,imageComparison)
        val drawn=engine.draw(kind,texture,textures,matrix,fw,fh,if(stretch) sourceWidth else fw,
            if(stretch) sourceHeight else fh,x,y,w,h,upscaler,sgsrEnabled)
        if(drawn && engine.didScaleThisFrame()) onFsrDraw?.invoke()
        if(!treatmentFailureReported) engine.failureReason()?.let { treatmentFailureReported=true;onTreatmentFailure(it) }
        checkFailure()
        return drawn
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
        val f=upscaler(frameWidth,frameHeight)
        if(enhanced(ImageTreatmentPipeline.Input.OES,texture,null,m,frameWidth,frameHeight,x,y,width,height,f)) { onDraw?.invoke();return }
        if(f?.drawOes(texture,m,frameWidth,frameHeight,x,y,width,height)==true) { onFsrDraw?.invoke();onDraw?.invoke();return }
        checkFailure()
        drawer(frameWidth,frameHeight).drawOes(texture,m,frameWidth,frameHeight,x,y,width,height)
        onDraw?.invoke()
    }
    override fun drawRgb(texture: Int, texMatrix: FloatArray, frameWidth: Int, frameHeight: Int,
                         x: Int, y: Int, width: Int, height: Int) {
        val m=matrix(texMatrix)
        val f=upscaler(frameWidth,frameHeight)
        if(enhanced(ImageTreatmentPipeline.Input.RGB,texture,null,m,frameWidth,frameHeight,x,y,width,height,f)) { onDraw?.invoke();return }
        if(f?.drawRgb(texture,m,frameWidth,frameHeight,x,y,width,height)==true) { onFsrDraw?.invoke();onDraw?.invoke();return }
        checkFailure()
        drawer(frameWidth,frameHeight).drawRgb(texture,m,frameWidth,frameHeight,x,y,width,height)
        onDraw?.invoke()
    }
    override fun drawYuv(textures: IntArray, texMatrix: FloatArray, frameWidth: Int, frameHeight: Int,
                         x: Int, y: Int, width: Int, height: Int) {
        val m=matrix(texMatrix)
        val f=upscaler(frameWidth,frameHeight)
        if(enhanced(ImageTreatmentPipeline.Input.YUV,0,textures,m,frameWidth,frameHeight,x,y,width,height,f)) { onDraw?.invoke();return }
        if(f?.drawYuv(textures,m,frameWidth,frameHeight,x,y,width,height)==true) { onFsrDraw?.invoke();onDraw?.invoke();return }
        checkFailure()
        drawer(frameWidth,frameHeight).drawYuv(textures,m,frameWidth,frameHeight,x,y,width,height)
        onDraw?.invoke()
    }
    override fun release() {
        try { pipeline?.release();pipeline=null;fsr?.release() } finally {
            fsr=null
            try { adjusted?.release() } finally { adjusted=null;delegate.release() }
        }
    }
}
