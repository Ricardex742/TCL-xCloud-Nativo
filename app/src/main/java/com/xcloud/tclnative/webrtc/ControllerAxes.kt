package com.xcloud.tclnative.webrtc

/** Axis ranges are reported by InputDevice. Flat is intentionally ignored for camera and triggers. */
data class ControllerAxisRange(val min: Float, val max: Float, val flat: Float = 0f)

object ControllerAxes {
    const val RIGHT_DEADZONE = 0f
    const val TRIGGER_DEADZONE = 0f

    fun stick(raw: Float, range: ControllerAxisRange? = null, useHardwareDeadzone: Boolean = false): Float {
        if (!raw.isFinite()) return 0f
        val min = range?.min ?: -1f
        val max = range?.max ?: 1f
        if (!min.isFinite() || !max.isFinite() || min >= max) return raw.coerceIn(-1f, 1f)
        val center = if (min < 0 && max > 0) 0f else (min + max) / 2
        if (useHardwareDeadzone && kotlin.math.abs(raw - center) <= (range?.flat ?: 0f).coerceAtLeast(0f)) return 0f
        val span = if (raw >= center) max - center else center - min
        return if (span > 0) ((raw - center) / span).coerceIn(-1f, 1f) else 0f
    }

    fun trigger(raw: Float, range: ControllerAxisRange? = null): Float {
        if (!raw.isFinite()) return 0f
        val min = range?.min ?: 0f
        val max = range?.max ?: 1f
        if (!min.isFinite() || !max.isFinite() || min >= max) return raw.coerceIn(0f, 1f)
        return ((raw - min) / (max - min)).coerceIn(0f, 1f)
    }
}
