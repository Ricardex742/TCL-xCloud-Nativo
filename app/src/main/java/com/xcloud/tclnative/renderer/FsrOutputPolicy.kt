package com.xcloud.tclnative.renderer

import com.xcloud.tclnative.settings.FsrOutput
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Buffer pixels are independent from Android UI/touch pixels. No stream or display-mode changes. */
object FsrOutputPolicy {
    fun target(output: FsrOutput,windowWidth: Int,windowHeight: Int,viewport: VideoViewport,
               physicalWidth: Int,physicalHeight: Int,isTv: Boolean,fullScreen: Boolean=true): VideoViewport? {
        if(windowWidth<=0 || windowHeight<=0 || viewport.width<=0 || viewport.height<=0) return null
        var scale=1.0
        if(physicalWidth>0 && physicalHeight>0) {
            val long=max(physicalWidth,physicalHeight).toDouble()
            val short=min(physicalWidth,physicalHeight).toDouble()
            val pw=if(windowWidth>=windowHeight) long else short
            val ph=if(windowWidth>=windowHeight) short else long
            val available=min(pw/windowWidth,ph/windowHeight)
            scale=if(output!=FsrOutput.AUTO && isTv && fullScreen) available else min(1.0,available)
        }
        val width=viewport.width*scale
        val height=viewport.height*scale
        val automaticTv=output==FsrOutput.AUTO && isTv
        val longCap=when { automaticTv -> 1920.0;output==FsrOutput.QHD_1440 -> 2560.0;else -> 3840.0 }
        val shortCap=when { automaticTv -> 1080.0;output==FsrOutput.QHD_1440 -> 1440.0;else -> 2160.0 }
        val cap=min(1.0,min(longCap/max(width,height),shortCap/min(width,height)))
        return VideoViewport((width*cap).roundToInt().coerceAtLeast(1),(height*cap).roundToInt().coerceAtLeast(1))
    }
}
