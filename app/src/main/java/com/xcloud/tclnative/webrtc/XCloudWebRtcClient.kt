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
    private val videoCodec: VideoCodecMode = VideoCodecMode.H264,
    private val maxBitrateKbps: Int = 0,private val preferIpv6: Boolean = false,
    private val nativeKeyboardMouse: Boolean = false,private val microphoneAllowed: Boolean = false,outputGain: Int = 100
) : StreamingPeer {
    private val appContext=context.applicationContext
    private val factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private val peerGeneration=java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var iceGathering: LocalIceGathering?=null
    private val iceReports=CopyOnWriteArrayList<String>()
    private val connected = CompletableDeferred<Unit>()
    private val ipv6ProbeFailed=CompletableDeferred<Unit>()
    @Volatile private var probingIpv6=false
    private val channelsReady = CompletableDeferred<Unit>()
    private val handshake = ChannelHandshake(nativeTouch)
    private val channels = java.util.concurrent.ConcurrentHashMap<String, DataChannel>()
    private val closed = AtomicBoolean(false)
    private val inputStarted = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gamepads = mutableMapOf<Int,GamepadState>().apply { if(!nativeTouch && !nativeKeyboardMouse) put(0,GamepadState()) }
    private val gamepadConnections = GamepadConnections()
    @Volatile private var inputDimensions: TouchInputDimensions? = null
    private val pumpLock = Any()
    private val pendingTouch = NativeTouchBuffer()
    private val desktopInput=DesktopInputBuffer()
    private var negotiatedInputVersion=0
    @Volatile private var touchEnabled = nativeTouch
    @Volatile private var inputPump: RealtimeInputPump? = null
    private val statsCollector = StreamStatsCollector()
    private val statisticsLock = Any()
    private var statsJob: Job? = null
    private val decoderRecovery = DecoderRecoveryController()
    private val controllerEventTiming=ControllerEventTiming()
    fun recordControllerEvent(eventUptimeMs: Long,arrivalUptimeMs: Long,historySize: Int,name: String?) =
        controllerEventTiming.record(eventUptimeMs,arrivalUptimeMs,historySize,name)
    private val decoderLatency = java.util.concurrent.atomic.AtomicReference("Codec LL: aguardando vídeo")
    private val effectiveCodec=if(compatibility) VideoCodecMode.H264_BASELINE else videoCodec
    private val codecTrace=CodecNegotiationTrace(effectiveCodec)
    private val decoderFactory: H264VideoDecoderFactory
    fun codecDiagnostics(): String = codecTrace.diagnostics()+"\nBitrate máximo solicitado: "+(if(maxBitrateKbps==0) "Automático" else "${maxBitrateKbps/1000.0} Mbps")
    fun iceDiagnostics(): String = iceReports.joinToString("\n").ifBlank { "ICE local: aguardando coleta" }
    private val mainHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private val audioDevice: JavaAudioDeviceModule
    private val peakGuard=AudioPeakGuard().apply { set(outputGain) }
    private var micSource: AudioSource?=null
    private var micTrack: AudioTrack?=null
    private var micSender: RtpSender?=null
    @Volatile private var chatSupported=false
    @Volatile private var microphoneForeground=true
    @Volatile var microphoneActive=false
        private set
    @Volatile private var microphoneStatus="Microfone: mudo"
    @Volatile private var micBytes=0L
    private var chatNegotiated=false
    private val audioLock=Any()
    private val audioTracks=mutableSetOf<AudioTrack>()
    private val audioSession=GameAudioSession(context) { gain -> synchronized(audioLock) {
        if(!closed.get()) audioTracks.forEach { it.setVolume(gain*peakGuard.effective) }
    } }
    fun currentInputMetrics(): InputMetrics = inputPump?.metrics ?: InputMetrics()
    fun audioDiagnostics()=audioSession.diagnostics()+"\n${peakGuard.diagnostics()}\n$microphoneStatus • chatStream anunciado: $chatSupported • áudio enviado: $micBytes B"
    fun setOutputGain(percent: Int) { peakGuard.set(percent);applyAudioGain() }
    private fun applyAudioGain() { synchronized(audioLock) { if(!closed.get()) audioTracks.forEach { it.setVolume(audioSession.gain*peakGuard.effective) } } }
    fun microphoneLabel()=if(microphoneActive) "Silenciar microfone" else "Ativar microfone"
    override fun configureChat(supported: Boolean) { chatSupported=supported && microphoneAllowed }
    override fun configureInput(version: Int) {
        negotiatedInputVersion=version
        if(nativeKeyboardMouse && version!=8) throw IOException("INPUT_NATIVE: o serviço não confirmou input v8 para mouse/teclado. Selecione emulação ou controle.")
    }
    fun microphoneUnavailableReason()=when {
        closed.get() -> "A sessão foi encerrada."
        !microphoneAllowed -> "O microfone está desativado nesta sessão. Ative Áudio e microfone antes da próxima partida."
        !chatSupported -> "O serviço não anunciou chatStream nesta sessão. A captura continua desligada."
        else -> "Microfone indisponível."
    }
    fun canUseMicrophone()=microphoneAllowed && chatSupported && !closed.get()
    fun microphoneNegotiated()=chatNegotiated
    fun setAudioForeground(visible: Boolean) { microphoneForeground=visible;if(!visible) setMicrophoneMuted(true) }
    fun setMicrophoneMuted(muted: Boolean) {
        if(closed.get()) return
        val allowed=MicrophonePolicy.canCapture(microphoneAllowed,chatSupported,chatNegotiated,microphoneForeground,
            androidx.core.content.ContextCompat.checkSelfPermission(appContext,android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED)
        val silent=muted || !allowed
        microphoneActive=false
        audioDevice.setMicrophoneMute(true)
        if(silent) {
            micTrack?.setEnabled(false)
            val paused=setMicrophoneEncoding(false)
            audioDevice.setAudioRecordEnabled(false)
            if(micTrack!=null) audioDevice.requestStopRecording()
            microphoneStatus=if(paused) "Microfone: mudo; envio pausado" else "Microfone: mudo; encoder recusou pausa"
        } else {
            audioDevice.setAudioRecordEnabled(true)
            if(!setMicrophoneEncoding(true)) {
                audioDevice.setAudioRecordEnabled(false)
                microphoneStatus="Microfone: encoder recusou ativação; mudo"
                return
            }
            micTrack?.setEnabled(true)
            audioDevice.setMicrophoneMute(false)
            microphoneActive=true
            audioDevice.requestStartRecording()
            microphoneStatus="Microfone: captura solicitada"
        }
    }
    private fun setMicrophoneEncoding(active: Boolean): Boolean {
        val sender=micSender ?: return !active
        repeat(2) {
            val parameters=sender.parameters
            if(parameters.encodings.isEmpty()) return false
            if(parameters.encodings.all { it.active==active }) return true
            parameters.encodings.forEach { it.active=active }
            if(sender.setParameters(parameters)) return true
        }
        return false
    }
    suspend fun createChatOffer(): String {
        check(canUseMicrophone()) { "CHAT: microfone não habilitado ou chatStream não anunciado pelo serviço" }
        val pc=peerConnection ?: throw IOException("CHAT: sessão encerrada")
        check(androidx.core.content.ContextCompat.checkSelfPermission(appContext,android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED) {
            "CHAT: permissão de microfone não concedida"
        }
        audioDevice.setMicrophoneMute(true);audioDevice.setAudioRecordEnabled(false)
        if(micTrack==null) {
            val constraints=MediaConstraints().apply {
                optional.add(MediaConstraints.KeyValuePair("googEchoCancellation","true"))
                optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression","true"))
            }
            micSource=factory.createAudioSource(constraints)
            micTrack=factory.createAudioTrack("xcloud-microphone",micSource).also { it.setEnabled(false) }
            micSender=pc.addTrack(micTrack,listOf("xcloud-chat")) ?: throw IOException("CHAT: sender indisponível")
            if(!setMicrophoneEncoding(false)) throw IOException("CHAT: encoder não confirmou pausa antes da negociação")
        }
        val offer=CompletableDeferred<SessionDescription>()
        pc.createOffer(object: DescriptionObserver() {
            override fun onCreateSuccess(description: SessionDescription?) {
                if(description==null) offer.completeExceptionally(IOException("CHAT: oferta vazia")) else offer.complete(description)
            }
            override fun onCreateFailure(error: String?) { offer.completeExceptionally(IOException("CHAT: oferta recusada")) }
        },MediaConstraints())
        val raw=withTimeout(10000) { offer.await() }
        val local=SessionDescription(raw.type,StreamProfile.receiveSdp(raw.description,streamResolution,
            compatibility || effectiveCodec==VideoCodecMode.H264_BASELINE,maxBitrateKbps))
        withTimeout(10000) { setDescription("CHAT_LOCAL") { pc.setLocalDescription(it,local) } }
        return local.description
    }
    suspend fun applyChatAnswer(sdp: String) {
        setRemoteAnswer(sdp)
        chatNegotiated=true
    }
    suspend fun rejectChat() {
        if(closed.get()) return
        setMicrophoneMuted(true)
        val pc=peerConnection ?: return
        if(pc.signalingState()==PeerConnection.SignalingState.HAVE_LOCAL_OFFER)
            runCatching { withTimeout(5000) { setDescription("CHAT_ROLLBACK") { pc.setLocalDescription(it,SessionDescription(SessionDescription.Type.ROLLBACK,"")) } } }
        if(closed.get() || peerConnection!==pc) return
        micSender?.let { pc.removeTrack(it) };micSender=null
        micTrack?.dispose();micTrack=null;micSource?.dispose();micSource=null;chatNegotiated=false
        microphoneStatus="Microfone: negociação não concluída; mudo"
    }
    fun resumeAudio() { if(synchronized(audioLock) { audioTracks.isNotEmpty() }) audioSession.requestFocus() }
    fun inputDiagnostics(): String = (if(nativeTouch)
        "Toque nativo: ${if(touchEnabled) "sinalizado ativo" else "desativado para HUD"}; espaço de input: ${inputDimensions?.let { "${it.width}×${it.height}" } ?: "aguardando metadata"}; slot virtual 0: ${if(channelsReady.isCompleted) "registro enviado" else "aguardando canal"}"
        else if(nativeKeyboardMouse) "Input Xbox: mouse/teclado nativos, versão negociada $negotiatedInputVersion" else "Input Xbox: controle/HUD")+"\n"+controllerEventTiming.diagnostics()+
        (if(nativeTouch) "\n${pendingTouch.diagnostics()}" else "")
    fun latencyDiagnostics(): String = (if (lowLatencyVideo) "Playout solicitado: 0–40 ms" else "Playout: adaptativo") +
        " | " + (if (lowLatencyVideo) decoderLatency.get() else "codec padrão") +
        if(nativeTouch) " | Toque: ${if(touchEnabled) "ativo" else "desativado"}, ${inputDimensions?.let { "${it.width}×${it.height}" } ?: "aguardando metadata"}, ${inputPump?.metrics?.pointerEventsSent ?: 0} eventos enviados" else ""

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
            .setFieldTrials(VideoLatencyPolicy.fieldTrials(lowLatencyVideo))
            .setEnableInternalTracer(false).createInitializationOptions())
        decoderFactory=H264VideoDecoderFactory(eglBase.eglBaseContext,lowLatencyVideo,decoderRecovery,
            { decoderLatency.set(it) },effectiveCodec==VideoCodecMode.H264_BASELINE)
        val audio=JavaAudioDeviceModule.builder(context.applicationContext)
            .setAudioAttributes(GameAudioSession.attributes()).setUseStereoOutput(true).setEnableVolumeLogger(false)
            .setPlaybackSamplesReadyCallback { samples ->
                if(!closed.get() && peakGuard.observe(samples.data,samples.audioFormat,android.os.SystemClock.elapsedRealtime()))
                    mainHandler.post { applyAudioGain() }
            }
            .setAudioRecordStateCallback(object: JavaAudioDeviceModule.AudioRecordStateCallback {
                override fun onWebRtcAudioRecordStart() { if(microphoneActive) microphoneStatus="Microfone: captura iniciada" }
                override fun onWebRtcAudioRecordStop() { if(microphoneActive) microphoneStatus="Microfone: captura parada pelo Android" }
            })
            .setAudioRecordErrorCallback(object: JavaAudioDeviceModule.AudioRecordErrorCallback {
                private fun rejected() { mainHandler.post {
                    if(!closed.get()) { setMicrophoneMuted(true);microphoneStatus="Microfone: captura recusada pelo Android; mudo" }
                } }
                override fun onWebRtcAudioRecordInitError(message: String) { rejected() }
                override fun onWebRtcAudioRecordStartError(code: JavaAudioDeviceModule.AudioRecordStartErrorCode,message: String) { rejected() }
                override fun onWebRtcAudioRecordError(message: String) { rejected() }
            })
            .setAudioTrackStateCallback(object: JavaAudioDeviceModule.AudioTrackStateCallback {
                override fun onWebRtcAudioTrackStart() { audioSession.playing=true }
                override fun onWebRtcAudioTrackStop() { audioSession.playing=false }
            })
            .setAudioTrackErrorCallback(object: JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(message: String) { audioSession.error="inicialização: ${message.take(180)}" }
                override fun onWebRtcAudioTrackStartError(code: JavaAudioDeviceModule.AudioTrackStartErrorCode,message: String) { audioSession.error="início: $code" }
                override fun onWebRtcAudioTrackError(message: String) { audioSession.error="reprodução: ${message.take(180)}" }
            }).createAudioDeviceModule()
        audioDevice=audio
        audio.setMicrophoneMute(true);audio.setAudioRecordEnabled(false)
        try { factory = PeerConnectionFactory.builder().setAudioDeviceModule(audio)
            .setVideoDecoderFactory(decoderFactory)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()
        } catch(error: Throwable) { audio.release();throw error }
    }

    private fun fail(message: String) {
        connected.completeExceptionally(IOException(message))
        channelsReady.completeExceptionally(IOException(message))
        if (!closed.get()) onConnectionStateChanged(message)
    }

    override suspend fun createOfferSdp(): Pair<String, List<IceCandidate>> = withTimeout(30_000) {
        check(!closed.get() && peerConnection == null)
        // Final disposal and onDestroy also run on Main. Serialize retry disposal there too.
        LocalIceOfferRetry.prepare(::createOfferAttempt,{ withContext(Dispatchers.Main.immediate) { closePeerTransport() } }) {
            onConnectionStateChanged("ICE: sem candidatos locais; refazendo a conexão uma vez (2/2), mantendo a sessão Xbox…")
        }
    }

    private suspend fun createOfferAttempt(attempt: Int): Pair<String,List<IceCandidate>> {
        currentCoroutineContext().ensureActive()
        check(!closed.get() && peerConnection==null)
        val generation=peerGeneration.incrementAndGet()
        fun current()=!closed.get() && generation==peerGeneration.get()
        val gathering=LocalIceGathering().also { iceGathering=it }
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
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                if(current() && candidates!=null) gathering.removed(candidates)
            }
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                if (!current()) return
                val preferring=probingIpv6
                onConnectionStateChanged(IceFamilyPolicy.connectionMessage(state?.name,preferring))
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> connected.complete(Unit)
                    PeerConnection.IceConnectionState.FAILED -> if(preferring) ipv6ProbeFailed.complete(Unit) else fail("TRANSPORT: ICE $state")
                    PeerConnection.IceConnectionState.CLOSED -> fail("TRANSPORT: ICE $state")
                    else -> Unit
                }
            }
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                if(current() && state!=null) gathering.state(state)
            }
            override fun onIceCandidate(candidate: IceCandidate?) {
                // The ':' in candidate:<foundation> is mandatory; IPv6 is valid too.
                if (candidate != null && current()) gathering.candidate(candidate)
            }
            override fun onIceCandidateError(event: IceCandidateErrorEvent?) {
                if(current() && event!=null) gathering.error(event.errorCode)
            }
            private fun receive(track: MediaStreamTrack?) {
                if (!current()) return
                track?.setEnabled(true)
                if (track is VideoTrack) onVideoTrackReady(track)
                if (track is AudioTrack) {
                    synchronized(audioLock) {
                        if(!current()) return
                        audioTracks.add(track); track.setVolume(audioSession.gain*peakGuard.effective)
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
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, RtpTransceiver.RtpTransceiverInit(
            if(microphoneAllowed) RtpTransceiver.RtpTransceiverDirection.SEND_RECV else RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        val video = pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, receive)
        val capabilities = factory.getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
        // Select supported codecs before creating/setLocalDescription. Never forge profile-level-id.
        val selection=CodecPreference.select(capabilities,effectiveCodec,compatibility || streamResolution==StreamResolution.HD_720,compatibility)
        codecTrace.selected(selection)
        val result=video.setCodecPreferences(selection.codecs)
        if(result.isError) {
            codecTrace.preferencesRejected()
            throw IOException("SDP: preferências de codec rejeitadas pelo WebRTC")
        }
        listOf("control" to "controlV1", "input" to "1.0", "message" to "messageV1", "chat" to "chatV1").forEach { (name, protocol) ->
            val channel = pc.createDataChannel(name, DataChannel.Init().apply { ordered = true; this.protocol = protocol })
                ?: throw IOException("CHANNELS: não foi possível criar $name")
            channels[name] = channel
            channel.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(amount: Long) {}
                override fun onStateChange() {
                    if(!current()) return
                    if (channel.state() == DataChannel.State.OPEN) dispatch(handshake.onOpen(name))
                    else if (channel.state() == DataChannel.State.CLOSED && name != "chat") fail("CHANNELS: $name fechado")
                }
                override fun onMessage(buffer: DataChannel.Buffer?) {
                    if (!current() || buffer == null) return
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
        val description = SessionDescription(original.type, StreamProfile.receiveSdp(original.description, streamResolution,compatibility || effectiveCodec==VideoCodecMode.H264_BASELINE,maxBitrateKbps))
        codecTrace.localOffer(description.description)
        setDescription("SDP_LOCAL") { observer -> pc.setLocalDescription(observer, description) }
        val candidates=gathering.awaitCandidates()
        iceReports.add(gathering.diagnostics(attempt))
        if (candidates.isEmpty()) throw LocalIceUnavailableException(attempt,iceDiagnostics())
        return description.description to candidates
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
                initialTouchDimensions = inputDimensions,desktop=desktopInput
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
                                micBytes=report.statsMap.values.filter { it.type=="outbound-rtp" && (it.members["kind"]=="audio" || it.members["mediaType"]=="audio") }
                                    .sumOf { (it.members["bytesSent"] as? Number)?.toLong() ?: 0L }
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
            if ((nativeTouch || nativeKeyboardMouse) && added && state == GamepadState()) return
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
    fun desktopKey(key: NativeKey) { synchronized(pumpLock) { if(nativeKeyboardMouse && !closed.get()) inputPump?.desktopKey(key) } }
    fun desktopMouse(mouse: NativeMouse) { synchronized(pumpLock) { if(nativeKeyboardMouse && !closed.get()) inputPump?.desktopMouse(mouse) } }
    fun releaseDesktop() { synchronized(pumpLock) { if(nativeKeyboardMouse && !closed.get()) inputPump?.releaseDesktop() } }
    fun resetInput() { synchronized(pumpLock) {
        gamepads.keys.toList().forEach { gamepads[it]=GamepadState(); inputPump?.update(GamepadState(),it) }
        pendingTouch.release()
        if(nativeKeyboardMouse) inputPump?.releaseDesktop()
    } }

    override suspend fun setRemoteAnswer(answerSdp: String) {
        val pc = peerConnection ?: throw IOException("TRANSPORT: PeerConnection ausente")
        codecTrace.remoteAnswer(answerSdp)
        withTimeout(10_000) { setDescription("SDP_REMOTE") { pc.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, answerSdp)) } }
        codecTrace.answerApplied()
    }

    override fun onOfferPosted() { codecTrace.offerPosted() }

    override suspend fun addRemoteCandidates(candidates: List<IceCandidate>) {
        val pc = peerConnection ?: throw IOException("TRANSPORT: PeerConnection ausente")
        probingIpv6=preferIpv6 && candidates.any(IceFamilyPolicy::isIpv6) && candidates.any { !IceFamilyPolicy.isIpv6(it) }
        try {
            val attempt=IceFamilyPolicy.add(candidates,preferIpv6,add={ candidate ->
                if(closed.get() || peerConnection!==pc) throw IOException("TRANSPORT: sessão encerrada")
                pc.addIceCandidate(candidate)
            },awaitPreferred={ kotlinx.coroutines.selects.select<Unit> {
                connected.onAwait { };ipv6ProbeFailed.onAwait { }
            } })
            if(attempt.ipv6First) iceReports.add("ICE: IPv6 primeiro; IPv4 preservado após até ${IceFamilyPolicy.IPV6_HEAD_START_MS} ms; recusados: ${attempt.rejected}")
        } finally { probingIpv6=false }
    }

    override suspend fun awaitReady() {
        withTimeout(30_000) { connected.await(); channelsReady.await() }
    }

    private suspend fun setDescription(stage: String, set: (SdpObserver) -> Unit) {
        val done = CompletableDeferred<Unit>()
        set(object : DescriptionObserver() {
            override fun onSetSuccess() { done.complete(Unit) }
            override fun onSetFailure(error: String?) { done.completeExceptionally(IOException("$stage: setDescription falhou")) }
        })
        done.await()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(audioLock) { audioTracks.clear() }
        mainHandler.removeCallbacksAndMessages(null)
        audioDevice.setMicrophoneMute(true);audioDevice.setAudioRecordEnabled(false)
        if(micTrack!=null) { setMicrophoneEncoding(false);audioDevice.requestStopRecording() }
        micTrack?.setEnabled(false);microphoneActive=false
        audioSession.close()
        synchronized(pumpLock) { inputPump }?.close()
        statsJob?.cancel()
        scope.cancel()
        synchronized(statisticsLock) { /* Wait for any getStats request to leave JNI before disposal. */ }
        connected.cancel(); channelsReady.cancel()
        closePeerTransport()
        micTrack?.dispose();micTrack=null;micSource?.dispose();micSource=null
        factory.dispose();audioDevice.release()
    }

    /** Invalidate callbacks before closing; old CLOSED/channel events cannot poison a retry. */
    private fun closePeerTransport() {
        peerGeneration.incrementAndGet()
        iceGathering?.close();iceGathering=null
        channels.values.forEach { it.unregisterObserver(); it.close(); it.dispose() }
        channels.clear()
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
    }

    private open class DescriptionObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
