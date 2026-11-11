package com.xcloud.tclnative.webrtc

import com.xcloud.tclnative.i18n.UiText

import java.util.Locale

/** A small, testable view of RTCStats. No IP addresses, credentials or SDP enter the overlay. */
data class RtcSample(val id: String, val type: String, val timestampUs: Double, val values: Map<String, Any>)

data class StreamMetrics(
    val width: Int? = null, val height: Int? = null, val fps: Double? = null,
    val bitrateMbps: Double? = null, val rttMs: Double? = null, val jitterMs: Double? = null,
    val jitterBufferMs: Double? = null, val decodeMs: Double? = null,
    val droppedFrames: Long? = null, val totalDroppedFrames: Long? = null,
    val lostPackets: Long? = null, val totalLostPackets: Long? = null, val packetLossPercent: Double? = null,
    val codec: String? = null, val decoder: String? = null, val powerEfficient: Boolean? = null,
    val intervalSeconds: Double? = null,
    val receivedVideoBytes: Long? = null, val decodedFrames: Long? = null,
    val receivedPackets: Long? = null, val totalReceivedVideoBytes: Long? = null, val videoStreamId: String? = null,
    val transportReceivedBytes: Long? = null, val transportSentBytes: Long? = null, val transportId: String? = null,
    val jitterBufferTargetMs: Double? = null, val jitterBufferMinimumMs: Double? = null,
    val jitterBufferEmittedFrames: Long? = null
) {
    fun compactOverlay(input: InputMetrics): String {
        fun n(value: Double?, decimals: Int = 1) = value?.takeIf(Double::isFinite)
            ?.let { String.format(Locale.US, "%.${decimals}f", it) } ?: "n/d"
        val resolution = if (width != null && height != null) "${width}x$height" else "n/d"
        return UiText.join("$resolution | ${n(fps, 0)} FPS | ${codec ?: "n/d"} | RX ${n(bitrateMbps)} Mbps\n","RTT ${n(rttMs)} ms | Jitter ${n(jitterMs)} ms | Buffer ${n(jitterBufferMs)} ms | Decode ${n(decodeMs, 2)} ms\n","${UiText.text("Perda")} ${n(packetLossPercent, 2)}% (${lostPackets ?: "n/d"} pct) | ${UiText.text("Quadros desc.")} ${droppedFrames ?: "n/d"} | Input ${n(input.maxSchedulerDelayMs)} ms / ${input.bufferedBytes} B\n",UiText.text("DZ app: câmera/LT/RT 0% | LT {0}% RT {1}% | Menu+View: Partida\n",UiText.nested(n(input.sentState.leftTrigger * 100.0)),UiText.nested(n(input.sentState.rightTrigger * 100.0))))
    }

    fun overlay(input: InputMetrics, renderer: String, profile: StreamResolution = StreamResolution.HD_1080_HQ, compatibility: Boolean = false): String {
        fun number(value: Double?, decimals: Int = 1): String = value?.takeIf(Double::isFinite)
            ?.let { String.format(Locale.US, "%.${decimals}f", it) } ?: "n/d"
        val resolution = if (width != null && height != null) "${width}x$height" else "n/d"
        val efficiency = when (powerEfficient) { true -> UiText.text("eficiente: sim"); false -> UiText.text("eficiente: não"); null -> "HW/SW: n/d" }
        return UiText.join("$resolution | ${number(fps, 0)} FPS | ${codec ?: "codec n/d"} | RX ${number(bitrateMbps)} Mbps\n","Ping (RTT ICE): ${number(rttMs)} ms | Jitter RTP: ${number(jitterMs)} ms | Buffer: ${number(jitterBufferMs)} ms\n",UiText.text("Buffer WebRTC: observado {0} ms | alvo {1} ms | mínimo de rede {2} ms\n",UiText.nested(number(jitterBufferMs)),UiText.nested(number(jitterBufferTargetMs)),UiText.nested(number(jitterBufferMinimumMs))),"${UiText.text("Perda")}: ${number(packetLossPercent, 2)}% (${lostPackets ?: "n/d"} ${UiText.text("pacotes")}/${number(intervalSeconds)}s; ${UiText.text("total")} ${totalLostPackets ?: "n/d"})\n","${UiText.text("Quadros descartados WebRTC")}: ${droppedFrames ?: "n/d"}/${UiText.text("intervalo")}; ${UiText.text("total")} ${totalDroppedFrames ?: "n/d"}\n","Decode: ${number(decodeMs, 2)} ${UiText.text("ms/quadro")} | ${decoder ?: "decoder n/d"} | $efficiency\n",UiText.text("Controle: fila {0} B | atraso local máx. {1} ms | coalescidos {2} | espera canal {3}\n",UiText.nested(input.bufferedBytes),UiText.nested(number(input.maxSchedulerDelayMs, 2)),UiText.nested(input.coalescedEdges),UiText.nested(input.backpressureSkips)),UiText.text("Câmera X/Y: {0}/{1} | LT/RT: {2}%/{3}% | DZ app: 0% | Input até 250 Hz\n",UiText.nested(number(input.sentState.rightX.toDouble(), 4)),UiText.nested(number(input.sentState.rightY.toDouble(), 4)),UiText.nested(number(input.sentState.leftTrigger * 100.0, 1)),UiText.nested(number(input.sentState.rightTrigger * 100.0, 1))),UiText.text("Pedido: {0}, {1} Mbps (VBR recebido pode variar) | Menu+View: Partida; INFO/F10: estatísticas\n{2}",UiText.nested(profile.label),UiText.nested(if(compatibility) "1–6" else "${profile.minKbps/1000}–${profile.maxKbps/1000}"),UiText.nested(renderer)))
    }
}

