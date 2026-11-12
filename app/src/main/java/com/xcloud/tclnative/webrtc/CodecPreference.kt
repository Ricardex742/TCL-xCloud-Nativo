package com.xcloud.tclnative.webrtc

import com.xcloud.tclnative.i18n.UiText

import com.xcloud.tclnative.settings.VideoCodecMode
import org.webrtc.RtpCapabilities
import java.io.IOException
import java.util.Locale

/** Rank only profiles advertised by the decoder; never rewrite a profile-level-id. */
object CodecPreference {
    fun rank(profile: String?, lightweight: Boolean): Int = when(profile?.take(2)?.lowercase()) {
        "42" -> if(lightweight) 0 else 2 // Baseline/constrained baseline: no CABAC or B frames.
        "4d" -> 1
        "64" -> if(lightweight) 2 else 0
        else -> 3
    }

    data class Offer(val codecs: List<RtpCapabilities.CodecCapability>, val baselineOnly: Boolean, val description: String)
    fun isBaseline(parameters: Map<String,String>): Boolean = parameters["profile-level-id"]
        ?.matches(Regex("42[0-9a-fA-F]{4}")) == true

    fun select(capabilities: List<RtpCapabilities.CodecCapability>, requested: VideoCodecMode,
               lightweight: Boolean, compatibility: Boolean): Offer {
        val baselineOnly=compatibility || requested==VideoCodecMode.H264_BASELINE
        val h264=capabilities.filter { it.name.equals("H264",true) }
        if(h264.isEmpty()) throw IOException("VIDEO: o dispositivo não oferece decodificador H.264")
        val fallback=h264.sortedBy {
            when(it.parameters["profile-level-id"]?.take(2)?.lowercase(Locale.ROOT)) { "4d" -> 0;"42" -> 1;else -> 2 }
        }
        val family=experimentFamily(requested)
        val preferred=when(requested) {
            VideoCodecMode.H264_HIGH_HW -> h264.filter { it.parameters["profile-level-id"]?.startsWith("64",true)==true }
            VideoCodecMode.H264_MAIN -> h264.filter { it.parameters["profile-level-id"]?.startsWith("4d",true)==true }
            else -> if(family!=null) capabilities.filter { it.name.equals(family,true) && basicProfile(it.name,it.parameters) } else emptyList()
        }
        val primary=when {
            baselineOnly -> h264.filter { isBaseline(it.parameters) }
            requested==VideoCodecMode.H264 -> h264.sortedBy { rank(it.parameters["profile-level-id"],lightweight) }
            else -> preferred+fallback.filter { it !in preferred }
        }
        if(primary.isEmpty()) throw IOException("VIDEO_CODEC: H.264 Baseline não anunciado pelo dispositivo; selecione H.264 padrão")
        val payloads=primary.map { it.preferredPayloadType.toString() }.toSet()
        val repair=capabilities.filter { it.name.equals("rtx",true) &&
            (it.parameters["apt"]==null || it.parameters["apt"] in payloads) }
        val description=when {
            compatibility -> UiText.text("H.264 Baseline • Compatibilidade • 720p solicitado • orçamento do perfil/partida")
            baselineOnly -> UiText.text("H.264 Baseline • 720p solicitado • orçamento do perfil/partida")
            requested==VideoCodecMode.H264 -> UiText.text("H.264 padrão")
            preferred.isNotEmpty() -> UiText.text("{0} preferido • H.264 disponível como alternativa",UiText.nested(requested.label))
            else -> UiText.text("{0} não anunciado pelo WebRTC • alternativa H.264",UiText.nested(requested.label))
        }
        return Offer(primary+repair,baselineOnly,description)
    }

    fun experimentFamily(mode: VideoCodecMode): String? = when(mode) {
        VideoCodecMode.H265 -> "H265";VideoCodecMode.VP9 -> "VP9";VideoCodecMode.AV1 -> "AV1";else -> null
    }
    fun basicProfile(name: String,p: Map<String,String>): Boolean = when(name.uppercase(Locale.ROOT)) {
        "H265" -> (p["profile-id"] ?: "1")=="1"
        "VP9" -> (p["profile-id"] ?: "0")=="0"
        "AV1" -> (p["profile"] ?: "0")=="0"
        else -> false
    }
    /** Only the first active video section; never interpret an audio/RTX payload as video. */
    fun answerVideo(sdp: String): VideoCodecAnswer? = videoFormats(sdp).firstOrNull()

    fun videoFormats(sdp: String): List<VideoCodecAnswer> {
        val sections=sdp.replace("\r\n","\n").split(Regex("(?m)(?=^m=)"))
        val lines=sections.firstOrNull { it.startsWith("m=video ") && it.lineSequence().first().trim().split(Regex("\\s+")).getOrNull(1)?.substringBefore('/')!="0" &&
            it.lineSequence().none { line -> line.trim()=="a=inactive" } }
            ?.lineSequence()?.map(String::trim)?.filter { it.isNotBlank() }?.toList() ?: return emptyList()
        val formats=lines.filter { it.startsWith("a=rtpmap:") }.associate {
            it.substringAfter(':').substringBefore(' ') to it.substringAfter(' ').substringBefore('/')
        }
        return lines.first().split(Regex("\\s+")).drop(3).mapNotNull { payload ->
            val name=formats[payload] ?: return@mapNotNull null
            if(name.lowercase(Locale.ROOT) in setOf("rtx","red","ulpfec","flexfec-03")) return@mapNotNull null
            val params=parseParameters(lines.firstOrNull { it.startsWith("a=fmtp:$payload ") }?.substringAfter(' ') ?: "")
            VideoCodecAnswer(name,params)
        }
    }
    fun parseParameters(text: String): Map<String,String> = text.split(';').mapNotNull {
        val pair=it.trim().split('=',limit=2)
        if(pair.size==2) pair[0].trim().lowercase(Locale.ROOT) to pair[1].trim() else null
    }.toMap()

    fun label(name: String, parameters: Map<String,String>): String {
        if(name.equals("H264",true)) {
            val profile=parameters["profile-level-id"]?.takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) } ?: return name
            val kind=when(profile.take(2).lowercase(Locale.ROOT)) { "64" -> "High"; "4d" -> "Main"; "42" -> "Baseline"; else -> "perfil" }
            return "H264 $kind ($profile)"
        }
        if(name.equals("H265",true)) return if(parameters["profile-id"]=="1") "H265/HEVC Main" else "H265/HEVC"
        return name
    }
}

data class VideoCodecAnswer(val name: String,val parameters: Map<String,String>) {
    val label: String get()=CodecPreference.label(name,parameters)
}
