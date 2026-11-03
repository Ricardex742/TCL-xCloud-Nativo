package com.xcloud.tclnative.webrtc

data class ShortcutUpdate(val forwardedButtons: Int, val pulse: Int = 0, val openMenu: Boolean = false)

/** Reserve only Menu/View for a short chord window; gameplay buttons and axes are never delayed. */
class ControllerShortcut {
    private var held = 0
    private var forwarded = 0
    private var pendingAt: Long? = null
    private var consumed = false
    val flushAtMs: Long? get() = pendingAt?.plus(CHORD_WINDOW_MS)

    fun change(button: Int, down: Boolean, nowMs: Long): ShortcutUpdate {
        require(button == START || button == SELECT)
        if (down) {
            if (held and button != 0) return ShortcutUpdate(forwarded)
            if (held == 0) pendingAt = nowMs
            held = held or button
            if (!consumed && held == BOTH && pendingAt?.let { nowMs - it <= CHORD_WINDOW_MS } == true) {
                consumed = true; pendingAt = null; forwarded = 0
                return ShortcutUpdate(0, openMenu = true)
            }
            if (!consumed && pendingAt == null) forwarded = held
            return ShortcutUpdate(forwarded)
        }
        if (held and button == 0) return ShortcutUpdate(forwarded)
        held = held and button.inv()
        if (consumed) {
            if (held == 0) reset()
            return ShortcutUpdate(0)
        }
        val pulse = if (forwarded and button == 0) button else 0
        forwarded = forwarded and button.inv()
        if (held == 0) pendingAt = null
        return ShortcutUpdate(forwarded, pulse)
    }
    fun flush(nowMs: Long): ShortcutUpdate {
        if (!consumed && pendingAt?.let { nowMs - it >= CHORD_WINDOW_MS } == true) {
            forwarded = held; pendingAt = null
        }
        return ShortcutUpdate(forwarded)
    }
    fun reset() { held = 0; forwarded = 0; pendingAt = null; consumed = false }
    companion object {
        const val START = 4
        const val SELECT = 8
        const val BOTH = START or SELECT
        const val CHORD_WINDOW_MS = 150L
    }
}
