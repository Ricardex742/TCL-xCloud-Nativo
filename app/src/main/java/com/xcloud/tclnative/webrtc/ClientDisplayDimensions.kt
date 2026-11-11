package com.xcloud.tclnative.webrtc

import org.json.JSONObject

/** Presentation characteristics, independent of the server's coordinate space for input. */
data class ClientDisplayDimensions(
    val horizontalMm: Int, val verticalMm: Int,
    val preferredWidth: Int, val preferredHeight: Int
) {
    init { require(horizontalMm > 0 && verticalMm > 0 && preferredWidth > 0 && preferredHeight > 0) }

    fun content() = JSONObject().put("horizontal", horizontalMm).put("vertical", verticalMm)
        .put("preferredWidth", preferredWidth).put("preferredHeight", preferredHeight)
        .put("safeAreaLeft", 0).put("safeAreaTop", 0)
        .put("safeAreaRight", preferredWidth).put("safeAreaBottom", preferredHeight)
        .put("supportsCustomResolution", true)

    companion object {
        /** Android supplies DPI directly; the web SDK estimates millimeters with a CSS 1cm element. */
        fun fromPixels(width: Int, height: Int, videoWidth: Int, videoHeight: Int,
                       dpiX: Float, dpiY: Float, fallbackDpi: Float = 160f): ClientDisplayDimensions {
            require(width > 0 && height > 0 && videoWidth > 0 && videoHeight > 0)
            val fallback = fallbackDpi.takeIf { it.isFinite() && it > 0 } ?: 160f
            fun millimeters(pixels: Int, dpi: Float) =
                (pixels * 25.4 / (dpi.takeIf { it.isFinite() && it > 0 } ?: fallback)).toInt().coerceAtLeast(1)
            return ClientDisplayDimensions(millimeters(videoWidth, dpiX), millimeters(videoHeight, dpiY), width, height)
        }
    }
}
