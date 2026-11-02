package com.xcloud.tclnative.webrtc

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONObject
import org.webrtc.*
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class XCloudWebRtcClient(
    context: Context,
    private val eglBase: EglBase,
    private val onVideoTrackReady: (VideoTrack) -> Unit,
    private val onConnectionStateChanged: (String) -> Unit,
    private val onStatistics: (StreamMetrics, InputMetrics) -> Unit = { _, _ -> },
    private val lowLatencyVideo: Boolean = true
) : StreamingPeer {
    private val factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private val candidates = CopyOnWriteArrayList<IceCandidate>()
    private val gatheringComplete = CompletableDeferred<Unit>()
    private val connected = CompletableDeferred<Unit>()
    private val channelsReady = CompletableDeferred<Unit>()
    private val handshake = ChannelHandshake()
    private val channels = java.util.concurrent.ConcurrentHashMap<String, DataChannel>()
    private val closed = AtomicBoolean(false)
    private val inputStarted = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var gamepad = GamepadState()
    private val pumpLock = Any()
    @Volatile private var inputPump: RealtimeInputPump? = null
    private val statsCollector = StreamStatsCollector()
    private val statisticsLock = Any()
    private var statsJob: Job? = null
    private val decoderLatency = java.util.concurrent.atomic.AtomicReference("Codec LL: aguardando vídeo")
    fun latencyDiagnostics(): String = (if (lowLatencyVideo) "Playout solicitado: 0–40 ms" else "Playout: adaptativo") +
        " | " + (if (lowLatencyVideo) decoderLatency.get() else "codec padrão")

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
            .setFieldTrials(VideoLatencyPolicy.fieldTrials(lowLatencyVideo))
            .setEnableInternalTracer(false).createInitializationOptions())
        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(LowLatencyDecoderFactory(eglBase.eglBaseContext, lowLatencyVideo) { decoderLatency.set(it) })
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()
    }

    private fun fail(message: String) {
        connected.completeExceptionally(IOException(message))
        channelsReady.completeExceptionally(IOException(message))
        if (!closed.get()) onConnectionStateChanged(message)
    }

    override suspend fun createOfferSdp(): Pair<String, List<IceCandidate>> = withTimeout(15_000) {
        check(!closed.get() && peerConnection == null)
        val config = PeerConnection.RTCConfiguration(listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        val pc = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                if (closed.get()) return
                onConnectionStateChanged("ICE: ${state?.name}")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> connected.complete(Unit)
                    PeerConnection.IceConnectionState.FAILED, PeerConnection.IceConnectionState.CLOSED -> fail("TRANSPORT: ICE $state")
                    else -> Unit
                }
            }
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) gatheringComplete.complete(Unit)
            }
            override fun onIceCandidate(candidate: IceCandidate?) {
                // The ':' in candidate:<foundation> is mandatory; IPv6 is valid too.
                if (candidate != null && !closed.get()) candidates.add(candidate)
            }
            private fun receive(track: MediaStreamTrack?) {
                if (closed.get()) return
                track?.setEnabled(true)
                if (track is VideoTrack) onVideoTrackReady(track)
            }
            override fun onAddStream(stream: MediaStream?) { receive(stream?.videoTracks?.firstOrNull()) }
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) { receive(receiver?.track()) }
        }) ?: throw IOException("TRANSPORT: falha ao criar PeerConnection")
        peerConnection = pc
        val receive = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, receive)
        val video = pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, receive)
        val capabilities = factory.getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
        val h264 = capabilities.filter { it.name.equals("H264", true) }
        if (h264.isEmpty()) throw IOException("VIDEO: o dispositivo não oferece decodificador H.264")
        // Select supported codecs before creating/setLocalDescription. Never forge profile-level-id.
        video.setCodecPreferences(h264.sortedBy {
            when (it.parameters["profile-level-id"]?.take(2)?.lowercase()) { "64" -> 0; "4d" -> 1; else -> 2 }
        } + capabilities.filter { it.name.equals("rtx", true) })
        listOf("control" to "controlV1", "input" to "1.0", "message" to "messageV1", "chat" to "chatV1").forEach { (name, protocol) ->
            val channel = pc.createDataChannel(name, DataChannel.Init().apply { ordered = true; this.protocol = protocol })
                ?: throw IOException("CHANNELS: não foi possível criar $name")
            channels[name] = channel
            channel.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(amount: Long) {}
                override fun onStateChange() {
                    if (closed.get()) return
                    if (channel.state() == DataChannel.State.OPEN) dispatch(handshake.onOpen(name))
                    else if (channel.state() == DataChannel.State.CLOSED && name != "chat") fail("CHANNELS: $name fechado")
                }
                override fun onMessage(buffer: DataChannel.Buffer?) {
                    if (closed.get() || name != "message" || buffer == null || buffer.binary) return
                    val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.duplicate().get(it) }
                    val type = runCatching { JSONObject(String(bytes, Charsets.UTF_8).trimEnd('\u0000')).optString("type") }.getOrNull()
                    if (type == "HandshakeAck") dispatch(handshake.onHandshakeAck())
                }
            })
        }
        val offer = CompletableDeferred<SessionDescription>()
        pc.createOffer(object : DescriptionObserver() {
            override fun onCreateSuccess(description: SessionDescription?) {
                if (description == null) offer.completeExceptionally(IOException("SDP: oferta vazia")) else offer.complete(description)
            }
            override fun onCreateFailure(error: String?) { offer.completeExceptionally(IOException("SDP: createOffer falhou")) }
        }, MediaConstraints())
        val original = offer.await()
        val description = SessionDescription(original.type, StreamProfile.receiveSdp(original.description))
        setDescription { observer -> pc.setLocalDescription(observer, description) }
        // A blocked STUN service must not discard usable host candidates.
        if (withTimeoutOrNull(8000) { gatheringComplete.await(); true } != true) {
            onConnectionStateChanged("ICE: coleta limitada a 8s; ${candidates.size} candidatos disponíveis")
        }
        if (candidates.isEmpty()) throw IOException("ICE_LOCAL: nenhum candidato de rede disponível")
        description.description to candidates.toList()
    }

    private fun dispatch(messages: List<ChannelMessage>) {
        messages.forEach { message ->
            val channel = channels[message.channel]
            if (channel == null || !channel.send(DataChannel.Buffer(ByteBuffer.wrap(message.bytes), false))) {
                fail("CHANNELS: falha ao enviar configuração ${message.channel}")
                return
            }
        }
        if (handshake.ready && inputStarted.compareAndSet(false, true)) {
            val input = channels.getValue("input")
            if (!input.send(DataChannel.Buffer(ByteBuffer.wrap(GamepadPacket.metadata(0.0)), true))) {
                fail("CHANNELS: falha ao iniciar input")
                return
            }
            channelsReady.complete(Unit)
            val pump = RealtimeInputPump(
                bufferedAmount = { if (closed.get()) Long.MAX_VALUE else input.bufferedAmount() },
                send = { bytes -> !closed.get() && input.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true)) },
                onFailure = { fail("CHANNELS: falha no envio de input") },
                prioritize = { runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) }; Unit }
            )
            synchronized(pumpLock) {
                if (closed.get()) return
                inputPump = pump
                pump.update(gamepad)
                pump.start()
            }
            statsJob = scope.launch {
                while (isActive) {
                    synchronized(statisticsLock) {
                        if (!closed.get()) peerConnection?.getStats { report ->
                            if (!closed.get()) {
                                val samples = report.statsMap.values.map { RtcSample(it.id, it.type, it.timestampUs, it.members) }
                                val metrics = statsCollector.collect(samples)
                                if (!closed.get()) onStatistics(metrics, inputPump?.metrics ?: InputMetrics())
                            }
                        }
                    }
                    delay(1000)
                }
            }
        }
    }

    fun updateGamepad(state: GamepadState) { gamepad = state; inputPump?.update(state) }

    override suspend fun setRemoteAnswer(answerSdp: String) {
        val pc = peerConnection ?: throw IOException("TRANSPORT: PeerConnection ausente")
        withTimeout(10_000) { setDescription { pc.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, answerSdp)) } }
    }

    override suspend fun addRemoteCandidates(candidates: List<IceCandidate>) {
        val pc = peerConnection ?: throw IOException("TRANSPORT: PeerConnection ausente")
        candidates.forEach { if (!pc.addIceCandidate(it)) throw IOException("ICE_REMOTE: candidato rejeitado pelo WebRTC") }
    }

    override suspend fun awaitReady() {
        withTimeout(30_000) { connected.await(); channelsReady.await() }
    }

    private suspend fun setDescription(set: (SdpObserver) -> Unit) {
        val done = CompletableDeferred<Unit>()
        set(object : DescriptionObserver() {
            override fun onSetSuccess() { done.complete(Unit) }
            override fun onSetFailure(error: String?) { done.completeExceptionally(IOException("SDP: setDescription falhou")) }
        })
        done.await()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(pumpLock) { inputPump }?.close()
        statsJob?.cancel()
        scope.cancel()
        synchronized(statisticsLock) { /* Wait for any getStats request to leave JNI before disposal. */ }
        gatheringComplete.cancel(); connected.cancel(); channelsReady.cancel()
        channels.values.forEach { it.unregisterObserver(); it.close(); it.dispose() }
        channels.clear()
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        factory.dispose()
    }

    private open class DescriptionObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
