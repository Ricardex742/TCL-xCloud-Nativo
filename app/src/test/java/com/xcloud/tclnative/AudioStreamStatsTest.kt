package com.xcloud.tclnative

import com.xcloud.tclnative.webrtc.*
import org.junit.Test
import org.junit.Assert.*

class AudioStreamStatsTest {
    private fun sample(at: Double,bytes: Long,delay: Double,emitted: Long,target: Double=delay,lost: Long=0,id: String="audio") =
        RtcSample(id,"inbound-rtp",at,mapOf("kind" to "audio","bytesReceived" to bytes,"jitter" to .005,"codecId" to "codec-audio",
            "jitterBufferDelay" to delay,"jitterBufferTargetDelay" to target,"jitterBufferEmittedCount" to emitted,"packetsLost" to lost))
    private val codec=RtcSample("codec-audio","codec",0.0,mapOf("mimeType" to "audio/opus"))
    @Test fun audioBufferUsesAudioSampleDeltasAndDoesNotReuseTheVideoBuffer() {
        val collector=AudioStreamStatsCollector()
        val video=RtcSample("video","inbound-rtp",1e6,mapOf("kind" to "video","bytesReceived" to 9_000_000L,"jitter" to .9))
        assertNull(collector.collect(listOf(sample(1e6,100,480.0,48000,960.0,3),codec,video)).bufferMs)
        val result=collector.collect(listOf(sample(2e6,200,1440.0,96000,2400.0,5),codec,video))
        assertEquals("opus",result.codec);assertEquals(5.0,result.jitterMs!!,.001)
        assertEquals(20.0,result.bufferMs!!,.001);assertEquals(30.0,result.targetMs!!,.001);assertEquals(2L,result.lostPackets)
        assertTrue(result.diagnostics().contains("última amostra"));assertTrue(result.diagnostics().contains("não mede o atraso dos alto-falantes"))
    }
    @Test fun noProgressResetAndLatePacketCorrectionsDoNotCreateFalseAudioDelay() {
        val collector=AudioStreamStatsCollector()
        collector.collect(listOf(sample(1e6,100,480.0,48000,lost=4)))
        val paused=collector.collect(listOf(sample(2e6,200,480.0,48000,lost=2)))
        assertNull(paused.bufferMs);assertEquals(0L,paused.lostPackets)
        assertNull(collector.collect(listOf(sample(3e6,10,1.0,1,id="new-audio"))).bufferMs)
        assertNull(collector.collect(listOf(sample(4e6,0,0.0,0,id="new-audio"))).bufferMs)
        assertEquals(AudioStreamMetrics(),collector.collect(emptyList()))
        assertNull(collector.collect(listOf(sample(5e6,200,1440.0,96000))).bufferMs)
    }
    @Test fun absentOrInvalidAudioMetricsRemainUnavailableInsteadOfBeingReportedAsZero() {
        val collector=AudioStreamStatsCollector()
        val invalid=sample(1e6,100,Double.NaN,0).copy(values=mapOf("mediaType" to "audio","jitter" to -.5))
        val result=collector.collect(listOf(invalid))
        assertNull(result.codec);assertNull(result.jitterMs);assertNull(result.bufferMs);assertNull(result.lostPackets)
        assertEquals(AudioStreamMetrics(),collector.collect(listOf(invalid.copy(type="outbound-rtp"))))
    }
}
