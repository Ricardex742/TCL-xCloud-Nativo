package com.xcloud.tclnative.webrtc

import android.content.Context
import com.xcloud.tclnative.settings.VideoCodecMode
import kotlinx.coroutines.*
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
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
    private val lowLatencyVideo: Boolean = true,
    private val streamResolution: StreamResolution = StreamResolution.HD_1080_HQ,
    private val nativeTouch: Boolean = false,
    private val onRumble: (RumbleCommand) -> Unit = {},
    private val compatibility: Boolean = false,
    private val videoCodec: VideoCodecMode = VideoCodecMode.H264
) : StreamingPeer {
    private val factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private val candidates = CopyOnWriteArrayList<IceCandidate>()
    private val gatheringComplete = CompletableDeferred<Unit>()
    private val connected = CompletableDeferred<Unit>()
    private val channelsReady = CompletableDeferred<Unit>()
    private val handshake = ChannelHandshake(nativeTouch)
    private val channels = java.util.concurrent.ConcurrentHashMap<String, DataChannel>()
    private val closed = AtomicBoolean(false)
    private val inputStarted = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gamepads = mutableMapOf<Int,GamepadState>().apply { if(!nativeTouch) put(0,GamepadState()) }
    private val gamepadConnections = GamepadConnections()
    @Volatile private var inputDimensions: TouchInputDimensions? = null
    private val pumpLock = Any()
    private val pendingTouch = NativeTouchBuffer()
    @Volatile private var touchEnabled = nativeTouch
    @Volatile private var inputPump: RealtimeInputPump? = null
    private val statsCollector = StreamStatsCollector()
    private val statisticsLock = Any()
    private var statsJob: Job? = null
    private val decoderRecovery = DecoderRecoveryController()
    private val decoderLatency = java.util.concurrent.atomic.AtomicReference("Codec LL: aguardando vídeo")
    private val effectiveCodec=if(compatibility) VideoCodecMode.H264 else videoCodec
    private val codecStatus=java.util.concurrent.atomic.AtomicReference("Codec: aguardando capacidades")
    @Volatile private var experimentalOffered=false
    @Volatile private var answerVideo: VideoCodecAnswer?=null
    private val decoderFactory: ExperimentalVideoDecoderFactory
    fun codecDiagnostics(): String = "Codec solicitado: ${videoCodec.label} | ${codecStatus.get()}\n${decoderFactory.diagnostics()}"
    fun shouldRetryH264(error: Exception, firstFrame: Boolean): Boolean = ExperimentalCodecRetry.allowed(
        effectiveCodec,experimentalOffered,answerVideo,firstFrame,error.message ?: "")
    private val audioLock=Any()
    private val audioTracks=mutableSetOf<AudioTrack>()
    private val audioSession=GameAudioSession(context) { gain -> synchronized(audioLock) {
        if(!closed.get()) audioTracks.forEach { it.setVolume(gain) }
    } }
    fun currentInputMetrics(): InputMetrics = inputPump?.metrics ?: InputMetrics()
    fun audioDiagnostics()=audioSession.diagnostics()
    fun resumeAudio() { if(synchronized(audioLock) { audioTracks.isNotEmpty() }) audioSession.requestFocus() }
    fun inputDiagnostics(): String = if(nativeTouch)
        "Toque nativo: ${if(touchEnabled) "sinalizado ativo" else "desativado para HUD"}; espaço de input: ${inputDimensions?.let { "${it.width}×${it.height}" } ?: "aguardando metadata"}; slot virtual 0: ${if(channelsReady.isCompleted) "registro enviado" else "aguardando canal"}"
        else "Input Xbox: controle/HUD"
    fun latencyDiagnostics(): String = (if (lowLatencyVideo) "Playout solicitado: 0–40 ms" else "Playout: adaptativo") +
        " | " + (if (lowLatencyVideo) decoderLatency.get() else "codec padrão") +
        if(nativeTouch) " | Toque: ${if(touchEnabled) "ativo" else "desativado"}, ${inputDimensions?.let { "${it.width}×${it.height}" } ?: "aguardando metadata"}, ${inputPump?.metrics?.pointerEventsSent ?: 0} eventos enviados" else ""

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
            .setFieldTrials(VideoLatencyPolicy.fieldTrials(lowLatencyVideo))
            .setEnableInternalTracer(false).createInitializationOptions())
        decoderFactory=ExperimentalVideoDecoderFactory(eglBase.eglBaseContext,lowLatencyVideo,decoderRecovery,
            { decoderLatency.set(it) }, { if(!closed.get()) onConnectionStateChanged(it) },effectiveCodec.name,
            streamResolution.width,streamResolution.height,if(compatibility) 30 else 60)
        val audio=JavaAudioDeviceModule.builder(context.applicationContext)
            .setAudioAttributes(GameAudioSession.attributes()).setUseStereoOutput(true).setEnableVolumeLogger(false)
            .setAudioTrackStateCallback(object: JavaAudioDeviceModule.AudioTrackStateCallback {
                override fun onWebRtcAudioTrackStart() { audioSession.playing=true }
                override fun onWebRtcAudioTrackStop() { audioSession.playing=false }
            })
            .setAudioTrackErrorCallback(object: JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(message: String) { audioSession.error="inicialização: ${message.take(180)}" }
                override fun onWebRtcAudioTrackStartError(code: JavaAudioDeviceModule.AudioTrackStartErrorCode,message: String) { audioSession.error="início: $code" }
                override fun onWebRtcAudioTrackError(message: String) { audioSession.error="reprodução: ${message.take(180)}" }
            }).createAudioDeviceModule()
        try { factory = PeerConnectionFactory.builder().setAudioDeviceModule(audio)
            .setVideoDecoderFactory(decoderFactory)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()
        } finally { audio.release() } // The factory owns its own native reference after construction.
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
                if (track is AudioTrack) {
                    synchronized(audioLock) {
                        if(closed.get()) return
                        audioTracks.add(track); track.setVolume(audioSession.gain)
                    }
                    audioSession.requestFocus()
                }
            }
            override fun onAddStream(stream: MediaStream?) {
                receive(stream?.videoTracks?.firstOrNull()); stream?.audioTracks?.forEach(::receive)
            }
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) { receive(receiver?.track()) }
            override fun onTrack(transceiver: RtpTransceiver?) { receive(transceiver?.receiver?.track()) }
        }) ?: throw IOException("TRANSPORT: falha ao criar PeerConnection")
        peerConnection = pc
        val receive = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, receive)
        val video = pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, receive)
        val capabilities = factory.getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
        // Select supported codecs before creating/setLocalDescription. Never forge profile-level-id.
        val selection=CodecPreference.select(capabilities,effectiveCodec,compatibility || streamResolution==StreamResolution.HD_720,compatibility)
        experimentalOffered=selection.experimental
        codecStatus.set(selection.description)
        val result=video.setCodecPreferences(selection.codecs)
        if(result.isError) throw IOException("SDP: preferências de codec rejeitadas pelo WebRTC")
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
                    if (closed.get() || buffer == null) return
                    if(name=="input" && buffer.binary) {
                        RemoteInputReport.parse(buffer.data)?.let { report ->
                            if (report.width != null && report.height != null) synchronized(pumpLock) {
                                val dimensions = TouchInputDimensions(report.width,report.height)
                                inputDimensions = dimensions
                                inputPump?.setTouchDimensions(dimensions)
                            }
                            report.rumble?.let(onRumble)
                        }
                        return
                    }
                    if(name!="message" || buffer.binary) return
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
        val description = SessionDescription(original.type, StreamProfile.receiveSdp(original.description, streamResolution,compatibility))
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
            if (channel == null || !channel.send(DataChannel.Buffer(ByteBuffer.wrap(message.bytes), message.binary))) {
                fail("CHANNELS: falha ao enviar configuração ${message.channel}")
                return
            }
        }
        // Only the callback carrying startup metadata may start the pump; a duplicate Ack can race it.
        if (messages.any { it.channel == "input" && it.binary } && inputStarted.compareAndSet(false, true)) {
            val input = channels.getValue("input")
            synchronized(pumpLock) {
                if (closed.get()) return
                gamepads.keys.forEach { if(gamepadConnections.add(it)) sendGamepadChanged(it,true) }
                val pump = RealtimeInputPump(
                bufferedAmount = { if (closed.get()) Long.MAX_VALUE else input.bufferedAmount() },
                send = { bytes -> !closed.get() && input.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true)) },
                onFailure = { fail("CHANNELS: falha no envio de input") },
                prioritize = { runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) }; Unit },
                initialGamepads = gamepads.keys.toSet(), touch = pendingTouch, inputHz = if(compatibility) 60 else 250,
                initialTouchDimensions = inputDimensions
            )
                inputPump = pump
                gamepads.forEach { (index,state) -> pump.update(state,index) }
                pump.start()
                channelsReady.complete(Unit)
                if(nativeTouch) sendTouchDimensions()
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

    /** Ask the running Xbox encoder for an IDR without allocating a new cloud session. */
    fun requestVideoKeyframe(): Boolean {
        if (closed.get()) return false
        val control = channels["control"] ?: return false
        if (control.state() != DataChannel.State.OPEN) return false
        val message = "{\"message\":\"videoKeyframeRequested\",\"ifrRequested\":true}"
        return control.send(DataChannel.Buffer(ByteBuffer.wrap(message.toByteArray(Charsets.UTF_8)), false))
    }

    fun recoverVideoDecoder(): Boolean {
        if (closed.get()) return false
        val scheduled = decoderRecovery.requestReset()
        requestVideoKeyframe()
        return scheduled
    }

    private fun sendGamepadChanged(index: Int, added: Boolean) {
        val bytes=JSONObject().put("message","gamepadChanged").put("gamepadIndex",index).put("wasAdded",added).toString().toByteArray(Charsets.UTF_8)
        if(channels["control"]?.send(DataChannel.Buffer(ByteBuffer.wrap(bytes),false)) != true && !closed.get()) fail("CHANNELS: falha ao atualizar controle")
    }
    fun updateGamepad(state: GamepadState, index: Int = 0) {
        require(index in 0..3)
        synchronized(pumpLock) {
            if(closed.get()) return
            val added=index !in gamepads
            // Reserved menu chords and idle dongles must not start physical reports in a touch-only game.
            if (nativeTouch && added && state == GamepadState()) return
            gamepads[index]=state
            if(added && channelsReady.isCompleted && gamepadConnections.add(index)) sendGamepadChanged(index,true)
            inputPump?.update(state,index)
        }
    }
    fun removeGamepad(index: Int) {
        synchronized(pumpLock) {
            if(index==0 && !nativeTouch) { gamepads[0]=GamepadState(); inputPump?.update(GamepadState()); return }
            if(gamepads.remove(index)!=null && channelsReady.isCompleted && gamepadConnections.remove(index)) sendGamepadChanged(index,false)
            inputPump?.remove(index)
        }
    }
    fun sendPointers(events: List<NativePointer>) { synchronized(pumpLock) {
        if(touchEnabled && !closed.get()) {
            val pump = inputPump
            if(pump != null) pump.pointers(events) else pendingTouch.add(events)
        }
    } }
    private fun sendTouchDimensions() {
        if(closed.get() || !handshake.ready || channels["message"]?.state()!=DataChannel.State.OPEN) return
        val message=ChannelHandshake.dimensions(handshake.displayDimensions ?: return)
        channels["message"]?.send(DataChannel.Buffer(ByteBuffer.wrap(message.bytes),false))
    }
    fun updateDisplayDimensions(dimensions: ClientDisplayDimensions) {
        if (handshake.displayDimensions == dimensions) return
        handshake.displayDimensions = dimensions
        sendTouchDimensions()
    }
    /** Reassert after the stream is ready and allow the native session to fall back to virtual controls. */
    fun setNativeTouchEnabled(enabled: Boolean): Boolean {
        if (!nativeTouch || closed.get() || !channelsReady.isCompleted) return false
        val message = JSONObject().put("type","Message").put("target","/streaming/characteristics/touchinputenabledchanged")
            .put("id",java.util.UUID.randomUUID().toString()).put("cv","")
            .put("content",JSONObject().put("touchInputEnabled",enabled).toString()).toString().toByteArray(Charsets.UTF_8)
        val sent = channels["message"]?.send(DataChannel.Buffer(ByteBuffer.wrap(message),false)) == true
        if (sent) synchronized(pumpLock) { if(touchEnabled != enabled) pendingTouch.release(); touchEnabled = enabled }
        return sent
    }
    fun resetInput() { synchronized(pumpLock) {
        gamepads.keys.toList().forEach { gamepads[it]=GamepadState(); inputPump?.update(GamepadState(),it) }
        pendingTouch.release()
    } }

    override suspend fun setRemoteAnswer(answerSdp: String) {
        val pc = peerConnection ?: throw IOException("TRANSPORT: PeerConnection ausente")
        val selected=CodecPreference.answerVideo(answerSdp) ?: throw IOException("VIDEO_CODEC: resposta sem vídeo ativo")
        if(selected.name.equals("H265",true) && (effectiveCodec!=VideoCodecMode.HEVC || !experimentalOffered || !CodecPreference.mainHevc(selected.parameters)))
            throw IOException("VIDEO_CODEC: resposta HEVC sem suporte anunciado para o perfil")
        withTimeout(10_000) { setDescription { pc.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, answerSdp)) } }
        answerVideo=selected
        codecStatus.set("${codecStatus.get()} | Resposta SDP: ${selected.label} (preferência; recebido nas estatísticas)")
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
        synchronized(audioLock) { audioTracks.clear() }
        audioSession.close()
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
