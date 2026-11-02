package com.xcloud.tclnative.webrtc

/** The service selects the actual VBR stream; these are receive-side requests, not a bandwidth guarantee. */
object StreamProfile {
    const val WIDTH = 1920
    const val HEIGHT = 1080
    const val MIN_KBPS = 20_000
    const val MAX_KBPS = 30_000
    // xCloud's TV profile is the 1080p HQ selector used by Better xCloud.
    const val OS_NAME = "tizen"

    fun receiveSdp(raw: String): String {
        val sections = mutableListOf<MutableList<String>>()
        raw.replace("\r\n", "\n").lineSequence().filter { it.isNotBlank() }.forEach { line ->
            if (sections.isEmpty() || line.startsWith("m=")) sections.add(mutableListOf())
            sections.last().add(line)
        }
        sections.filter { it.first().startsWith("m=video ") }.forEach { video ->
            video.removeAll { it.startsWith("b=AS:") || it.startsWith("b=TIAS:") }
            val connection = video.indexOfLast { it.startsWith("c=") }
            val index = if (connection >= 0) connection + 1 else 1
            video.addAll(index, listOf("b=AS:$MAX_KBPS", "b=TIAS:${MAX_KBPS * 1000}"))
            val h264 = video.filter { it.startsWith("a=rtpmap:") && it.substringAfter(' ').startsWith("H264/", true) }
                .map { it.substringAfter(':').substringBefore(' ') }.toSet()
            h264.forEach { payload ->
                val fmtp = video.indexOfFirst { it.startsWith("a=fmtp:$payload ") }
                val previous = if (fmtp >= 0) video[fmtp].substringAfter(' ').split(';') else emptyList()
                val kept = previous.map(String::trim).filter { it.isNotEmpty() && !it.startsWith("x-google-min-bitrate=") &&
                    !it.startsWith("x-google-start-bitrate=") && !it.startsWith("x-google-max-bitrate=") }
                val parameters = kept + listOf("x-google-min-bitrate=$MIN_KBPS", "x-google-start-bitrate=$MIN_KBPS", "x-google-max-bitrate=$MAX_KBPS")
                val value = "a=fmtp:$payload ${parameters.joinToString(";")}"
                if (fmtp >= 0) video[fmtp] = value else video.add(value)
            }
        }
        return sections.flatten().joinToString("\r\n", postfix = "\r\n")
    }
}
