package com.xcloud.tclnative.webrtc

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

    data class Offer(val codecs: List<RtpCapabilities.CodecCapability>, val experimental: Boolean, val description: String)
    fun select(capabilities: List<RtpCapabilities.CodecCapability>, requested: VideoCodecMode,
               lightweight: Boolean, compatibility: Boolean): Offer {
        val mode=if(compatibility) VideoCodecMode.H264 else requested
        val h264=capabilities.filter { it.name.equals("H264",true) }
        if(h264.isEmpty()) throw IOException("VIDEO: o dispositivo não oferece decodificador H.264")
        val hevc=if(mode==VideoCodecMode.HEVC) capabilities.filter {
            it.name.equals("H265",true) && mainHevc(it.parameters)
        } else emptyList()
        val high=mode==VideoCodecMode.H264_HIGH && h264.any { it.parameters["profile-level-id"]?.startsWith("64")==true }
        val primary=hevc+h264.sortedBy { rank(it.parameters["profile-level-id"],if(high) false else lightweight) }
        val payloads=primary.map { it.preferredPayloadType.toString() }.toSet()
        val repair=capabilities.filter { it.name.equals("rtx",true) &&
            (it.parameters["apt"]==null || it.parameters["apt"] in payloads) }
        val description=when {
            compatibility -> "H.264 leve • modo Compatibilidade"
            hevc.isNotEmpty() -> "HEVC Main preferido • H.264 também oferecido"
            high -> "H.264 High preferido • demais perfis H.264 também oferecidos"
            mode!=VideoCodecMode.H264 -> "Experimental indisponível • H.264 padrão"
            else -> "H.264 padrão"
        }
        return Offer(primary+repair,hevc.isNotEmpty() || high,description)
    }

    fun mainHevc(parameters: Map<String,String>): Boolean =
        (parameters["profile-id"] ?: "1")=="1" && (parameters["tier-flag"] ?: "0")=="0"

    /** Only the first active video section; never interpret an audio/RTX payload as video. */
    fun answerVideo(sdp: String): VideoCodecAnswer? {
        val sections=sdp.replace("\r\n","\n").split(Regex("(?m)(?=^m=)"))
        val lines=sections.firstOrNull { it.startsWith("m=video ") && it.lineSequence().first().split(' ').getOrNull(1)!="0" }
            ?.lineSequence()?.filter { it.isNotBlank() }?.toList() ?: return null
        val formats=lines.filter { it.startsWith("a=rtpmap:") }.associate {
            it.substringAfter(':').substringBefore(' ') to it.substringAfter(' ').substringBefore('/')
        }
        for(payload in lines.first().split(' ').drop(3)) {
            val name=formats[payload] ?: continue
            if(name.lowercase(Locale.ROOT) in setOf("rtx","red","ulpfec","flexfec-03")) continue
            val params=parseParameters(lines.firstOrNull { it.startsWith("a=fmtp:$payload ") }?.substringAfter(' ') ?: "")
            return VideoCodecAnswer(name,params)
        }
        return null
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
    fun experimental(mode: VideoCodecMode): Boolean = when(mode) {
        VideoCodecMode.HEVC -> name.equals("H265",true)
        VideoCodecMode.H264_HIGH -> name.equals("H264",true) && parameters["profile-level-id"]?.startsWith("64")==true
        else -> false
    }
}

/** A retry uses a new session/PeerConnection, after cleanup; it never reinterprets encoded packets. */
object ExperimentalCodecRetry {
    fun allowed(mode: VideoCodecMode, offered: Boolean, answer: VideoCodecAnswer?, firstFrame: Boolean, reason: String): Boolean {
        if(mode==VideoCodecMode.H264 || !offered) return false
        if(firstFrame) return false
        if(reason.startsWith("VIDEO_CODEC:")) return true
        return reason.startsWith("SDP") || (reason.startsWith("VIDEO:") && answer?.experimental(mode)==true)
    }
}
