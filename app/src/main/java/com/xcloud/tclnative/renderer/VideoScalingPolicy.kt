package com.xcloud.tclnative.renderer

data class VideoViewport(val width: Int, val height: Int)

object VideoScalingPolicy {
    /** Disabled means no enlargement in the application's pixel viewport; larger input can still fit down. */
    fun viewport(windowWidth: Int, windowHeight: Int, videoWidth: Int, videoHeight: Int, upscale: Boolean): VideoViewport {
        if (windowWidth <= 0 || windowHeight <= 0) return VideoViewport(1,1)
        if (upscale) return VideoViewport(windowWidth,windowHeight)
        if (videoWidth <= 0 || videoHeight <= 0) return VideoViewport(1,1)
        val fit = minOf(1.0,windowWidth.toDouble()/videoWidth,windowHeight.toDouble()/videoHeight)
        return VideoViewport((videoWidth*fit).toInt().coerceAtLeast(1),(videoHeight*fit).toInt().coerceAtLeast(1))
    }
}
