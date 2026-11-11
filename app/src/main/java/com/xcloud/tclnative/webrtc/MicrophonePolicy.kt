package com.xcloud.tclnative.webrtc

object MicrophonePolicy {
    fun canCapture(enabled: Boolean,supported: Boolean,negotiated: Boolean,foreground: Boolean,permission: Boolean): Boolean =
        enabled && supported && negotiated && foreground && permission
}
