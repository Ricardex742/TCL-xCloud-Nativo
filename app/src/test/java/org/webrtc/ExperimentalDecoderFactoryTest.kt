package org.webrtc

import org.junit.Assert.*
import org.junit.Test

class ExperimentalDecoderFactoryTest {
    private fun avc(p: String)=VideoCodecInfo("H264",mapOf("profile-level-id" to p,"packetization-mode" to "1"),emptyList())
    private class Decoder : VideoDecoder {
        override fun initDecode(s: VideoDecoder.Settings,c: VideoDecoder.Callback)=VideoCodecStatus.OK
        override fun decode(i: EncodedImage,d: VideoDecoder.DecodeInfo)=VideoCodecStatus.OK
        override fun release()=VideoCodecStatus.OK
        override fun getImplementationName()="hardware-test"
    }
    private class Base(val capabilities: Array<VideoCodecInfo>) : VideoDecoderFactory {
        val created=mutableListOf<String>()
        override fun getSupportedCodecs()=capabilities
        override fun createDecoder(codec: VideoCodecInfo): VideoDecoder { created+=codec.name;return Decoder() }
    }
    private class Hardware : HardwareVideoDecoderFactory(null as EglBase.Context?) {
        val created=mutableListOf<String>()
        override fun createDecoder(codec: VideoCodecInfo): VideoDecoder { created+=codec.name;return Decoder() }
    }
    @Test fun alternativesCannotFallBackToSoftwareOrAnUnadvertisedProfile() {
        val base=Base(arrayOf(avc("42e01f"),avc("4d002a"),avc("640c1f")))
        val hardware=Hardware();val hevc=VideoCodecInfo("H265",mapOf("profile-id" to "1"),emptyList())
        val f=ExperimentalVideoDecoderFactory(base,hardware,DecoderRecoveryController(),listOf(hevc))
        assertEquals(listOf("H265","H264","H264"),f.supportedCodecs.map { it.name })
        val d=f.createDecoder(hevc)!!
        assertEquals(VideoCodecStatus.OK,d.initDecode(VideoDecoder.Settings(4,1920,1080),VideoDecoder.Callback { _,_,_ -> }))
        assertEquals(listOf("H265"),hardware.created);assertTrue(base.created.isEmpty());d.release()
        assertNull(f.createDecoder(VideoCodecInfo("H265",mapOf("profile-id" to "2"),emptyList())))
        assertNull(f.createDecoder(VideoCodecInfo("AV1",emptyMap(),emptyList())))
        assertNull(f.createDecoder(avc("64002a")))
        assertNotNull(f.createDecoder(avc("4d002a")));assertEquals(listOf("H264"),base.created)
    }
    @Test fun unavailableExperimentDoesNotAdvertiseHighFromALegacyVendorWhitelist() {
        val base=Base(arrayOf(avc("42e01f"),avc("4d002a"),avc("640c1f")))
        val f=ExperimentalVideoDecoderFactory(base,null,DecoderRecoveryController(),emptyList())
        assertEquals(listOf("42e01f","4d002a"),f.supportedCodecs.map { it.params["profile-level-id"] })
        assertEquals("64002a",HardwareCodecSupport.highParameters(42,false)["profile-level-id"])
        assertEquals("640c2a",HardwareCodecSupport.highParameters(42,true)["profile-level-id"])
        assertEquals("4d002a",H264MainSupport.parameters(42)["profile-level-id"])
    }
}
