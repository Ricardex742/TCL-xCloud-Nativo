package com.xcloud.tclnative

import com.xcloud.tclnative.diagnostics.*
import com.xcloud.tclnative.webrtc.*
import org.junit.Assert.*
import org.junit.Test

class SessionReportTest {
    @Test fun reportCapturesFinalTouchFallbackAndInputDimensionsAndFreezesThem() {
        val t=tracker(); t.begin(0,"Wi-Fi")
        var details="xCLOUD 0.24 • Toque nativo: ativo; espaço de input: 1280×720"
        t.inputDetails={ details }
        t.add(1000,sample(),InputMetrics(pointerEventsSent=27))
        details="xCLOUD 0.24 • Toque nativo: desativado para HUD; espaço de input: 1280×720"
        val r=finish(t,2000)
        assertEquals(details,r.inputDetails); assertTrue(r.text().contains("desativado para HUD"))
        details="outro modo"
        assertSame(r,t.finish(3000,SessionEnd.USER)); assertTrue(r.inputDetails!!.contains("1280×720"))
    }
    private fun tracker()=SessionReportTracker("Jogo","Dispositivo","720p",60) { 123456L }
    private fun sample(rtt: Double=30.0,seconds: Double=1.0)=StreamMetrics(
        rttMs=rtt,jitterMs=2.0,packetLossPercent=0.0,fps=60.0,bitrateMbps=8.0,decodeMs=4.0,
        intervalSeconds=seconds,decodedFrames=60,width=1280,height=720,codec="H264")
    private fun finish(t: SessionReportTracker,now: Long)=t.finish(now,SessionEnd.USER)!!

