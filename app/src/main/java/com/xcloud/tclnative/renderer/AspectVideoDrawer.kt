package com.xcloud.tclnative.renderer

import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon
import org.webrtc.ImageAdjustmentDrawer

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

/** Uses the stock WebRTC drawer: one texture matrix correction, no extra render pass/readback. */
class AspectVideoDrawer(@Volatile var stretch: Boolean) : RendererCommon.GlDrawer {
    @Volatile var sourceAspect = 16f/9
    @Volatile var layoutAspect = 16f/9
    @Volatile var sourceWidth=1920
    @Volatile var sourceHeight=1080
    @Volatile var sharpness=0
    @Volatile var saturation=100
    private val delegate=GlRectDrawer()
    private var adjusted: ImageAdjustmentDrawer? = null
    private fun drawer(width: Int,height: Int): RendererCommon.GlDrawer {
        if(sharpness==0 && saturation==100) return delegate
        return (adjusted ?: ImageAdjustmentDrawer().also { adjusted=it }).also {
            it.configure(sharpness,saturation,if(stretch) sourceWidth else width,if(stretch) sourceHeight else height)
        }
    }
    private val transformed=FloatArray(16)
    private fun matrix(input: FloatArray): FloatArray {
        if(!stretch) return input
        AspectTransform.undoCrop(input,sourceAspect,layoutAspect,transformed)
        return transformed
    }
    override fun drawOes(texture: Int, texMatrix: FloatArray, frameWidth: Int, frameHeight: Int,
                         x: Int, y: Int, width: Int, height: Int) =
        drawer(frameWidth,frameHeight).drawOes(texture,matrix(texMatrix),frameWidth,frameHeight,x,y,width,height)
    override fun drawRgb(texture: Int, texMatrix: FloatArray, frameWidth: Int, frameHeight: Int,
                         x: Int, y: Int, width: Int, height: Int) =
        drawer(frameWidth,frameHeight).drawRgb(texture,matrix(texMatrix),frameWidth,frameHeight,x,y,width,height)
    override fun drawYuv(textures: IntArray, texMatrix: FloatArray, frameWidth: Int, frameHeight: Int,
                         x: Int, y: Int, width: Int, height: Int) =
        drawer(frameWidth,frameHeight).drawYuv(textures,matrix(texMatrix),frameWidth,frameHeight,x,y,width,height)
    override fun release() { delegate.release(); adjusted?.release(); adjusted=null }
}
