package com.xcloud.tclnative.webrtc

import org.webrtc.IceCandidate

interface StreamingPeer {
    suspend fun createOfferSdp(): Pair<String, List<IceCandidate>>
    /** The SDP POST returned success; this does not yet mean the requested codec was accepted. */
    fun onOfferPosted() {}
    suspend fun setRemoteAnswer(answerSdp: String)
    suspend fun addRemoteCandidates(candidates: List<IceCandidate>)
    fun configureChat(supported: Boolean) {}
    fun configureInput(version: Int) {}
    suspend fun awaitReady()
}
