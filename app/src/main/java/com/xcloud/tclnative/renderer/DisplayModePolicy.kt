package com.xcloud.tclnative.renderer

data class OutputMode(val id: Int, val width: Int, val height: Int, val hz: Float)

object DisplayModePolicy {
    /** Keep an already-fast 4K mode; request supported native 4K60 instead of 4K24/30 for TV gaming. */
    fun preferred4k60(current: OutputMode, supported: List<OutputMode>): Int? {
        if (current.width == 3840 && current.height == 2160 && current.hz >= 59) return null
        return supported.firstOrNull { it.width == 3840 && it.height == 2160 && it.hz in 59f..61f }?.id
    }
}
