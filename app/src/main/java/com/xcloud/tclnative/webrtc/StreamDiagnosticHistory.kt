package com.xcloud.tclnative.webrtc

/** Local, bounded metrics only: no account identifiers, IP addresses, tokens or SDP. */
class StreamDiagnosticHistory {
    private data class Sample(val time: Long,val metrics: StreamMetrics,val input: InputMetrics)
    private val samples=java.util.ArrayDeque<Sample>()
    @Synchronized fun clear() { samples.clear() }
    @Synchronized fun add(now: Long,metrics: StreamMetrics,input: InputMetrics) {
        if(samples.size>=20) samples.removeFirst()
        samples.addLast(Sample(now,metrics,input))
    }
    @Synchronized fun receivingVideo(now: Long): Boolean? = samples.lastOrNull()?.takeIf { now-it.time<=2500 }
        ?.metrics?.receivedVideoBytes?.let { it>0 }
    @Synchronized fun report(): String = if(samples.isEmpty()) "Aguardando amostras de vídeo." else {
        val start=samples.first.time
        samples.joinToString("\n") { s ->
            val m=s.metrics
            "+${(s.time-start)/1000}s RX ${m.receivedVideoBytes ?: "n/d"} B | decode ${m.decodedFrames ?: "n/d"} quadros | " +
                "${m.width ?: "?"}x${m.height ?: "?"} | buffer ${m.jitterBufferMs?.toInt() ?: "?"} ms | " +
                "perda ${m.lostPackets ?: "?"} | ${m.decoder ?: "decoder n/d"}"
        }
    }
}
