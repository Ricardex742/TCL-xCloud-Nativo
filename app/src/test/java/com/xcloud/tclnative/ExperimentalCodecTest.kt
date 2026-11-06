package com.xcloud.tclnative

import com.xcloud.tclnative.settings.VideoCodecMode
import com.xcloud.tclnative.webrtc.*
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.RtpCapabilities

class ExperimentalCodecTest {
    private fun codec(name: String,payload: Int,vararg parameters: Pair<String,String>)=RtpCapabilities.CodecCapability().apply {
        this.name=name;preferredPayloadType=payload;this.parameters=parameters.toMap()
    }
    private val baseline=codec("H264",96,"profile-level-id" to "42e01f","packetization-mode" to "1")
    private val high=codec("H264",98,"profile-level-id" to "640c1f","packetization-mode" to "1")
    private val hevc=codec("H265",100,"profile-id" to "1","tier-flag" to "0","level-id" to "93")
    private val all=listOf(baseline,high,hevc,codec("VP9",102),codec("rtx",97,"apt" to "96"),
        codec("rtx",99,"apt" to "98"),codec("rtx",101,"apt" to "100"),codec("rtx",103,"apt" to "102"))

    @Test fun hevcIsOptInKeepsH264AndOnlyItsAssociatedRepairPayloadsWithoutMutatingCapabilities() {
        val original=all.map { it.parameters.toMap() }
        val standard=CodecPreference.select(all,VideoCodecMode.H264,false,false)
        assertFalse(standard.experimental)
        assertEquals(listOf(98,96,97,99),standard.codecs.map { it.preferredPayloadType })
        val experimental=CodecPreference.select(all,VideoCodecMode.HEVC,false,false)
        assertTrue(experimental.experimental)
        assertEquals(listOf(100,98,96,97,99,101),experimental.codecs.map { it.preferredPayloadType })
        assertSame(hevc,experimental.codecs.first())
        assertEquals(original,all.map { it.parameters })
    }
    @Test fun highCanBeRequestedAt720pButCompatibilityAlwaysPrefersTheLightweightCodec() {
        assertSame(high,CodecPreference.select(all,VideoCodecMode.H264_HIGH,true,false).codecs.first())
        for(mode in VideoCodecMode.entries) {
            val offer=CodecPreference.select(all,mode,true,true)
            assertFalse(offer.experimental);assertSame(baseline,offer.codecs.first())
            assertFalse(offer.codecs.any { it.name=="H265" })
        }
    }
    @Test fun missingOptionalSupportFallsBackWithoutInventingProfilesOrUsingTenBitHevc() {
        val noHigh=CodecPreference.select(listOf(baseline),VideoCodecMode.H264_HIGH,false,false)
        assertFalse(noHigh.experimental);assertEquals(listOf(baseline),noHigh.codecs)
        val noHevc=CodecPreference.select(listOf(baseline,codec("H265",100,"profile-id" to "2"),
            codec("H265",101,"tier-flag" to "1")),VideoCodecMode.HEVC,false,false)
        assertFalse(noHevc.experimental);assertEquals(listOf(baseline),noHevc.codecs)
        assertTrue(CodecPreference.mainHevc(emptyMap())) // RFC defaults: Main, main tier.
    }
    @Test fun h264MustRemainAvailableForExperimentalSessions() {
        try { CodecPreference.select(listOf(hevc),VideoCodecMode.HEVC,false,false);fail("No H.264 fallback") }
        catch(expected: java.io.IOException) { assertTrue(expected.message!!.contains("H.264")) }
    }
    private val sdp="""
        v=0
        m=audio 9 UDP/TLS/RTP/SAVPF 111
        a=rtpmap:111 opus/48000/2
        a=fmtp:111 minptime=10
        m=video 9 UDP/TLS/RTP/SAVPF 101 100 96
        c=IN IP4 0.0.0.0
        a=rtpmap:101 rtx/90000
        a=fmtp:101 apt=100
        a=rtpmap:100 H265/90000
        a=fmtp:100 profile-id=1;tier-flag=0;level-id=93
        a=rtpmap:96 H264/90000
        a=fmtp:96 profile-level-id=42e01f;packetization-mode=1
        a=rtcp-fb:100 nack pli
        a=ice-ufrag:test
    """.trimIndent().replace("\n","\r\n")+"\r\n"
    @Test fun answerDiagnosticsSeparateThePreferredVideoCodecFromAudioAndRepair() {
        val answer=CodecPreference.answerVideo(sdp)!!
        assertEquals("H265",answer.name);assertEquals("93",answer.parameters["level-id"])
        assertEquals("H265/HEVC Main",answer.label)
        assertNull(CodecPreference.answerVideo(sdp.substringBefore("m=video")))
        assertNull(CodecPreference.answerVideo(sdp.replace("m=video 9","m=video 0")))
        assertEquals("H264 High (640c1f)",CodecPreference.label("H264",mapOf("profile-level-id" to "640c1f")))
        assertEquals("H264",CodecPreference.label("H264",mapOf("profile-level-id" to "unknown-sensitive-data")))
    }
    @Test fun receiveBudgetAppliesToHevcAndH264WithoutChangingProfilesAudioRepairOrFeedback() {
        val output=StreamProfile.receiveSdp(sdp)
        assertEquals(sdp.substringBefore("m=video"),output.substringBefore("m=video"))
        assertTrue(output.contains("a=fmtp:100 profile-id=1;tier-flag=0;level-id=93;x-google-min-bitrate=20000"))
        assertTrue(output.contains("a=fmtp:96 profile-level-id=42e01f;packetization-mode=1;x-google-min-bitrate=20000"))
        assertTrue(output.contains("a=fmtp:101 apt=100\r\n"))
        assertTrue(output.contains("a=rtcp-fb:100 nack pli\r\n"))
        assertTrue(output.contains("a=ice-ufrag:test\r\n"))
        assertEquals(output,StreamProfile.receiveSdp(output))
    }
    @Test fun codecRetryIsBoundedByAnH264OverrideAndDoesNotRetryUnrelatedNetworkFailures() {
        val answer=CodecPreference.answerVideo(sdp)
        for(reason in listOf("VIDEO_CODEC: init failed","SDP: rejected","VIDEO: no first frame")) {
            assertTrue(ExperimentalCodecRetry.allowed(VideoCodecMode.HEVC,true,answer,false,reason))
            assertFalse(ExperimentalCodecRetry.allowed(VideoCodecMode.H264,true,answer,false,reason))
            assertFalse(ExperimentalCodecRetry.allowed(VideoCodecMode.HEVC,false,answer,false,reason))
        }
        assertFalse(ExperimentalCodecRetry.allowed(VideoCodecMode.HEVC,true,answer,true,"VIDEO_CODEC: failed"))
        assertFalse(ExperimentalCodecRetry.allowed(VideoCodecMode.HEVC,true,answer,true,"SDP: failed"))
        assertFalse(ExperimentalCodecRetry.allowed(VideoCodecMode.HEVC,true,answer,false,"TRANSPORT: ICE failed"))
        assertFalse(ExperimentalCodecRetry.allowed(VideoCodecMode.HEVC,true,answer,false,"PLAY: unauthorized"))
        assertFalse(ExperimentalCodecRetry.allowed(VideoCodecMode.HEVC,true,VideoCodecAnswer("H264",emptyMap()),false,"VIDEO: no first frame"))
    }
}
