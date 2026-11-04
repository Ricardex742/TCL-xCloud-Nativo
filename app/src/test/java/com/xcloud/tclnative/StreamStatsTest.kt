package com.xcloud.tclnative

import com.xcloud.tclnative.webrtc.*
import org.junit.Assert.*
import org.junit.Test

class StreamStatsTest {
    @Test fun diagnosticsUseActualTransportCountersAndTheDecodedVideoSource() {
        val collector=StreamStatsCollector()
        fun measured(time: Double,bytes: Long,packets: Long)=samples(video(time,bytes=bytes,packets=packets)).map { s ->
            if(s.type=="transport") s.copy(values=s.values+mapOf("bytesReceived" to bytes+100,"bytesSent" to 900L)) else s
        }
        collector.collect(measured(1_000_000.0,1000,100))
        val r=collector.collect(measured(2_000_000.0,2000,150))
        assertEquals("video",r.videoStreamId); assertEquals(2000L,r.totalReceivedVideoBytes)
        assertEquals(50L,r.receivedPackets); assertEquals(2100L,r.transportReceivedBytes)
        assertEquals(900L,r.transportSentBytes); assertEquals("transport",r.transportId)
        val missing=StreamStatsCollector().collect(samples(video(1_000_000.0)))
        assertNull(missing.transportReceivedBytes); assertNull(missing.transportSentBytes)
    }
    private fun video(time: Double, bytes: Long = 1_000_000, packets: Long = 1000, lost: Long = 10,
                      frames: Long = 600, dropped: Long = 30, decode: Double = 1.0,
                      buffer: Double = 6.0, emitted: Long = 600, id: String = "video") =
        RtcSample(id, "inbound-rtp", time, mapOf("kind" to "video", "codecId" to "h264", "transportId" to "transport",
            "frameWidth" to 1920, "frameHeight" to 1080, "bytesReceived" to java.math.BigInteger.valueOf(bytes),
            "packetsReceived" to packets, "packetsLost" to lost, "framesDecoded" to frames, "framesDropped" to dropped,
            "totalDecodeTime" to decode, "jitterBufferDelay" to buffer, "jitterBufferEmittedCount" to emitted,
            "jitter" to 0.004, "decoderImplementation" to "MediaCodec", "powerEfficientDecoder" to true))
    private fun samples(video: RtcSample, selected: String = "pair1") = listOf(video,
        RtcSample("h264", "codec", video.timestampUs, mapOf("mimeType" to "video/H264")),
        RtcSample("transport", "transport", video.timestampUs, mapOf("selectedCandidatePairId" to selected)),
        RtcSample("pair1", "candidate-pair", video.timestampUs, mapOf("state" to "succeeded", "currentRoundTripTime" to 0.042)),
        RtcSample("pair2", "candidate-pair", video.timestampUs, mapOf("state" to "succeeded", "currentRoundTripTime" to 0.120)))

