package com.xcloud.tclnative.renderer

data class OutputMode(val id: Int, val width: Int, val height: Int, val hz: Float)

object DisplayModePolicy {
    /** Match the stream cadence without changing the phone's physical resolution. */
    fun preferredCadence(current: OutputMode, supported: List<OutputMode>, fps: Int): Int? {
        if (fps <= 0) return null
        fun compatible(mode: OutputMode): Boolean {
            if (!mode.hz.isFinite() || mode.hz <= 0) return false
            val multiple = kotlin.math.round(mode.hz / fps)
            return multiple >= 1 && kotlin.math.abs(mode.hz - multiple * fps) <= multiple * 0.15f
        }
        if (compatible(current)) return current.id
        return supported.filter {
            it.width == current.width && it.height == current.height && compatible(it)
        }.minByOrNull { it.hz }?.id
    }

    /** Keep an already-fast 4K mode; request supported native 4K60 instead of 4K24/30 for TV gaming. */
    fun preferred4k60(current: OutputMode, supported: List<OutputMode>): Int? {
        if (current.width == 3840 && current.height == 2160 && current.hz >= 59) return null
        return supported.firstOrNull { it.width == 3840 && it.height == 2160 && it.hz in 59f..61f }?.id
    }
}
