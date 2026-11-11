package com.xcloud.tclnative.webrtc

object VideoLatencyPolicy {
    const val MAX_PLAYOUT_MS = 40
    // Present in the pinned native library. Parser: RtpVideoStreamReceiver2, min_ms/max_ms.
    fun fieldTrials(lowLatency: Boolean): String = if (lowLatency)
        "WebRTC-ForcePlayoutDelay/min_ms:0,max_ms:$MAX_PLAYOUT_MS/" else ""
}
