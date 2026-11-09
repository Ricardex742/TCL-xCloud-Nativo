package org.webrtc

import org.junit.Assert.*
import org.junit.Test

class H264VideoDecoderFactoryTest {
    private val baseline=VideoCodecInfo("H264",mapOf("profile-level-id" to "42e01f"),emptyList())
    private val high=VideoCodecInfo("H264",mapOf("profile-level-id" to "640c1f"),emptyList())
    private val main=VideoCodecInfo("H264",mapOf("profile-level-id" to "4d001f"),emptyList())
    private val hevc=VideoCodecInfo("H265",mapOf("profile-id" to "1"),emptyList())
    private val vp9=VideoCodecInfo("VP9",emptyMap(),emptyList())
    private val calls=mutableListOf<VideoCodecInfo>()
    private val base=object: VideoDecoderFactory {
        override fun getSupportedCodecs()=arrayOf(high,baseline,main,hevc,vp9)
        override fun createDecoder(codec: VideoCodecInfo): VideoDecoder? { calls.add(codec);return null }
    }
    @Test fun defaultPreservesExistingH264ProfilesWithoutAdvertisingExperimentalFormats() {
        val factory=H264VideoDecoderFactory(base,false)
        assertEquals(listOf(high,baseline,main),factory.supportedCodecs.toList())
        factory.createDecoder(hevc);factory.createDecoder(vp9)
        assertTrue(calls.isEmpty())
        factory.createDecoder(high);assertEquals(listOf(high),calls)
    }
    @Test fun lightweightAdvertisesAndCreatesOnlyDeclaredBaselineWithoutForgingCapabilities() {
        val factory=H264VideoDecoderFactory(base,true)
        assertEquals(listOf(baseline),factory.supportedCodecs.toList())
        for(codec in listOf(high,main,hevc,vp9,VideoCodecInfo("H264",mapOf("profile-level-id" to "42invalid"),emptyList())))
            factory.createDecoder(codec)
        assertTrue(calls.isEmpty())
        factory.createDecoder(baseline);assertEquals(listOf(baseline),calls)
        assertEquals("42e01f",factory.supportedCodecs.single().params["profile-level-id"])
    }
}
