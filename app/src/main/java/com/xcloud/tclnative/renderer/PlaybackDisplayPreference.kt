package com.xcloud.tclnative.renderer

import android.view.Window

/** Holds a window request only while playing. Android still controls the actual mode. */
class PlaybackDisplayPreference(private val window: Window) {
    private data class Original(val modeId: Int, val refreshRate: Float)
    private var original: Original? = null

    fun request(modeId: Int?) {
        if (modeId == null) { restore(); return }
        val attrs = window.attributes
        if (original == null) original = Original(attrs.preferredDisplayModeId, attrs.preferredRefreshRate)
        window.attributes = attrs.apply {
            preferredDisplayModeId = modeId
            preferredRefreshRate = 0f
        }
    }

    fun restore() {
        val saved = original ?: return
        original = null
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = saved.modeId
            preferredRefreshRate = saved.refreshRate
        }
    }
}
