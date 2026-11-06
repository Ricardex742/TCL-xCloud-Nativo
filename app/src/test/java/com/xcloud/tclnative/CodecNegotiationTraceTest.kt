package com.xcloud.tclnative

import com.xcloud.tclnative.settings.VideoCodecMode
import com.xcloud.tclnative.webrtc.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CodecNegotiationTraceTest {
    private fun video(name: String,fmtp: String="",port: Int=9)=
        "v=0\r\nm=video $port UDP/TLS/RTP/SAVPF 96 97\r\na=rtpmap:96 $name/90000\r\n"+
            "a=fmtp:96 $fmtp\r\na=rtpmap:97 rtx/90000\r\na=fmtp:97 apt=96\r\n"
    private val hevc=video("H265","profile-id=1;tier-flag=0;level-id=153")
    private val high=video("H264","profile-level-id=640c1f;packetization-mode=1")
    private val baseline=video("H264","profile-level-id=42e01f;packetization-mode=1")
    private fun trace(mode: VideoCodecMode)=CodecNegotiationTrace(mode).apply {
        selected(CodecPreference.Offer(emptyList(),mode!=VideoCodecMode.H264,"política de teste"))
    }
    private fun rejected(block: ()->Unit): IOException {
        try { block();fail("Expected a codec negotiation rejection") }
        catch(error: IOException) { assertTrue(error.message!!.startsWith("VIDEO_CODEC:"));return error }
        throw AssertionError("Unreachable")
    }
    @Test fun successfulExperimentsRecordThePreparedFormatPostAndAppliedAnswerSeparately() {
        for((mode,sdp) in listOf(VideoCodecMode.HEVC to hevc,VideoCodecMode.H264_HIGH to high)) {
            val trace=trace(mode)
            trace.localOffer(sdp)
            assertTrue(trace.diagnostics().contains("POST confirmado: não confirmado"))
            trace.offerPosted()
            val answer=trace.remoteAnswer(sdp)
            assertTrue(answer.experimental(mode))
            assertTrue(trace.diagnostics().contains("aplicada: não"))
            trace.answerApplied()
            val report=trace.diagnostics()
            assertTrue(report.contains("POST confirmado: sim"))
            assertTrue(report.contains("aplicada: sim"))
            assertTrue(report.contains(if(mode==VideoCodecMode.HEVC) "H265/HEVC Main (level-id=153)" else "H264 High (640c1f)"))
        }
    }
    @Test fun anSdkOfferThatStillIncludesBaselineIsRejectedBeforePostingTheExperiment() {
        val mixed=hevc.replace(" 96 97\r\n"," 96 97 98\r\n")+
            "a=rtpmap:98 H264/90000\r\na=fmtp:98 profile-level-id=42e01f\r\n"
        val trace=trace(VideoCodecMode.HEVC)
        val error=rejected { trace.localOffer(mixed) }
        val summary=trace.fallbackSummary(error)
        assertTrue(summary.contains("H265/HEVC Main (level-id=153), H264 Baseline (42e01f)"))
        assertTrue(summary.contains("POST não confirmado"))
        assertTrue(summary.contains("SDK não gerou oferta exclusiva"))
    }
    @Test fun fallbackRetainsTheRealServerBaselineResponseInsteadOfClaimingTheExperimentWorked() {
        for((mode,sdp) in listOf(VideoCodecMode.HEVC to hevc,VideoCodecMode.H264_HIGH to high)) {
            val trace=trace(mode)
            trace.localOffer(sdp);trace.offerPosted()
            val error=rejected { trace.remoteAnswer(baseline) }
            val summary=trace.fallbackSummary(error)
            assertTrue(summary.contains("POST confirmado"))
            assertTrue(summary.contains("resposta H264 Baseline (42e01f)"))
            assertTrue(summary.contains("serviço respondeu outro formato"))
            assertTrue(summary.endsWith("Nova sessão em H.264 padrão."))
            assertTrue(trace.diagnostics().contains("aplicada: não"))
        }
    }
    @Test fun hevcExperimentsRejectMain10HighTierAndVideoRejectionWithoutPretendingTheyAreMain() {
        for(answer in listOf(hevc.replace("profile-id=1","profile-id=2"),hevc.replace("tier-flag=0","tier-flag=1"),
            video("H265",port=0),hevc+"a=inactive\r\n","v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n")) {
            val trace=trace(VideoCodecMode.HEVC)
            trace.localOffer(hevc);trace.offerPosted()
            rejected { trace.remoteAnswer(answer) }
            assertTrue(trace.diagnostics().contains("aplicada: não"))
        }
    }
    @Test fun theStandardPathAllowsBaselineLevelAsymmetryAndNeverRetriesAsACodecExperiment() {
        val trace=trace(VideoCodecMode.H264)
        trace.localOffer(baseline)
        val answer=trace.remoteAnswer(baseline.replace("42e01f","42e02a"))
        trace.answerApplied()
        assertEquals("H264 Baseline (42e02a)",answer.label)
        assertFalse(ExperimentalCodecRetry.allowed(VideoCodecMode.H264,true,answer,false,"SDP_REMOTE: failure"))
    }
    @Test fun aRememberedDecoderFailureAllowsTheBoundedRetryEvenWhenReadinessLaterTimesOut() {
        val trace=trace(VideoCodecMode.HEVC)
        trace.localOffer(hevc);val answer=trace.remoteAnswer(hevc)
        trace.decoderRejected("VIDEO_CODEC: dimensões/taxa 2080×992 não suportadas: candidate:private")
        val reason=trace.retryReason(IOException("TRANSPORT: timeout"))
        assertTrue(ExperimentalCodecRetry.allowed(VideoCodecMode.HEVC,true,answer,false,reason))
        assertFalse(ExperimentalCodecRetry.allowed(VideoCodecMode.HEVC,true,answer,true,reason))
        assertFalse(ExperimentalCodecRetry.allowed(VideoCodecMode.H264,true,answer,false,reason))
        val summary=trace.fallbackSummary(IOException("TRANSPORT: timeout"))
        assertTrue(summary.contains("2080×992"))
        assertFalse(summary.contains("candidate:private"))
    }
    @Test fun fallbackDiagnosticsDoNotCopyRawSdpTokensAddressesOrServerBodies() {
        val trace=trace(VideoCodecMode.HEVC)
        val sdp=video("H265","profile-id=1;level-id=sensitive-level")+
            "a=ice-pwd:secret-password\r\na=candidate:1 1 UDP 1 192.168.1.2 5000 typ host\r\n"
        trace.localOffer(sdp);trace.offerPosted();trace.remoteAnswer(sdp)
        val report=trace.diagnostics()+trace.fallbackSummary(IOException("SDP_REMOTE: secret-server-body"))
        for(secret in listOf("sensitive-level","secret-password","192.168.1.2","secret-server-body","a=ice","a=candidate"))
            assertFalse(report,report.contains(secret))
        assertTrue(report.contains("SDK recusou a resposta"))
    }
    @Test fun anUnsupportedLocalExperimentRecordsTheStandardPolicyWithoutInventingAnAttempt() {
        val trace=trace(VideoCodecMode.HEVC)
        trace.selected(CodecPreference.Offer(emptyList(),false,"Experimental indisponível • H.264 padrão"))
        trace.localOffer(baseline);trace.offerPosted();trace.remoteAnswer(baseline)
        assertTrue(trace.diagnostics().contains("Experimental indisponível"))
        assertTrue(trace.diagnostics().contains("H264 Baseline (42e01f)"))
    }
    @Test fun parsingSkipsInactiveVideoAndAudioAndPreservesPayloadOrderWithoutRepair() {
        val sdp="v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 96\r\na=rtpmap:96 opus/48000/2\r\n"+
            "m=video 0 UDP/TLS/RTP/SAVPF 96\r\na=rtpmap:96 VP9/90000\r\n"+
            high.substringAfter("v=0\r\n")
        assertEquals(listOf("H264 High (640c1f)"),CodecPreference.videoFormats(sdp).map { it.label })
        assertEquals(listOf("H264 High (640c1f)"),CodecPreference.videoFormats(sdp.replace(
            "m=video 0 UDP/TLS/RTP/SAVPF 96\r\n","m=video 9 UDP/TLS/RTP/SAVPF 96\r\na=inactive\r\n")).map { it.label })
    }
}
