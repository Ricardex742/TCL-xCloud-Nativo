package com.xcloud.tclnative.webrtc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.nio.ByteBuffer

class XCloudWebRtcClient(
    context: Context,
    private val eglBase: EglBase,
    private val onVideoTrackReady: (VideoTrack) -> Unit,
    private val onConnectionStateChanged: (String) -> Unit
) {
    companion object {
        private const val TAG = "XCloudWebRtcClient"
    }

    private val factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private val gatheredCandidates = mutableListOf<IceCandidate>()
    private var iceGatheringComplete = CompletableDeferred<Unit>()

    private var inputChannel: DataChannel? = null
    private var controlChannel: DataChannel? = null
    private var messageChannel: DataChannel? = null
    private var chatChannel: DataChannel? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)

        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(decoderFactory)
            .setVideoEncoderFactory(encoderFactory)
            .createPeerConnectionFactory()
    }

    suspend fun createOfferSdp(): Pair<String, List<IceCandidate>> {
        gatheredCandidates.clear()
        iceGatheringComplete = CompletableDeferred()

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                Log.i(TAG, "Estado ICE: $newState")
                onConnectionStateChanged(newState?.name ?: "UNKNOWN")
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
                if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                    if (!iceGatheringComplete.isCompleted) {
                        iceGatheringComplete.complete(Unit)
                    }
                }
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null && !candidate.sdp.contains(":")) {
                    val cleanedCandSdp = candidate.sdp
                        .replace(Regex(" network-id \\d+"), "")
                        .replace(Regex(" network-cost \\d+"), "")
                        .replace(Regex(" generation \\d+"), " generation 0")
                    gatheredCandidates.add(
                        IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, cleanedCandSdp)
                    )
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream?) {
                stream?.videoTracks?.firstOrNull()?.let { onVideoTrackReady(it) }
            }

            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) {
                    track.setEnabled(true)
                    onVideoTrackReady(track)
                }
            }
        }) ?: throw Exception("Falha ao instanciar PeerConnection")

        this.peerConnection = pc

        // 1. Transceivers na ordem estrita do protocolo xCloud: Audio (mid:0) e Video (mid:1)
        val recvOnlyInit = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, recvOnlyInit)
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, recvOnlyInit)

        // 2. DataChannels oficiais (mid:2)
        controlChannel = pc.createDataChannel("control", DataChannel.Init().apply {
            ordered = true
            protocol = "controlV1"
        })?.apply {
            registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) {}
                override fun onStateChange() {
                    if (state() == DataChannel.State.OPEN) {
                        sendXboxControlHandshake()
                    }
                }
                override fun onMessage(buffer: DataChannel.Buffer?) {}
            })
        }

        inputChannel = pc.createDataChannel("input", DataChannel.Init().apply {
            ordered = true
            protocol = "1.0"
        })

        messageChannel = pc.createDataChannel("message", DataChannel.Init().apply {
            ordered = true
            protocol = "messageV1"
        })?.apply {
            registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) {}
                override fun onStateChange() {
                    if (state() == DataChannel.State.OPEN) {
                        sendXboxMessageHandshake()
                    }
                }
                override fun onMessage(buffer: DataChannel.Buffer?) {}
            })
        }

        chatChannel = pc.createDataChannel("chat", DataChannel.Init().apply {
            ordered = true
            protocol = "chatV1"
        })

        val offerDeferred = CompletableDeferred<SessionDescription>()
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp != null) offerDeferred.complete(sdp)
                else offerDeferred.completeExceptionally(Exception("SDP Offer nulo"))
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                offerDeferred.completeExceptionally(Exception("Erro createOffer: $error"))
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)

        val offer = offerDeferred.await()

        val setLocalDeferred = CompletableDeferred<Unit>()
        pc.setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onSetSuccess() {
                setLocalDeferred.complete(Unit)
            }
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {
                setLocalDeferred.completeExceptionally(Exception("Erro setLocalDescription: $error"))
            }
        }, offer)

        setLocalDeferred.await()

        withTimeoutOrNull(1200) {
            iceGatheringComplete.await()
        }

        val cleanSdp = sanitizeSdpForXboxBlade(offer.description)
        return Pair(cleanSdp, gatheredCandidates.toList())
    }

    /**
     * Limpeza Cirúrgica de SDP para o WebrtcTransport.dll da Microsoft:
     * - Remove VP8, VP9 e AV1 da seção m=video (mantendo apenas H264 e seu RTX).
     * - Força profile-level-id=42e01f (Constrained Baseline sem B-frames).
     * - Remove extensões experimentais do Android que causam HTTP 400 e matam a sessão.
     */
    private fun sanitizeSdpForXboxBlade(rawSdp: String): String {
        val normalizedLines = rawSdp.replace("\r\n", "\n").split("\n")

        // 1. Identifica todos os Payload Types que são H264
        val h264Pts = mutableSetOf<String>()
        for (line in normalizedLines) {
            if (line.startsWith("a=rtpmap:") && line.contains("H264/90000", ignoreCase = true)) {
                val pt = line.substringAfter("a=rtpmap:").substringBefore(" ").trim()
                if (pt.isNotEmpty()) h264Pts.add(pt)
            }
        }

        // 2. Identifica os Payload Types de RTX vinculados aos H264
        val rtxPts = mutableSetOf<String>()
        for (line in normalizedLines) {
            if (line.startsWith("a=fmtp:") && line.contains("apt=")) {
                val pt = line.substringAfter("a=fmtp:").substringBefore(" ").trim()
                val apt = line.substringAfter("apt=").substringBefore(";").substringBefore(" ").trim()
                if (h264Pts.contains(apt)) {
                    rtxPts.add(pt)
                }
            }
        }

        val allowedVideoPts = h264Pts + rtxPts
        val out = StringBuilder()
        var currentSection = "header"
        var hasInjected42e01f = false

        for (line in normalizedLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("m=audio ")) currentSection = "audio"
            else if (trimmed.startsWith("m=video ")) currentSection = "video"
            else if (trimmed.startsWith("m=application ")) currentSection = "application"

            // Remove atributos que quebram o parser C++ da Microsoft
            if (trimmed.startsWith("a=extmap-allow-mixed")) continue
            if (trimmed.startsWith("a=candidate:")) continue
            if (trimmed.startsWith("a=extmap:") && (
                    trimmed.contains("video-layers-allocation") ||
                    trimmed.contains("dependency-descriptor") ||
                    trimmed.contains("color-space") ||
                    trimmed.contains("playout-delay") ||
                    trimmed.contains(" video-timing")
                )
            ) {
                continue
            }

            if (currentSection == "video" && allowedVideoPts.isNotEmpty()) {
                if (trimmed.startsWith("m=video ")) {
                    val parts = trimmed.split(" ")
                    if (parts.size > 3) {
                        val prefix = parts.subList(0, 3).joinToString(" ")
                        val ptsInLine = parts.subList(3, parts.size).filter { allowedVideoPts.contains(it) }
                        if (ptsInLine.isNotEmpty()) {
                            out.append("$prefix ${ptsInLine.joinToString(" ")}\r\n")
                            continue
                        }
                    }
                }

                // Filtra a=rtpmap, a=fmtp e a=rtcp-fb de codecs descartados (VP8, VP9, AV1)
                if (trimmed.startsWith("a=rtpmap:") || trimmed.startsWith("a=fmtp:") || trimmed.startsWith("a=rtcp-fb:")) {
                    val pt = trimmed.substringAfter(":").substringBefore(" ").trim()
                    if (pt != "*" && !allowedVideoPts.contains(pt)) {
                        continue
                    }
                }

                // Garante que o primeiro H264 declare explicitamente 42e01f exigido pelo xCloud
                if (trimmed.startsWith("a=fmtp:")) {
                    val pt = trimmed.substringAfter("a=fmtp:").substringBefore(" ").trim()
                    if (h264Pts.contains(pt) && !hasInjected42e01f) {
                        hasInjected42e01f = true
                        out.append("a=fmtp:$pt level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f\r\n")
                        continue
                    }
                }
            }

            out.append(trimmed).append("\r\n")
        }

        return out.toString()
    }

    private fun sendXboxControlHandshake() {
        try {
            val authMsg = JSONObject().apply {
                put("message", "authorizationRequest")
                put("accessKey", "4BDB3609-C1F1-4195-9B37-FEFF45DA8B8E")
            }.toString()
            sendJsonOnChannel(controlChannel, authMsg)

            val gamepadMsg = JSONObject().apply {
                put("message", "gamepadChanged")
                put("gamepadIndex", 0)
                put("wasAdded", true)
            }.toString()
            sendJsonOnChannel(controlChannel, gamepadMsg)

            val keyframeMsg = JSONObject().apply {
                put("message", "videoKeyframeRequested")
                put("ifrRequested", true)
            }.toString()
            sendJsonOnChannel(controlChannel, keyframeMsg)
        } catch (e: Exception) {
            Log.e(TAG, "Erro no handshake control: ${e.message}")
        }
    }

    private fun sendXboxMessageHandshake() {
        try {
            val handshake = JSONObject().apply {
                put("type", "Handshake")
                put("version", "messageV1")
                put("id", "00000000-0000-0000-0000-000000000001")
                put("cv", "")
            }.toString()
            sendJsonOnChannel(messageChannel, handshake)
        } catch (ignored: Exception) {}
    }

    private fun sendJsonOnChannel(channel: DataChannel?, jsonText: String) {
        val bytes = (jsonText + "\u0000").toByteArray(Charsets.UTF_8)
        val buf = DataChannel.Buffer(ByteBuffer.wrap(bytes), false)
        channel?.send(buf)
    }

    suspend fun applyRemoteAnswerAndCandidates(answerSdp: String, remoteCandidates: List<IceCandidate>) {
        val pc = peerConnection ?: return
        val setRemoteDeferred = CompletableDeferred<Unit>()

        val answer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onSetSuccess() {
                setRemoteDeferred.complete(Unit)
            }
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {
                setRemoteDeferred.completeExceptionally(Exception("Erro setRemoteDescription: $error"))
            }
        }, answer)

        setRemoteDeferred.await()

        for (candidate in remoteCandidates) {
            try {
                pc.addIceCandidate(candidate)
            } catch (ignored: Exception) {}
        }
    }

    fun close() {
        try {
            inputChannel?.close()
            controlChannel?.close()
            messageChannel?.close()
            chatChannel?.close()
            peerConnection?.close()
        } catch (ignored: Exception) {}
    }
}
