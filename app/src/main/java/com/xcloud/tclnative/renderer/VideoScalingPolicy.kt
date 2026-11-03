package com.xcloud.tclnative.renderer

import com.xcloud.tclnative.settings.VideoAspect
import kotlin.math.roundToInt

data class VideoViewport(val width: Int, val height: Int)

object VideoScalingPolicy {
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
