package com.xcloud.tclnative.webrtc

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
    val receivedVideoBytes: Long? = null, val decodedFrames: Long? = null
) {
    fun compactOverlay(input: InputMetrics): String {
        fun n(value: Double?, decimals: Int = 1) = value?.takeIf(Double::isFinite)
            ?.let { String.format(Locale.US, "%.${decimals}f", it) } ?: "n/d"
        val resolution = if (width != null && height != null) "${width}x$height" else "n/d"
        return "$resolution | ${n(fps, 0)} FPS | ${codec ?: "n/d"} | RX ${n(bitrateMbps)} Mbps\n" +
            "RTT ${n(rttMs)} ms | Jitter ${n(jitterMs)} ms | Buffer ${n(jitterBufferMs)} ms | Decode ${n(decodeMs, 2)} ms\n" +
            "Perda ${n(packetLossPercent, 2)}% (${lostPackets ?: "n/d"} pct) | Quadros desc. ${droppedFrames ?: "n/d"} | Input ${n(input.maxSchedulerDelayMs)} ms / ${input.bufferedBytes} B\n" +
            "DZ app: câmera/LT/RT 0% | LT ${n(input.sentState.leftTrigger * 100.0)}% RT ${n(input.sentState.rightTrigger * 100.0)}% | Menu+View: Partida\n"
    }

    fun overlay(input: InputMetrics, renderer: String, profile: StreamResolution = StreamResolution.HD_1080_HQ, compatibility: Boolean = false): String {
        fun number(value: Double?, decimals: Int = 1): String = value?.takeIf(Double::isFinite)
            ?.let { String.format(Locale.US, "%.${decimals}f", it) } ?: "n/d"
        val resolution = if (width != null && height != null) "${width}x$height" else "n/d"
        val efficiency = when (powerEfficient) { true -> "eficiente: sim"; false -> "eficiente: não"; null -> "HW/SW: n/d" }
        return "$resolution | ${number(fps, 0)} FPS | ${codec ?: "codec n/d"} | RX ${number(bitrateMbps)} Mbps\n" +
            "Ping (RTT ICE): ${number(rttMs)} ms | Jitter RTP: ${number(jitterMs)} ms | Buffer: ${number(jitterBufferMs)} ms\n" +
            "Perda: ${number(packetLossPercent, 2)}% (${lostPackets ?: "n/d"} pacotes/${number(intervalSeconds)}s; total ${totalLostPackets ?: "n/d"})\n" +
            "Quadros descartados WebRTC: ${droppedFrames ?: "n/d"}/intervalo; total ${totalDroppedFrames ?: "n/d"}\n" +
            "Decode: ${number(decodeMs, 2)} ms/quadro | ${decoder ?: "decoder n/d"} | $efficiency\n" +
            "Controle: fila ${input.bufferedBytes} B | atraso local máx. ${number(input.maxSchedulerDelayMs, 2)} ms | coalescidos ${input.coalescedEdges} | espera canal ${input.backpressureSkips}\n" +
            "Câmera X/Y: ${number(input.sentState.rightX.toDouble(), 4)}/${number(input.sentState.rightY.toDouble(), 4)} | LT/RT: ${number(input.sentState.leftTrigger * 100.0, 1)}%/${number(input.sentState.rightTrigger * 100.0, 1)}% | DZ app: 0% | Input até 250 Hz\n" +
            "Pedido: ${profile.label}, ${if(compatibility) "1–6" else "${profile.minKbps/1000}–${profile.maxKbps/1000}"} Mbps (VBR recebido pode variar) | Menu+View: Partida; INFO/F10: estatísticas\n$renderer"
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
        val codec = byId[video.text("codecId")]?.text("mimeType")?.substringAfter('/')
        val transport = byId[video.text("transportId")]
        val selectedId = transport?.text("selectedCandidatePairId")
            ?: samples.firstOrNull { it.type == "transport" && it.text("selectedCandidatePairId") != null }?.text("selectedCandidatePairId")
        val pair = if (selectedId != null) byId[selectedId]?.takeIf { it.type == "candidate-pair" }
            else samples.firstOrNull { it.type == "candidate-pair" && it.text("state") == "succeeded" &&
                (it.values["selected"] == true || it.values["nominated"] == true) }
        val emitted = delta("jitterBufferEmittedCount")
        return StreamMetrics(
            width = video.number("frameWidth")?.toInt()?.takeIf { it > 0 },
            height = video.number("frameHeight")?.toInt()?.takeIf { it > 0 },
            fps = video.number("framesPerSecond") ?: if (seconds != null && decoded != null) decoded / seconds else null,
            bitrateMbps = if (seconds != null) delta("bytesReceived")?.times(8)?.div(seconds * 1_000_000) else null,
            rttMs = pair?.number("currentRoundTripTime")?.takeIf { it >= 0 }?.times(1000),
            jitterMs = video.number("jitter")?.takeIf { it >= 0 }?.times(1000),
            jitterBufferMs = if (emitted != null && emitted > 0) delta("jitterBufferDelay")?.times(1000)?.div(emitted) else null,
            decodeMs = if (decoded != null && decoded > 0) delta("totalDecodeTime")?.times(1000)?.div(decoded) else null,
            droppedFrames = delta("framesDropped")?.toLong(), totalDroppedFrames = video.number("framesDropped")?.toLong(),
            lostPackets = lost?.toLong(), totalLostPackets = video.number("packetsLost")?.coerceAtLeast(0.0)?.toLong(),
            packetLossPercent = if (received != null && lost != null && received + lost > 0) 100 * lost / (received + lost) else null,
            codec = codec, decoder = video.text("decoderImplementation"), powerEfficient = video.values["powerEfficientDecoder"] as? Boolean,
            intervalSeconds = seconds, receivedVideoBytes = delta("bytesReceived")?.toLong(), decodedFrames = decoded?.toLong()
        )
    }
}
