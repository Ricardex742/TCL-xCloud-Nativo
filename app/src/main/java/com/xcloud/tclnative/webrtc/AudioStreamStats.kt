package com.xcloud.tclnative.webrtc

import com.xcloud.tclnative.i18n.UiText
import java.util.Locale

/** Network/NetEq observations, not AudioTrack latency or a measurement of audiovisual sync. */
data class AudioStreamMetrics(val codec: String? = null, val jitterMs: Double? = null,
    val bufferMs: Double? = null, val targetMs: Double? = null, val lostPackets: Long? = null) {
    fun diagnostics(): String {
        fun n(v: Double?) = v?.takeIf { it.isFinite() && it >= 0 }?.let { String.format(Locale.US, "%.2f", it) } ?: "n/d"
        return UiText.text("Áudio RTP (última amostra): {0} | jitter {1} ms | buffer WebRTC {2} ms | alvo {3} ms | perdas {4}",
            UiText.nested(codec ?: "n/d"), UiText.nested(n(jitterMs)), UiText.nested(n(bufferMs)), UiText.nested(n(targetMs)), UiText.nested(lostPackets ?: "n/d")) + "\n" +
            UiText.text("O buffer de áudio WebRTC não mede o atraso dos alto-falantes nem o sincronismo completo.")
    }
}

class AudioStreamStatsCollector {
    private var previous: RtcSample? = null
    private fun RtcSample.number(key: String) = (values[key] as? Number)?.toDouble()?.takeIf(Double::isFinite)
    @Synchronized fun collect(samples: Collection<RtcSample>): AudioStreamMetrics {
        val audio = samples.filter { it.type == "inbound-rtp" && it.values["isRemote"] != true && it.values["active"] != false &&
            (it.values["kind"] ?: it.values["mediaType"]) == "audio" }.maxByOrNull { it.number("bytesReceived") ?: 0.0 }
        if (audio == null) { previous = null; return AudioStreamMetrics() }
        val old = previous?.takeIf { it.id == audio.id && it.timestampUs < audio.timestampUs &&
            (audio.number("bytesReceived") == null || it.number("bytesReceived") == null || audio.number("bytesReceived")!! >= it.number("bytesReceived")!!) }
        previous = audio
        fun delta(key: String) = old?.number(key)?.let { before -> audio.number(key)?.let { after -> (after-before).takeIf { it >= 0 } } }
        val emitted = delta("jitterBufferEmittedCount")?.takeIf { it > 0 }
        fun buffer(key: String) = emitted?.let { delta(key)?.times(1000)?.div(it) }
        val codec = samples.firstOrNull { it.id == audio.values["codecId"] }?.values?.get("mimeType") as? String
        val lost = old?.number("packetsLost")?.let { before -> audio.number("packetsLost")?.let { (it-before).coerceAtLeast(0.0).toLong() } }
        return AudioStreamMetrics(codec?.substringAfter('/')?.take(40), audio.number("jitter")?.takeIf { it >= 0 }?.times(1000),
            buffer("jitterBufferDelay"), buffer("jitterBufferTargetDelay"), lost)
    }
}
