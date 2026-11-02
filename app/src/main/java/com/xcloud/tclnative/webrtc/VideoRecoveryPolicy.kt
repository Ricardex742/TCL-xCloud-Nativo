package com.xcloud.tclnative.webrtc

enum class VideoRecoveryAction { NONE, REQUEST_KEYFRAME, RESET_DECODER, REBUILD_SURFACE, OFFER_RETRY }

/** A frozen picture is missing frame progress, not an unchanged image or a temporarily low FPS. */
class VideoRecoveryPolicy {
    private var lastProgressMs: Long? = null
    private var lastFrameToken = -1L
    private var stage = 0
    private var lastRequestMs = Long.MIN_VALUE
    fun observe(nowMs: Long, presentedFrameToken: Long, deliveredRecently: Boolean): VideoRecoveryAction {
        if (lastProgressMs == null || presentedFrameToken != lastFrameToken) {
            lastProgressMs = nowMs; lastFrameToken = presentedFrameToken; stage = 0
            lastRequestMs = Long.MIN_VALUE
            return VideoRecoveryAction.NONE
        }
        val quiet = nowMs - lastProgressMs!!
        if (quiet < 3500) return VideoRecoveryAction.NONE
        if (stage == 0) {
            stage = 1; lastRequestMs = nowMs
            return VideoRecoveryAction.REQUEST_KEYFRAME
        }
        if (quiet >= 8000 && stage == 1) {
            stage = 2; lastRequestMs = nowMs
            return if (deliveredRecently) VideoRecoveryAction.REBUILD_SURFACE else VideoRecoveryAction.RESET_DECODER
        }
        if (quiet >= 16_000 && stage == 2) {
            stage = 3; lastRequestMs = nowMs
            return if (deliveredRecently) VideoRecoveryAction.REBUILD_SURFACE else VideoRecoveryAction.RESET_DECODER
        }
        if (quiet >= 30_000 && stage == 3) {
            stage = 4
            return VideoRecoveryAction.OFFER_RETRY
        }
        if (stage < 4 && nowMs - lastRequestMs >= 5000) {
            lastRequestMs = nowMs
            return VideoRecoveryAction.REQUEST_KEYFRAME
        }
        return VideoRecoveryAction.NONE
    }
    fun retry(nowMs: Long) { lastProgressMs = nowMs - 3500; stage = 0; lastRequestMs = Long.MIN_VALUE }
}
