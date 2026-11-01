package com.xcloud.tclnative.webrtc

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.webrtc.*
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class VideoStats(val bytes: Long, val frames: Long, val decoded: Long, val codec: String, val decoder: String)

class XCloudWebRtcClient(
    context: Context,
    eglBase: EglBase,
    private val onVideoTrackReady: (VideoTrack) -> Unit,
    private val onConnectionStateChanged: (String) -> Unit,
    private val onServerDialog: (String, String, (Boolean) -> Unit) -> Unit = { _, _, reply -> reply(false) }
) : StreamingPeer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closed = AtomicBoolean(false)
    private val failure = AtomicReference<Exception?>()
    private val candidates = ConcurrentLinkedQueue<IceCandidate>()
    private val gathering = CompletableDeferred<Unit>()
    private val connected = CompletableDeferred<Unit>()
    private val channelsReady = CompletableDeferred<Unit>()
    private val handshake = ChannelHandshake()
    private val channels = java.util.concurrent.ConcurrentHashMap<String, DataChannel>()
    private val gamepad = AtomicReference(GamepadState())
    private val trackIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val inputStarted = AtomicBoolean(false)
    private val configurationSent = AtomicBoolean(false)
    private val nativeLock = Any()
    private val factory: PeerConnectionFactory
    private var peer: PeerConnection? = null

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
            .setEnableInternalTracer(false).createInitializationOptions())
        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)).createPeerConnectionFactory()
    }

    override suspend fun createOfferSdp(): Pair<String, List<IceCandidate>> {
        checkHealthy()
        check(peer == null) { "RTC: esta conexão já tem uma oferta" }
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        val pc = native { factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                if (!closed.get()) onConnectionStateChanged("ICE: ${state?.name}")
                if (state == PeerConnection.IceConnectionState.FAILED) fail(IOException("ICE: falha de conectividade de rede"))
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                if (closed.get()) return
                onConnectionStateChanged("TRANSPORT: ${state?.name}")
                if (state == PeerConnection.PeerConnectionState.CONNECTED) connected.complete(Unit)
                if (state == PeerConnection.PeerConnectionState.FAILED || state == PeerConnection.PeerConnectionState.CLOSED)
                    fail(IOException("TRANSPORT: ${state.name}"))
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) gathering.complete(Unit)
            }
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null && !closed.get()) candidates.add(candidate)
            }
            override fun onIceCandidatesRemoved(removed: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {
                if (!closed.get()) runCatching { native { stream?.videoTracks?.forEach(::attach) } }
                    .onFailure { fail(IOException("VIDEO_TRACK: falha ao anexar stream", it)) }
            }
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                if (!closed.get()) runCatching { native { (receiver?.track() as? VideoTrack)?.let(::attach) } }
                    .onFailure { fail(IOException("VIDEO_TRACK: falha ao receber track", it)) }
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                if (!closed.get()) runCatching { native { (transceiver?.receiver?.track() as? VideoTrack)?.let(::attach) } }
                    .onFailure { fail(IOException("VIDEO_TRACK: falha ao receber transceiver", it)) }
            }
            override fun onDataChannel(channel: DataChannel?) {
                if (!closed.get()) runCatching { channel?.let(::register) }.onFailure { fail(IOException("CHANNELS: registro falhou", it)) }
            }
            override fun onRenegotiationNeeded() {}
        })?.also { peer = it } ?: throw IOException("RTC: não foi possível criar PeerConnection") }
        native {
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        val video = pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        val codecs = factory.getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
        check(codecs.any { it.name.equals("H264", true) }) { "RTC: nenhum decoder H.264 disponível" }
        video.setCodecPreferences(codecs.sortedBy { if (it.name.equals("H264", true)) 0 else 1 })
        listOf("control" to "controlV1", "input" to "1.0", "message" to "messageV1", "chat" to "chatV1").forEach { (name, protocol) ->
            register(pc.createDataChannel(name, DataChannel.Init().apply { ordered = true; this.protocol = protocol })
                ?: throw IOException("RTC: não foi possível criar canal $name"))
        }
        }
        val offer = CompletableDeferred<SessionDescription>()
        native { pc.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) offer.completeExceptionally(IOException("SDP: oferta vazia")) else offer.complete(sdp)
            }
            override fun onCreateFailure(error: String?) { offer.completeExceptionally(IOException("SDP: createOffer falhou")) }
        }, MediaConstraints()) }
        val description = waitFor(offer, "SDP: timeout ao criar oferta")
        val local = CompletableDeferred<Unit>()
        native { pc.setLocalDescription(object : SdpAdapter() {
            override fun onSetSuccess() { local.complete(Unit) }
            override fun onSetFailure(error: String?) { local.completeExceptionally(IOException("SDP: setLocalDescription falhou")) }
        }, description) }
        waitFor(local, "SDP: timeout ao aplicar oferta")
        waitFor(gathering, "ICE_LOCAL: coleta não foi concluída")
        checkHealthy()
        val gathered = candidates.toList()
        check(gathered.isNotEmpty()) { "ICE_LOCAL: nenhum candidato encontrado" }
        Log.i(TAG, "ICE_LOCAL: ${gathered.size} candidatos coletados")
        // Use exactly the description accepted by libwebrtc, including its ICE credentials.
        return native { (pc.localDescription?.description ?: throw IOException("SDP: descrição local ausente")) to gathered }
    }

    override suspend fun setRemoteAnswer(answerSdp: String) {
        checkHealthy()
        val done = CompletableDeferred<Unit>()
        val pc = peer ?: throw IOException("RTC: conexão não inicializada")
        native { pc.setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() { done.complete(Unit) }
            override fun onSetFailure(error: String?) { done.completeExceptionally(IOException("SDP_ANSWER: resposta recusada pela libwebrtc")) }
        }, SessionDescription(SessionDescription.Type.ANSWER, answerSdp)) }
        waitFor(done, "SDP_ANSWER: timeout ao aplicar resposta")
    }
    override suspend fun addRemoteCandidates(candidates: List<IceCandidate>) {
        checkHealthy()
        val pc = peer ?: throw IOException("RTC: conexão não inicializada")
        for (candidate in candidates) {
            val added = CompletableDeferred<Unit>()
            native { pc.addIceCandidate(candidate, object : AddIceObserver {
                override fun onAddSuccess() { added.complete(Unit) }
                override fun onAddFailure(error: String?) { added.completeExceptionally(IOException("ICE_REMOTE: candidato recusado")) }
            }) }
            waitFor(added, "ICE_REMOTE: timeout ao aplicar candidato")
        }
    }
    override suspend fun awaitReady() {
        waitFor(connected, "TRANSPORT: ICE/DTLS não conectou", 30_000)
        waitFor(channelsReady, "CHANNELS: servidor não confirmou handshake", 20_000)
    }
    private suspend fun <T> waitFor(value: CompletableDeferred<T>, error: String, timeout: Long = 10_000): T =
        withTimeoutOrNull(timeout) { value.await() } ?: throw IOException(error)

    private fun attach(track: VideoTrack) {
        if (!closed.get() && trackIds.add(track.id())) {
            track.setEnabled(true)
            onVideoTrackReady(track)
        }
    }
    private fun register(channel: DataChannel) = native {
        val name = channel.label()
        val old = channels.putIfAbsent(name, channel)
        if (old != null && old !== channel) { channel.close(); channel.dispose(); return@native }
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                if (closed.get()) return
                try {
                    when (native { channel.state() }) {
                        DataChannel.State.OPEN -> dispatch(handshake.onOpen(name))
                        DataChannel.State.CLOSED -> if (name != "chat") fail(IOException("CHANNELS: canal $name encerrado"))
                        else -> Unit
                    }
                } catch (e: Exception) { fail(e) }
            }
            override fun onMessage(buffer: DataChannel.Buffer?) {
                if (closed.get() || buffer == null || name != "message") return
                try {
                    val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.duplicate().get(it) }
                    val json = JSONObject(String(bytes, Charsets.UTF_8))
                    when (json.optString("type")) {
                        "HandshakeAck" -> dispatch(handshake.onHandshakeAck())
                        "Message", "TransactionStart" -> handleMessage(json)
                    }
                } catch (e: Exception) { fail(IOException("CHANNELS: mensagem inválida", e)) }
            }
        })
        if (channel.state() == DataChannel.State.OPEN) dispatch(handshake.onOpen(name))
    }
    private fun dispatch(messages: List<ChannelMessage>) {
        messages.forEach { send(it.channel, it.bytes) }
        if (messages.any { it.channel == "control" }) configurationSent.set(true)
        if (handshake.ready && configurationSent.get() && inputStarted.compareAndSet(false, true)) {
            send("input", GamepadPacket.metadata(SystemClock.elapsedRealtime().toDouble()))
            channelsReady.complete(Unit)
            scope.launch {
                var sequence = 0
                while (isActive && !closed.get()) {
                    try {
                        send("input", GamepadPacket.encode(gamepad.get(), ++sequence, SystemClock.elapsedRealtime().toDouble()), dropIfBusy = true)
                    } catch (e: Exception) { fail(e); break }
                    delay(16)
                }
            }
        }
    }
    private fun handleMessage(json: JSONObject) {
        when (json.optString("target")) {
            "/streaming/sessionLifetimeManagement/serverInitiatedDisconnect" -> {
                reply(json.getString("id"), JSONObject())
                fail(IOException("SESSION: servidor encerrou o streaming"))
            }
            "/streaming/systemUi/messages/ShowMessageDialog" -> {
                val content = JSONObject(json.getString("content"))
                onServerDialog(content.optString("TitleText"), content.optString("ContentText")) { accept ->
                    if (!closed.get()) runCatching { reply(json.getString("id"), JSONObject().put("Result", if (accept) 0 else 1)) }
                        .onFailure { fail(IOException("CHANNELS: falha ao responder diálogo", it)) }
                }
            }
        }
    }
    private fun reply(id: String, content: JSONObject) = send("message", ChannelHandshake.encode("message", JSONObject()
        .put("type", "TransactionComplete").put("id", id).put("cv", "").put("content", content.toString())).bytes)
    private fun send(name: String, bytes: ByteArray, dropIfBusy: Boolean = false) = native {
        val channel = channels[name] ?: throw IOException("CHANNELS: canal $name ausente")
        check(channel.state() == DataChannel.State.OPEN) { "CHANNELS: canal $name não está aberto" }
        if (dropIfBusy && channel.bufferedAmount() > 64 * 1024) return@native
        if (!channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))) throw IOException("CHANNELS: envio falhou em $name")
    }
    fun updateGamepad(state: GamepadState) { gamepad.set(state) }
    private inline fun <T> native(block: () -> T): T = synchronized(nativeLock) {
        checkHealthy()
        block()
    }
    fun checkHealthy() {
        failure.get()?.let { throw it }
        check(!closed.get()) { "RTC: sessão encerrada" }
    }
    private fun fail(error: Exception) {
        if (!closed.get() && failure.compareAndSet(null, error)) {
            Log.e(TAG, error.message ?: "Falha RTC")
            connected.completeExceptionally(error)
            channelsReady.completeExceptionally(error)
            gathering.completeExceptionally(error)
        }
    }
    suspend fun videoStats(): VideoStats {
        checkHealthy()
        return withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine { continuation ->
                val pc = peer
                if (pc == null) continuation.resumeWithException(IOException("RTC: conexão ausente"))
                else native { pc.getStats { report ->
                    val values = report.statsMap.values
                    val video = values.firstOrNull { it.type == "inbound-rtp" &&
                        (it.members["kind"] == "video" || it.members["mediaType"] == "video") }
                    fun number(key: String) = (video?.members?.get(key) as? Number)?.toLong() ?: 0L
                    val codec = report.statsMap[video?.members?.get("codecId")]?.members?.get("mimeType")?.toString().orEmpty()
                    if (continuation.isActive) continuation.resume(VideoStats(number("bytesReceived"), number("framesReceived"),
                        number("framesDecoded"), codec, video?.members?.get("decoderImplementation")?.toString().orEmpty()))
                } }
            }
        } ?: throw IOException("RTC_STATS: tempo limite")
    }
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        connected.cancel(); channelsReady.cancel(); gathering.cancel()
        val (oldChannels, oldPeer) = synchronized(nativeLock) {
            val handles = channels.values.toList() to peer
            channels.clear(); peer = null
            handles
        }
        // Release the lock before dispose(): native teardown can wait for observer callbacks.
        oldChannels.forEach { channel -> runCatching { channel.unregisterObserver(); channel.close(); channel.dispose() } }
        oldPeer?.let { runCatching { it.dispose() } }
        factory.dispose()
    }
    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
    companion object { private const val TAG = "XCloudWebRtcClient" }
}
