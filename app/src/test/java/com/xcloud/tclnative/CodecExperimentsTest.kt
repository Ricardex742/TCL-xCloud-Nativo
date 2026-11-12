package com.xcloud.tclnative

import com.xcloud.tclnative.settings.VideoCodecMode
import com.xcloud.tclnative.webrtc.*
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.RtpCapabilities
import java.io.IOException

class CodecExperimentsTest {
    private fun codec(n: String,p: Int,vararg params: Pair<String,String>)=RtpCapabilities.CodecCapability().apply { name=n;preferredPayloadType=p;parameters=params.toMap() }
    private val baseline=codec("H264",96,"profile-level-id" to "42e01f")
    private val main=codec("H264",98,"profile-level-id" to "4d002a")
    private val high=codec("H264",100,"profile-level-id" to "64002a")
    private fun offerSdp(primary: String,params: String="")="v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 110 96\r\na=rtpmap:110 $primary/90000\r\na=fmtp:110 $params\r\na=rtpmap:96 H264/90000\r\na=fmtp:96 profile-level-id=42e01f\r\n"
    @Test fun highUsesDeclaredFormatsThenMainAndBaselineWithoutChangingCapabilities() {
        val before=listOf(baseline,main,high).map { it.parameters.toMap() }
        val selected=CodecPreference.select(listOf(baseline,main,high),VideoCodecMode.H264_HIGH_HW,false,false)
        assertEquals(listOf(100,98,96),selected.codecs.map { it.preferredPayloadType })
        assertEquals(before,listOf(baseline,main,high).map { it.parameters })
    }
    @Test fun experimentsKeepOnlyTheirOwnProfileAndRepairWithTheRealH264Fallback() {
        for((mode,name,good,bad) in listOf(
            listOf(VideoCodecMode.H265,"H265","1","2"),listOf(VideoCodecMode.VP9,"VP9","0","2"),listOf(VideoCodecMode.AV1,"AV1","0","1"))) {
            val key=if(name=="AV1") "profile" else "profile-id"
            val candidate=codec(name as String,110,key to good as String)
            val invalid=codec(name,112,key to bad as String)
            val capabilities=listOf(baseline,main,candidate,invalid,codec("VP8",114),codec("rtx",111,"apt" to "110"),codec("rtx",113,"apt" to "112"))
            val selected=CodecPreference.select(capabilities,mode as VideoCodecMode,false,false)
            assertEquals(listOf(110,98,96,111),selected.codecs.map { it.preferredPayloadType })
            assertSame(candidate,selected.codecs.first())
            val unavailable=CodecPreference.select(listOf(baseline,main),mode,false,false)
            assertEquals(listOf(98,96),unavailable.codecs.map { it.preferredPayloadType })
            assertTrue(unavailable.description.contains("não anunciado"))
        }
    }
    @Test fun traceAcceptsOnlyTheOptedInFamilyAndMakesTheH264FallbackVisible() {
        for((mode,name,params) in listOf(Triple(VideoCodecMode.H265,"H265","profile-id=1"),Triple(VideoCodecMode.VP9,"VP9","profile-id=0"),Triple(VideoCodecMode.AV1,"AV1","profile=0"))) {
            val trace=CodecNegotiationTrace(mode)
            trace.localOffer(offerSdp(name,params));trace.offerPosted()
            assertEquals(name,trace.remoteAnswer(offerSdp(name,params)).name)
            trace.answerApplied();assertTrue(trace.diagnostics().contains(name))
            assertEquals("H264",trace.remoteAnswer(offerSdp("H264","profile-level-id=4d002a")).name)
            try { trace.remoteAnswer(offerSdp("VP8"));fail("Unadvertised family") } catch(expected: IOException) { assertTrue(expected.message!!.startsWith("VIDEO_CODEC:")) }
            val noLocalAlternative=CodecNegotiationTrace(mode)
            noLocalAlternative.localOffer(offerSdp("H264","profile-level-id=4d002a"))
            try { noLocalAlternative.remoteAnswer(offerSdp(name,params));fail("Not present in the prepared offer") } catch(expected: IOException) { }
        }
    }
    @Test fun baselineCompatibilityNeverLeaksAnAlternativeCodec() {
        val capabilities=listOf(baseline,main,high,codec("H265",110),codec("VP9",112),codec("AV1",114))
        for(mode in VideoCodecMode.entries) assertEquals(listOf(baseline),CodecPreference.select(capabilities,mode,true,true).codecs)
    }
    @Test fun alternateCodecBudgetsPreserveProfileRepairFeedbackAndAudio() {
        for((name,params) in listOf("H265" to "profile-id=1;tier-flag=0;level-id=123", "VP9" to "profile-id=0", "AV1" to "profile=0;level-idx=5;tier=0")) {
            val audio="v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2\r\na=fmtp:111 minptime=10\r\n"
            val raw=audio+offerSdp(name,params).substringAfter("v=0\r\n").replace("110 96\r\n","110 96 112\r\n")+"a=rtpmap:112 rtx/90000\r\na=fmtp:112 apt=110\r\na=rtcp-fb:110 nack pli\r\n"
            val result=StreamProfile.receiveSdp(raw,maxBitrateKbps=25000)
            assertTrue(result.startsWith(audio));assertTrue(result.contains("a=fmtp:110 $params;x-google-min-bitrate=20000"))
            assertTrue(result.contains("x-google-max-bitrate=25000"));assertTrue(result.contains("a=fmtp:112 apt=110\r\n"))
            assertTrue(result.contains("a=rtcp-fb:110 nack pli\r\n"));assertEquals(result,StreamProfile.receiveSdp(result,maxBitrateKbps=25000))
        }
    }

}