class StreamStatsCollector {
    private var previous: RtcSample? = null
    private fun RtcSample.number(name: String) = (values[name] as? Number)?.toDouble()?.takeIf(Double::isFinite)
    private fun RtcSample.text(name: String) = (values[name] as? String)?.takeIf { it.isNotBlank() }

    @Synchronized fun collect(samples: Collection<RtcSample>): StreamMetrics {
        val byId = samples.associateBy { it.id }
        val video = samples.filter { it.type == "inbound-rtp" && it.values["isRemote"] != true &&
            it.values["active"] != false && (it.text("kind") ?: it.text("mediaType")) == "video" &&
            byId[it.text("codecId")]?.text("mimeType")?.lowercase(Locale.ROOT) !in setOf("video/rtx", "video/ulpfec", "video/flexfec-03") }
            .maxByOrNull { it.number("bytesReceived") ?: 0.0 }
        if (video == null) { previous = null; return StreamMetrics() }
        val old = previous?.takeIf { it.id == video.id && it.timestampUs < video.timestampUs &&
            (video.number("bytesReceived") == null || it.number("bytesReceived") == null ||
                video.number("bytesReceived")!! >= it.number("bytesReceived")!!) }
        previous = video
        val seconds = old?.let { (video.timestampUs - it.timestampUs) / 1_000_000 }?.takeIf { it > 0 }
        fun delta(key: String): Double? {
            val before = old?.number(key) ?: return null
            val after = video.number(key) ?: return null
            return (after - before).takeIf { it >= 0 }
        }
        val decoded = delta("framesDecoded")
        val received = delta("packetsReceived")
        // packetsLost is signed: late arrival corrections must not become a negative loss percentage.
        val lost = old?.number("packetsLost")?.let { before -> video.number("packetsLost")?.let { (it - before).coerceAtLeast(0.0) } }
        val codecSample=byId[video.text("codecId")]
        val codec = codecSample?.text("mimeType")?.substringAfter('/')?.let {
            CodecPreference.label(it,CodecPreference.parseParameters(codecSample.text("sdpFmtpLine") ?: ""))
        }
        val transport = byId[video.text("transportId")]
        val selectedId = transport?.text("selectedCandidatePairId")
            ?: samples.firstOrNull { it.type == "transport" && it.text("selectedCandidatePairId") != null }?.text("selectedCandidatePairId")
        val pair = if (selectedId != null) byId[selectedId]?.takeIf { it.type == "candidate-pair" }
            else samples.firstOrNull { it.type == "candidate-pair" && it.text("state") == "succeeded" &&
                (it.values["selected"] == true || it.values["nominated"] == true) }
        val emitted = delta("jitterBufferEmittedCount")
        fun bufferMs(key: String) = if (emitted != null && emitted > 0)
            delta(key)?.times(1000)?.div(emitted) else null
        return StreamMetrics(
            width = video.number("frameWidth")?.toInt()?.takeIf { it > 0 },
            height = video.number("frameHeight")?.toInt()?.takeIf { it > 0 },
            fps = video.number("framesPerSecond") ?: if (seconds != null && decoded != null) decoded / seconds else null,
            bitrateMbps = if (seconds != null) delta("bytesReceived")?.times(8)?.div(seconds * 1_000_000) else null,
            rttMs = pair?.number("currentRoundTripTime")?.takeIf { it >= 0 }?.times(1000),
            jitterMs = video.number("jitter")?.takeIf { it >= 0 }?.times(1000),
            jitterBufferMs = bufferMs("jitterBufferDelay"),
            jitterBufferTargetMs = bufferMs("jitterBufferTargetDelay"),
            jitterBufferMinimumMs = bufferMs("jitterBufferMinimumDelay"),
            jitterBufferEmittedFrames = emitted?.toLong(),
            decodeMs = if (decoded != null && decoded > 0) delta("totalDecodeTime")?.times(1000)?.div(decoded) else null,
            droppedFrames = delta("framesDropped")?.toLong(), totalDroppedFrames = video.number("framesDropped")?.toLong(),
            lostPackets = lost?.toLong(), totalLostPackets = video.number("packetsLost")?.coerceAtLeast(0.0)?.toLong(),
            packetLossPercent = if (received != null && lost != null && received + lost > 0) 100 * lost / (received + lost) else null,
            codec = codec, decoder = video.text("decoderImplementation"), powerEfficient = video.values["powerEfficientDecoder"] as? Boolean,
            intervalSeconds = seconds, receivedVideoBytes = delta("bytesReceived")?.toLong(), decodedFrames = decoded?.toLong(),
            receivedPackets=received?.toLong(),totalReceivedVideoBytes=video.number("bytesReceived")?.takeIf { it>=0 }?.toLong(),
            videoStreamId=video.id,transportReceivedBytes=transport?.number("bytesReceived")?.takeIf { it>=0 }?.toLong(),
            transportSentBytes=transport?.number("bytesSent")?.takeIf { it>=0 }?.toLong(),transportId=transport?.id
        )
    }
}