    @Test fun firstSampleProvidesRealTotalsButDoesNotInventRates() {
        val result = StreamStatsCollector().collect(samples(video(1_000_000.0)))
        assertEquals(1920, result.width); assertEquals(1080, result.height)
        assertEquals(10L, result.totalLostPackets); assertEquals(30L, result.totalDroppedFrames)
        assertNull(result.bitrateMbps); assertNull(result.decodeMs); assertNull(result.packetLossPercent)
        assertEquals(42.0, result.rttMs!!, 0.001)
    }
    @Test fun computesMbpsRttJitterDecodeBufferAndLossWithCorrectUnitsAndIntervals() {
        val collector = StreamStatsCollector()
        collector.collect(samples(video(1_000_000.0)))
        val result = collector.collect(samples(video(2_000_000.0, bytes = 3_500_000, packets = 1990, lost = 20,
            frames = 660, dropped = 33, decode = 1.12, buffer = 6.6, emitted = 660)))
        assertEquals(20.0, result.bitrateMbps!!, 0.001)
        assertEquals(60.0, result.fps!!, 0.001)
        assertEquals(42.0, result.rttMs!!, 0.001)
        assertEquals(4.0, result.jitterMs!!, 0.001)
        assertEquals(10.0, result.jitterBufferMs!!, 0.001)
        assertEquals(2.0, result.decodeMs!!, 0.001)
        assertEquals(1.0, result.packetLossPercent!!, 0.001)
        assertEquals(10L, result.lostPackets); assertEquals(3L, result.droppedFrames)
        assertEquals("H264", result.codec); assertEquals("MediaCodec", result.decoder)
        assertEquals(true, result.powerEfficient)
    }
    @Test fun ratesUseActualIntervalInsteadOfAssumingOneSecondTicks() {
        val collector = StreamStatsCollector()
        collector.collect(samples(video(1_000_000.0)))
        val result = collector.collect(samples(video(3_000_000.0, bytes = 6_000_000, frames = 720, decode = 1.24)))
        assertEquals(20.0, result.bitrateMbps!!, 0.001)
        assertEquals(60.0, result.fps!!, 0.001)
        assertEquals(2.0, result.intervalSeconds!!, 0.001)
    }
    @Test fun selectedIcePairIsReevaluatedAfterConnectionMigration() {
        val collector = StreamStatsCollector()
        collector.collect(samples(video(1_000_000.0)))
        val result = collector.collect(samples(video(2_000_000.0), selected = "pair2"))
        assertEquals(120.0, result.rttMs!!, 0.001)
    }
    @Test fun counterResetAndSsrcChangeDoNotProduceNegativeOrHugeBitrates() {
        val collector = StreamStatsCollector()
        collector.collect(samples(video(1_000_000.0, bytes = 5_000_000)))
        assertNull(collector.collect(samples(video(2_000_000.0, bytes = 100))).bitrateMbps)
        assertEquals(20.0, collector.collect(samples(video(3_000_000.0, bytes = 2_500_100))).bitrateMbps!!, 0.001)
        assertNull(collector.collect(samples(video(4_000_000.0, id = "new-stream"))).bitrateMbps)
    }
    @Test fun signedPacketLossCorrectionsNeverAppearAsNegativeLoss() {
        val collector = StreamStatsCollector()
        collector.collect(samples(video(1_000_000.0)))
        val result = collector.collect(samples(video(2_000_000.0, lost = 8, packets = 2000)))
        assertEquals(0.0, result.packetLossPercent!!, 0.001)
        assertEquals(0L, result.lostPackets)
    }
    @Test fun unknownMetricsRemainUnknownInsteadOfBecomingHealthyZerosOrHardwareClaims() {
        val sample = RtcSample("video", "inbound-rtp", 1_000_000.0, mapOf("kind" to "video"))
        val result = StreamStatsCollector().collect(listOf(sample))
        assertNull(result.rttMs); assertNull(result.jitterMs); assertNull(result.decoder); assertNull(result.powerEfficient)
        val overlay = result.overlay(InputMetrics(), "AiPQ: não verificável")
        assertTrue(overlay.contains("Ping (RTT ICE): n/d"))
        assertTrue(overlay.contains("Decode: n/d"))
        assertTrue(overlay.contains("HW/SW: n/d"))
        assertFalse(overlay.contains("RX 20.0"))
    }
    @Test fun rtxAndAudioCannotBeMistakenForTheDecodedVideoStream() {
        val extra = listOf(RtcSample("rtx-codec", "codec", 0.0, mapOf("mimeType" to "video/rtx")),
            RtcSample("rtx", "inbound-rtp", 1_000_000.0, mapOf("kind" to "video", "codecId" to "rtx-codec", "bytesReceived" to Long.MAX_VALUE)),
            RtcSample("audio", "inbound-rtp", 1_000_000.0, mapOf("kind" to "audio", "bytesReceived" to Long.MAX_VALUE)))
        assertEquals(1920, StreamStatsCollector().collect(samples(video(1_000_000.0)) + extra).width)
    }
    @Test fun repeatedTimestampDoesNotDivideByZeroAndPausedDecodeDoesNotInventAFrameTime() {
        val collector = StreamStatsCollector()
        collector.collect(samples(video(1_000_000.0)))
        assertNull(collector.collect(samples(video(1_000_000.0))).bitrateMbps)
        assertNull(collector.collect(samples(video(2_000_000.0))).decodeMs)
    }
}
