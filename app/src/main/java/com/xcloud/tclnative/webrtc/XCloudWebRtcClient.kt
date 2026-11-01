package com.xcloud.tclnative.webrtc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * Cria a conexão WebRTC na ordem exata exigida pelo servidor Xbox Series X:
     * 1º Áudio (mid:0) -> 2º Vídeo (mid:1) -> 3º DataChannels (mid:2)
     */
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
                if (candidate != null) {
                    // Filtra apenas candidatos IPv4 válidos e remove extensões proprietárias do Android
                    if (!candidate.sdp.contains(":")) {
                        val cleanedCandSdp = candidate.sdp
                            .replace(Regex(" network-id \\d+"), "")
                            .replace(Regex(" network-cost \\d+"), "")
                        gatheredCandidates.add(
                            IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, cleanedCandSdp)
                        )
                    }
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
        }) ?: throw Exception("Falha ao criar PeerConnection nativa")

        this.peerConnection = pc

        // ORDEM CRÍTICA PARA EVITAR HTTP 400 NO XCLOUD:
        // 1. Primeiro adicionamos Áudio e Vídeo (garante mid:0 e mid:1)
        val recvOnlyInit = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        val sendRecvInit = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, sendRecvInit)
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, recvOnlyInit)

        // 2. Depois criamos os DataChannels (garante mid:2 para m=application)
        chatChannel = pc.createDataChannel("chat", DataChannel.Init().apply {
            ordered = true
            protocol = "chatV1"
        })
        controlChannel = pc.createDataChannel("control", DataChannel.Init().apply {
            ordered = true
            protocol = "controlV1"
        })
        inputChannel = pc.createDataChannel("input", DataChannel.Init().apply {
            ordered = true
            protocol = "1.0"
        })
        messageChannel = pc.createDataChannel("message", DataChannel.Init().apply {
            ordered = true
            protocol = "messageV1"
        })

        // 3. Gera o SDP Offer puro
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

        // Enviamos o offer.description limpo (sem candidatos sujos embutidos no SDP)
        val cleanSdp = sanitizeSdpForXbox(offer.description)
        return Pair(cleanSdp, gatheredCandidates.toList())
    }

    /**
     * Limpa linhas proprietárias do Android que causam HTTP 400 no WebrtcTransport.dll da Microsoft
     */
    private fun sanitizeSdpForXbox(rawSdp: String): String {
        val lines = rawSdp.split("\r\n")
        val out = StringBuilder()
        for (line in lines) {
            if (line.isBlank()) continue
            // Remove extensões que quebram o parser C++ da consola Xbox Series X
            if (line.startsWith("a=extmap-allow-mixed")) continue
            if (line.startsWith("a=msid-semantic:")) {
                out.append("a=msid-semantic: WMS\r\n")
                continue
            }
            out.append(line).append("\r\n")
        }
        return out.toString()
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
