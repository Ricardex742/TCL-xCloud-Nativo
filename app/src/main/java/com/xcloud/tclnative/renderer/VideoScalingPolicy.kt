package com.xcloud.tclnative.renderer

import com.xcloud.tclnative.settings.VideoAspect
import kotlin.math.roundToInt

data class VideoViewport(val width: Int, val height: Int)

object VideoScalingPolicy {
    fun touchCoordinates(windowWidth: Int,windowHeight: Int,videoWidth: Int,videoHeight: Int,upscale: Boolean,
                         aspect: VideoAspect,x: Float,y: Float): Pair<Float,Float>? {
        if(windowWidth<=0 || windowHeight<=0 || videoWidth<=0 || videoHeight<=0) return null
        val viewport=viewport(windowWidth,windowHeight,videoWidth,videoHeight,upscale,aspect)
        var u=(x-(windowWidth-viewport.width)/2f)/viewport.width
        var v=(y-(windowHeight-viewport.height)/2f)/viewport.height
        if(!u.isFinite() || !v.isFinite() || u !in 0f..1f || v !in 0f..1f) return null
        if(aspect==VideoAspect.ZOOM) {
            val source=videoWidth.toFloat()/videoHeight
            val layout=viewport.width.toFloat()/viewport.height
            if(source>layout) u=.5f+(u-.5f)*(layout/source) else v=.5f+(v-.5f)*(source/layout)
        }
        return u to v
    }
    /** Disabled means no enlargement in the application's pixel viewport; larger input can still fit down. */
    fun viewport(windowWidth: Int, windowHeight: Int, videoWidth: Int, videoHeight: Int, upscale: Boolean,
                 aspect: VideoAspect = VideoAspect.ORIGINAL): VideoViewport {
        if (windowWidth <= 0 || windowHeight <= 0) return VideoViewport(1,1)
        if (videoWidth <= 0 || videoHeight <= 0) return VideoViewport(1,1)
        val ratio=aspect.ratio ?: (videoWidth.toDouble()/videoHeight)
        var width=windowWidth.toDouble()
        var height=windowHeight.toDouble()
        if (aspect != VideoAspect.STRETCH && aspect != VideoAspect.ZOOM) {
            width=minOf(width,height*ratio)
            height=width/ratio
        }
        val limit=if(upscale) 1.0 else minOf(1.0,videoWidth/width,videoHeight/height)
        return VideoViewport((width*limit).roundToInt().coerceAtLeast(1),(height*limit).roundToInt().coerceAtLeast(1))
    }
}
