package com.xcloud.tclnative.webrtc

import com.xcloud.tclnative.settings.VideoCodecMode
import java.io.IOException

/** Records only codec names/validated profile IDs, never SDP, ICE, addresses or server bodies. */
class CodecNegotiationTrace(private val mode: VideoCodecMode) {
    private var experimental=false
    private var intention="aguardando capacidades"
    private var offer: List<VideoCodecAnswer>?=null
    private var answer: List<VideoCodecAnswer>?=null
    private var posted=false
    private var applied=false
    private var issue: String?=null
    private var decoderFailure=false

    @Synchronized fun selected(selection: CodecPreference.Offer) {
        experimental=selection.experimental;intention=selection.description
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
    private fun matches(format: VideoCodecAnswer): Boolean = format.experimental(mode) &&
        (!format.name.equals("H265",true) || CodecPreference.mainHevc(format.parameters))

    @Synchronized fun localOffer(sdp: String) {
        val formats=CodecPreference.videoFormats(sdp)
        offer=formats
        if(experimental && (formats.isEmpty() || formats.any { !matches(it) })) {
            issue="o SDK não gerou oferta exclusiva compatível"
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
        if(experimental && formats.any { !matches(it) }) {
            issue="o serviço respondeu outro formato após a tentativa exclusiva"
            throw IOException("VIDEO_CODEC: $issue")
        }
        return formats.first()
    }
    @Synchronized fun answerApplied() { applied=true }
    @Synchronized fun decoderRejected(detail: String) {
        decoderFailure=true
        val dimensions=Regex("([0-9]{1,5})×([0-9]{1,5})").find(detail)
        issue=if(dimensions!=null) "decoder recusou dimensões/taxa: ${dimensions.value}"
            else "decoder experimental recusou o fluxo/perfil/inicialização"
    }
    @Synchronized fun retryReason(error: Exception): String = if(decoderFailure) "VIDEO_CODEC: decoder experimental recusou o fluxo" else error.message.orEmpty()

    @Synchronized fun diagnostics(): String = "Codec solicitado: ${mode.label} | $intention\n"+
        "Oferta SDP preparada: ${summary(offer)} | POST confirmado: ${if(posted) "sim" else "não confirmado"}\n"+
        "Resposta SDP: ${summary(answer)} | aplicada: ${if(applied) "sim" else "não"}"+
        (issue?.let { " | $it" } ?: "")

    @Synchronized fun fallbackSummary(error: Exception): String {
        val reason=issue ?: when {
            error.message.orEmpty().startsWith("SDP_LOCAL") -> "o SDK recusou a descrição local"
            error.message.orEmpty().startsWith("SDP_REMOTE") -> "o SDK recusou a resposta do serviço"
            error.message.orEmpty().startsWith("SDP") -> "negociação rejeitada ou sem confirmação"
            error.message.orEmpty().startsWith("VIDEO:") -> "sem primeiro quadro de vídeo"
            error.message.orEmpty().startsWith("VIDEO_CODEC:") -> "formato/decoder experimental recusado"
            else -> "tentativa não concluída"
        }
        return "Teste anterior ${mode.label}: oferta ${summary(offer)}; POST ${if(posted) "confirmado" else "não confirmado"}; resposta ${summary(answer)}; $reason. Nova sessão em H.264 padrão."
    }
}
