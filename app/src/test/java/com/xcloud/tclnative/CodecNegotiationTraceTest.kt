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
    @Test fun baselineRecordsThePreparedFormatPostAndRealLevelAsymmetricAnswer() {
        val trace=trace(VideoCodecMode.H264_BASELINE)
        trace.localOffer(baseline)
        assertTrue(trace.diagnostics().contains("POST confirmado: não confirmado"))
        trace.offerPosted()
        val answer=trace.remoteAnswer(baseline.replace("42e01f","42e02a"))
        assertEquals("H264 Baseline (42e02a)",answer.label)
        assertTrue(trace.diagnostics().contains("aplicada: não"))
        trace.answerApplied()
        assertTrue(trace.diagnostics().contains("POST confirmado: sim"))
        assertTrue(trace.diagnostics().contains("aplicada: sim"))
    }
    @Test fun lightRejectsHighMainHevcUnknownAndMixedOffersBeforePosting() {
        val mixed=baseline.replace(" 96 97\r\n"," 96 97 98\r\n")+
            "a=rtpmap:98 H264/90000\r\na=fmtp:98 profile-level-id=640c1f\r\n"
        for(sdp in listOf(high,high.replace("640c1f","4d001f"),hevc,video("H264","profile-level-id=unknown"),mixed)) {
            val trace=trace(VideoCodecMode.H264_BASELINE)
            rejected { trace.localOffer(sdp) }
            assertTrue(trace.diagnostics().contains("POST confirmado: não confirmado"))
        }
    }
    @Test fun lightRejectsAnIncompatibleOrInactiveAnswerWithoutClaimingItWasApplied() {
        for(sdp in listOf(high,hevc,video("H264",port=0),baseline+"a=inactive\r\n")) {
            val trace=trace(VideoCodecMode.H264_BASELINE)
            trace.localOffer(baseline);trace.offerPosted()
            rejected { trace.remoteAnswer(sdp) }
            assertTrue(trace.diagnostics().contains("aplicada: não"))
        }
    }
    @Test fun standardAcceptsRealHighAndBaselineButRejectsOtherCodecFamilies() {
        val trace=trace(VideoCodecMode.H264)
        trace.localOffer(high);trace.remoteAnswer(baseline);trace.answerApplied()
        assertTrue(trace.diagnostics().contains("H264 High"))
        assertTrue(trace.diagnostics().contains("H264 Baseline"))
        rejected { trace.remoteAnswer(hevc) }
        rejected { trace.localOffer(video("VP8")) }
    }
    @Test fun diagnosticsNeverCopyRawSdpCredentialsOrAddresses() {
        val trace=trace(VideoCodecMode.H264_BASELINE)
        val sdp=baseline+"a=ice-pwd:secret-password\r\na=candidate:1 1 UDP 1 192.168.1.2 5000 typ host\r\n"
        trace.localOffer(sdp);trace.offerPosted();trace.remoteAnswer(sdp)
        val report=trace.diagnostics()
        for(secret in listOf("secret-password","192.168.1.2","a=ice","a=candidate")) assertFalse(report.contains(secret))
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
