package com.xcloud.tclnative.webrtc

import org.webrtc.IceCandidate

interface StreamingPeer {
    suspend fun createOfferSdp(): Pair<String, List<IceCandidate>>
    suspend fun setRemoteAnswer(answerSdp: String)
    suspend fun addRemoteCandidates(candidates: List<IceCandidate>)
    suspend fun awaitReady()
}