    @Test fun reportCoversTheEntireSessionBeyondTheLiveHistoryWindow() {
        val t=tracker(); t.begin(0,"Ethernet")
        repeat(10_000) { i -> t.add((i+1)*1000L,sample(if(i<5000) 10.0 else 100.0),InputMetrics()) }
        val r=finish(t,10_000_000)
        assertEquals(55.0,r.metrics.getValue(ReportMetric.RTT).average,0.0001)
        assertEquals(10.0,r.metrics.getValue(ReportMetric.RTT).minimum,0.0)
        assertEquals(100.0,r.metrics.getValue(ReportMetric.RTT).peak,0.0)
        assertEquals(10000L,r.samples); assertEquals(10_000_000L,r.observedMs)
    }
    @Test fun averagesUseElapsedTimeFramesAndPacketCountsForTheirRespectiveMetrics() {
        val t=tracker(); t.begin(0,"Wi-Fi")
        t.add(1000,sample(10.0).copy(decodeMs=2.0,decodedFrames=10,receivedPackets=90,lostPackets=10,packetLossPercent=10.0),InputMetrics())
        t.add(4000,sample(30.0,3.0).copy(decodeMs=6.0,decodedFrames=30,receivedPackets=900,lostPackets=0),InputMetrics())
        val r=finish(t,4000)
        assertEquals(25.0,r.metrics.getValue(ReportMetric.RTT).average,0.0001)
        assertEquals(5.0,r.metrics.getValue(ReportMetric.DECODE).average,0.0001)
        assertEquals(1.0,r.metrics.getValue(ReportMetric.LOSS).average,0.0001)
        assertEquals(4000L,r.observedMs)
    }
    @Test fun firstFrameStartsDurationAndClampsAnIntervalThatStraddlesStartup() {
        val t=tracker()
        t.add(1000,sample(),InputMetrics())
        t.begin(5000,"Ethernet")
        t.add(5200,sample(seconds=1.0),InputMetrics())
        val r=finish(t,6000)
        assertEquals(1000L,r.durationMs); assertEquals(200L,r.observedMs); assertEquals(1L,r.samples)
        assertNull(r.score)
    }
    @Test fun cumulativeTrafficHandlesResetAndSourceChangeWithoutCountingTheSameBytesTwice() {
        val t=tracker(); t.begin(0,"Ethernet")
        fun add(now: Long,v: Long,transport: Long,source: String="one")=t.add(now,sample().copy(
            totalReceivedVideoBytes=v,videoStreamId=source,transportId="dtls",transportReceivedBytes=transport,transportSentBytes=transport/10),InputMetrics(sentBytes=38))
        add(1000,100,200); add(2000,150,300); add(3000,10,20); add(4000,20,40,"two")
        val r=finish(t,4000)
        assertEquals(180L,r.videoBytes); assertEquals(340L,r.receivedBytes); assertEquals(34L,r.uploadedBytes)
        assertEquals(38L,r.inputBytes)
    }
    @Test fun absentOrInvalidDataCannotProduceHealthyZerosOrACompleteScore() {
        val t=tracker(); t.begin(0,"Rede não informada")
        repeat(8) { t.add((it+1)*1000L,StreamMetrics(rttMs=Double.NaN,jitterMs=-2.0,fps=Double.POSITIVE_INFINITY),InputMetrics()) }
        val r=finish(t,8000)
        assertNull(r.score); assertNull(r.videoBytes); assertNull(r.uploadedBytes); assertNull(r.droppedFrames)
        assertFalse(r.metrics.containsKey(ReportMetric.RTT)); assertFalse(r.metrics.containsKey(ReportMetric.FPS))
        assertTrue(r.text().contains("Enviado WebRTC: n/d")); assertTrue(r.text().contains("média n/d"))
    }
    @Test fun queueCancellationAndRepeatedFinalizationDoNotCreateOrOverwriteAReport() {
        assertNull(tracker().finish(20000,SessionEnd.USER))
        val t=tracker(); assertNull(t.takeReport()); t.begin(1000,"Ethernet")
        t.add(2000,sample(),InputMetrics(sentBytes=76,pointerEventsSent=2))
        val original=t.finish(5000,SessionEnd.FAILURE)!!
        t.add(6000,sample(1000.0),InputMetrics())
        assertSame(original,t.finish(10000,SessionEnd.USER,InputMetrics()))
        assertSame(original,t.takeReport()); assertNull(t.takeReport())
        assertEquals(4000L,original.durationMs); assertEquals(SessionEnd.FAILURE,original.end)
        assertEquals(2L,original.pointerEvents); assertEquals(123456L,original.endedAtMs)
    }
    @Test fun backgroundAndResumeDeltasDoNotPolluteQualityOrStallMeasurements() {
        val t=tracker(); t.begin(0,"Ethernet")
        t.add(1000,sample(10.0).copy(totalReceivedVideoBytes=100),InputMetrics())
        t.observe(1000,1,true,VideoRecoveryAction.NONE)
        t.setVisible(false)
        t.add(2000,sample(999.0).copy(totalReceivedVideoBytes=200),InputMetrics())
        t.observe(2000,1,false,VideoRecoveryAction.RESET_DECODER)
        t.setVisible(true)
        t.add(3000,sample(999.0).copy(totalReceivedVideoBytes=300),InputMetrics())
        t.add(4000,sample(20.0).copy(totalReceivedVideoBytes=400),InputMetrics())
        t.observe(4000,1,true,VideoRecoveryAction.NONE)
        val r=finish(t,4000)
        assertEquals(15.0,r.metrics.getValue(ReportMetric.RTT).average,0.0)
        assertEquals(2000L,r.observedMs); assertEquals(400L,r.videoBytes)
        assertEquals(0L,r.stalledMs); assertTrue(r.recovery.isEmpty())
    }
    @Test fun watchdogSeparatesPausesWithVideoDataWithoutDataAndUnknownAndCountsEpisodes() {
        val t=tracker(); t.begin(0,"Ethernet")
        t.observe(1000,1,true,VideoRecoveryAction.NONE)
        t.observe(2000,1,true,VideoRecoveryAction.REQUEST_KEYFRAME)
        t.observe(3000,1,false,VideoRecoveryAction.NONE)
        t.observe(4000,1,null,VideoRecoveryAction.RESET_DECODER)
        t.observe(5000,2,true,VideoRecoveryAction.NONE)
        t.observe(6000,2,false,VideoRecoveryAction.NONE)
        val r=finish(t,6000)
        assertEquals(4000L,r.stalledMs); assertEquals(2L,r.stalledEvents)
        assertEquals(1000L,r.stalledWithDataMs); assertEquals(2000L,r.stalledWithoutDataMs)
        assertEquals(1,r.recovery[VideoRecoveryAction.REQUEST_KEYFRAME]); assertEquals(1,r.recovery[VideoRecoveryAction.RESET_DECODER])
        assertTrue(r.findings().any { it.contains("sem progresso") })
    }
    @Test fun sufficientRealSamplesEnableTheEstimateAndMissingDecodeKeepsItPartial() {
        val healthy=tracker(); val partial=tracker(); healthy.begin(0,"Ethernet"); partial.begin(0,"Ethernet")
        repeat(6) { i -> healthy.add((i+1)*1000L,sample(),InputMetrics()); partial.add((i+1)*1000L,sample().copy(decodeMs=null),InputMetrics()) }
        assertEquals(100,finish(healthy,6000).score); assertNull(finish(partial,6000).score)
    }
    @Test fun presentationPausesReduceTheEstimateEvenWhenTheDecoderKeepsReportingHealthyFps() {
        val t=tracker(); t.begin(0,"Ethernet")
        repeat(10) { i ->
            val now=(i+1)*1000L
            t.add(now,sample(),InputMetrics())
            t.observe(now,1,true,VideoRecoveryAction.NONE)
        }
        val r=finish(t,10000)
        assertEquals(9000L,r.stalledMs); assertEquals(60,r.score)
        assertTrue(r.findings().any { it.contains("sem progresso da imagem") })
    }
    @Test fun bufferAveragesAreWeightedByEmittedFramesInsteadOfElapsedTime() {
        val t = tracker(); t.begin(0, "Wi-Fi")
        t.add(1000, sample().copy(jitterBufferMs = 80.0, jitterBufferTargetMs = 60.0,
            jitterBufferMinimumMs = 20.0, jitterBufferEmittedFrames = 10), InputMetrics())
        t.add(2000, sample().copy(jitterBufferMs = 40.0, jitterBufferTargetMs = 30.0,
            jitterBufferMinimumMs = 10.0, jitterBufferEmittedFrames = 90), InputMetrics())
        val r = finish(t, 2000)
        assertEquals(44.0, r.metrics.getValue(ReportMetric.BUFFER).average, 0.001)
        assertEquals(33.0, r.metrics.getValue(ReportMetric.BUFFER_TARGET).average, 0.001)
        assertEquals(11.0, r.metrics.getValue(ReportMetric.BUFFER_MINIMUM).average, 0.001)
        assertTrue(r.text().contains("Alvo do buffer WebRTC: média 33.00"))
        assertTrue(r.text().contains("Mínimo de rede do buffer WebRTC: média 11.00"))
    }
    @Test fun decodeFindingsFollowThePresentationTargetAndExplainPipelineOverlap() {
        fun report(fps: Int) = SessionReportTracker("Jogo", "Aparelho", "720p", fps).let { t ->
            t.begin(0, "Wi-Fi"); t.add(1000, sample().copy(decodeMs = 20.0), InputMetrics()); finish(t, 1000)
        }
        assertTrue(report(60).findings().any { it.contains("sozinho não prova sobrecarga") })
        assertFalse(report(30).findings().any { it.contains("Tempo de decodificação acima") })
        assertTrue(report(60).text().contains("Não some essas medidas para estimar input-to-photon"))
        assertTrue(report(60).text().contains("Alvo do buffer WebRTC: média n/d"))
    }
}
