package com.xcloud.tclnative.webrtc

import com.xcloud.tclnative.i18n.UiText

/** Device profile plus the official control-channel resolution alias; the service chooses the received stream. */
enum class StreamResolution(private val sourceLabel: String, val width: Int, val height: Int, val osName: String,
                            val minKbps: Int, val maxKbps: Int,val resolutionAlias: String) {
    HD_720("720p — mais leve",1280,720,"android",5000,12000,"720"),
    HD_1080("1080p",1920,1080,"windows",10000,20000,"1080"),
    HD_1080_HQ("1080p HQ",1920,1080,"tizen",20000,30000,"1080HQ"),
    QHD_1440("1440p — 2560×1440",2560,1440,"tizen",20000,40000,"1440");
    val label: String get()=UiText.text(sourceLabel)
}

/** The service selects the actual VBR stream; these are receive-side requests, not a bandwidth guarantee. */
object StreamProfile {
    const val WIDTH = 1920
    const val HEIGHT = 1080
    const val MIN_KBPS = 20_000
    const val MAX_KBPS = 30_000
    // xCloud's TV profile is the 1080p HQ selector used by Better xCloud.
    const val OS_NAME = "tizen"

    fun receiveSdp(raw: String, profile: StreamResolution = StreamResolution.HD_1080_HQ, compatibility: Boolean = false, maxBitrateKbps: Int = 0): String {
        val maxKbps=if(maxBitrateKbps==0) (if(compatibility) 6000 else profile.maxKbps) else maxBitrateKbps.coerceIn(1000,50000)
        val minKbps=(if(compatibility) 1000 else profile.minKbps).coerceAtMost(maxKbps)
        val startKbps=(if(compatibility) 2500 else profile.minKbps).coerceAtMost(maxKbps)
        val sections = mutableListOf<MutableList<String>>()
        raw.replace("\r\n", "\n").lineSequence().filter { it.isNotBlank() }.forEach { line ->
            if (sections.isEmpty() || line.startsWith("m=")) sections.add(mutableListOf())
            sections.last().add(line)
        }
        sections.filter { it.first().startsWith("m=video ") }.forEach { video ->
            video.removeAll { it.startsWith("b=AS:") || it.startsWith("b=TIAS:") }
            val connection = video.indexOfLast { it.startsWith("c=") }
            val index = if (connection >= 0) connection + 1 else 1
            video.addAll(index, listOf("b=AS:$maxKbps", "b=TIAS:${maxKbps * 1000}"))
            val videoCodecs = video.filter { it.startsWith("a=rtpmap:") &&
                (it.substringAfter(' ').startsWith("H264/", true) || it.substringAfter(' ').startsWith("H265/",true) || it.substringAfter(' ').startsWith("VP9/",true) || it.substringAfter(' ').startsWith("AV1/",true)) }
                .map { it.substringAfter(':').substringBefore(' ') }.toSet()
            videoCodecs.forEach { payload ->
                val fmtp = video.indexOfFirst { it.startsWith("a=fmtp:$payload ") }
                val previous = if (fmtp >= 0) video[fmtp].substringAfter(' ').split(';') else emptyList()
                val kept = previous.map(String::trim).filter { it.isNotEmpty() && !it.startsWith("x-google-min-bitrate=") &&
                    !it.startsWith("x-google-start-bitrate=") && !it.startsWith("x-google-max-bitrate=") }
                val parameters = kept + listOf("x-google-min-bitrate=$minKbps", "x-google-start-bitrate=$startKbps", "x-google-max-bitrate=$maxKbps")
                val value = "a=fmtp:$payload ${parameters.joinToString(";")}"
                if (fmtp >= 0) video[fmtp] = value else video.add(value)
            }
        }
        return sections.flatten().joinToString("\r\n", postfix = "\r\n")
    }
}
