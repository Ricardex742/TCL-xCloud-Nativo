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

        // Usa o MediaCodec de Hardware do processador (Snapdragon / Realtek TCL P755)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)

        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(decoderFactory)
            .setVideoEncoderFactory(encoderFactory)
            .createPeerConnectionFactory()
    }

    /**
     * Cria a conexão WebRTC, os canais exigidos pelo protocolo Xbox e gera o SDP Offer
     */
    suspend fun createOfferSdp(): Pair<String, List<IceCandidate>> {
        gatheredCandidates.clear()
        iceGatheringComplete = CompletableDeferred()

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
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
                    gatheredCandidates.add(candidate)
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

        // 1. Canais de Dados obrigatórios do protocolo xCloud
        inputChannel = pc.createDataChannel("input", DataChannel.Init().apply {
            ordered = true
            protocol = "1.0"
        })
        controlChannel = pc.createDataChannel("control", DataChannel.Init().apply {
            ordered = true
            protocol = "controlV1"
        })
        messageChannel = pc.createDataChannel("message", DataChannel.Init().apply {
            ordered = true
            protocol = "messageV1"
        })
        chatChannel = pc.createDataChannel("chat", DataChannel.Init().apply {
            ordered = true
            protocol = "chatV1"
        })

        // 2. Transceivers de Vídeo (H.264) e Áudio (Opus) apenas de receção (RECV_ONLY)
        val recvOnlyInit = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, recvOnlyInit)
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, recvOnlyInit)

        // 3. Gera o SDP Offer
        val offerDeferred = CompletableDeferred<SessionDescription>()
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
        }, MediaConstraints())

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

        // Aguarda até 2 segundos para recolher todos os candidatos ICE locais
        withTimeoutOrNull(2000) {
            iceGatheringComplete.await()
        }

        val finalLocalSdp = pc.localDescription?.description ?: offer.description
        return Pair(finalLocalSdp, gatheredCandidates.toList())
    }

    /**
     * Aplica a resposta SDP e os candidatos ICE enviados pelo servidor Xbox Series X da Microsoft
     */
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
            pc.addIceCandidate(candidate)
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
