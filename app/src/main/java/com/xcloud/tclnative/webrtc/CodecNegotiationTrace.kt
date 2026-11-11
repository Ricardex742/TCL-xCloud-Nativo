package com.xcloud.tclnative.webrtc

import com.xcloud.tclnative.settings.VideoCodecMode
import java.io.IOException

/** Records only codec names/validated profile IDs, never SDP, ICE, addresses or server bodies. */
class CodecNegotiationTrace(private val mode: VideoCodecMode) {
    private var baselineOnly=false
    private var intention="aguardando capacidades"
    private var offer: List<VideoCodecAnswer>?=null
    private var answer: List<VideoCodecAnswer>?=null
    private var posted=false
    private var applied=false
    private var issue: String?=null

    @Synchronized fun selected(selection: CodecPreference.Offer) {
        baselineOnly=selection.baselineOnly;intention=selection.description
    }
    private fun summary(formats: List<VideoCodecAnswer>?): String {
        if(formats==null) return "não obtida"
        return formats.map { format ->
            when {
                format.name.equals("H264",true) -> format.label
                format.name.equals("H265",true) -> format.label+(format.parameters["level-id"]
                    ?.takeIf { it.matches(Regex("[0-9]{1,3}")) }?.let { " (level-id=$it)" } ?: "")
                else -> "outro formato de vídeo"
            }
        }.distinct().take(8).joinToString(", ").ifBlank { "sem vídeo ativo" }
    }
    private fun supported(format: VideoCodecAnswer): Boolean = format.name.equals("H264",true) &&
        (!baselineOnly || CodecPreference.isBaseline(format.parameters))

    @Synchronized fun localOffer(sdp: String) {
        val formats=CodecPreference.videoFormats(sdp)
        offer=formats
        if(formats.isEmpty() || formats.any { !supported(it) }) {
            issue="o SDK não gerou oferta H.264 compatível com o perfil escolhido"
            throw IOException("VIDEO_CODEC: $issue")
        }
    }
    @Synchronized fun offerPosted() { posted=true }
    @Synchronized fun preferencesRejected() { issue="o SDK recusou as preferências RTP" }
    @Synchronized fun remoteAnswer(sdp: String): VideoCodecAnswer {
        val formats=CodecPreference.videoFormats(sdp)
        answer=formats
        if(formats.isEmpty()) {
            issue="o serviço respondeu sem vídeo ativo"
            throw IOException("VIDEO_CODEC: $issue")
        }
        if(formats.any { !supported(it) }) {
            issue="o serviço respondeu um formato fora do perfil H.264 escolhido"
            throw IOException("VIDEO_CODEC: $issue")
        }
        return formats.first()
    }
    @Synchronized fun answerApplied() { applied=true }
    @Synchronized fun diagnostics(): String = "Codec solicitado: ${mode.label} | $intention\n"+
        "Oferta SDP preparada: ${summary(offer)} | POST confirmado: ${if(posted) "sim" else "não confirmado"}\n"+
        "Resposta SDP: ${summary(answer)} | aplicada: ${if(applied) "sim" else "não"}"+
        (issue?.let { " | $it" } ?: "")

}
