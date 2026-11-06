package org.webrtc

import android.media.MediaCodecInfo
import android.media.MediaFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.MediaCodecInfoBuilder.CodecCapabilitiesBuilder
import org.robolectric.shadows.ShadowMediaCodecList

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class ExperimentalHardwareCodecTest {
    private fun info(mime: String="video/hevc",profile: Int=MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
                     level: Int=MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51,
                     name: String="c2.vendor.decoder",software: Boolean=false,encoder: Boolean=false): MediaCodecInfo {
        val format=MediaFormat.createVideoFormat(mime,1920,1080)
        val caps=CodecCapabilitiesBuilder.newBuilder().setMediaFormat(format).setIsEncoder(encoder)
            .setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel().apply { this.profile=profile;this.level=level }))
            .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)).build()
        return MediaCodecInfoBuilder.newBuilder().setName(name).setIsEncoder(encoder).setIsVendor(!software)
            .setIsSoftwareOnly(software).setIsHardwareAccelerated(!software).setCapabilities(caps).build()
    }
    @Test fun optionalHevcNeedsHardwareEightBitMainAndTheRequestedSizeAndRate() {
        val hardware=info()
        assertNotNull(ExperimentalDecoderSupport.inspect(hardware,"HEVC",1920,1080,60))
        assertNull(ExperimentalDecoderSupport.inspect(info(software=true),"HEVC",1920,1080,60))
        assertNull(ExperimentalDecoderSupport.inspect(info(encoder=true),"HEVC",1920,1080,60))
        assertNull(ExperimentalDecoderSupport.inspect(info(profile=MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10),"HEVC",1920,1080,60))
        assertNull(ExperimentalDecoderSupport.inspect(hardware,"HEVC",16000,16000,60))
        assertNull(ExperimentalDecoderSupport.inspect(hardware,"HEVC",1920,1080,1000))
        assertNull(ExperimentalDecoderSupport.inspect(hardware,"HEVC",0,1080,60))
        val limited=info(level=MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41)
        val chosen=ExperimentalDecoderSupport.inspect(limited,"HEVC",1920,1080,60)!!
        assertFalse("Runtime dimensions must be checked separately from 1920x1080",chosen.supports(4096,2160))
    }
    @Test fun highUsesTheRealVendorCapabilitiesInsteadOfTheLibraryBrandWhitelist() {
        val hardware=info("video/avc",MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,MediaCodecInfo.CodecProfileLevel.AVCLevel42)
        assertNotNull(ExperimentalDecoderSupport.inspect(hardware,"H264_HIGH",1920,1080,60))
        assertNull(ExperimentalDecoderSupport.inspect(info("video/avc",MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
            MediaCodecInfo.CodecProfileLevel.AVCLevel42),"H264_HIGH",1920,1080,60))
        assertNull(ExperimentalDecoderSupport.inspect(info("video/avc",MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10,
            MediaCodecInfo.CodecProfileLevel.AVCLevel42),"H264_HIGH",1920,1080,60))
    }
    @Test @Config(sdk=[24]) fun oldAndroidRejectsKnownSoftwareDecoderNames() {
        assertNull(ExperimentalDecoderSupport.inspect(info(name="OMX.google.hevc.decoder"),"HEVC",1280,720,60))
        assertNotNull(ExperimentalDecoderSupport.inspect(info(name="OMX.vendor.hevc.decoder"),"HEVC",1280,720,60))
    }
    private val base=object: VideoDecoderFactory {
        override fun createDecoder(codec: VideoCodecInfo): VideoDecoder?=null
        override fun getSupportedCodecs()=arrayOf(VideoCodecInfo("H264",mapOf("profile-level-id" to "42e01f"),emptyList()),
            VideoCodecInfo("H264",mapOf("profile-level-id" to "640c1f"),emptyList()),VideoCodecInfo("H265",emptyMap(),emptyList()))
    }
    private fun factory(mode: String)=ExperimentalVideoDecoderFactory(base,null,false,DecoderRecoveryController(),{},{},mode,1920,1080,60)
    private fun inventory(vararg codecs: MediaCodecInfo) {
        // Android caches the codec inventory. Each scenario represents a fresh device snapshot.
        ShadowMediaCodecList.reset()
        codecs.forEach(ShadowMediaCodecList::addCodec)
    }
    @Test fun factoryDoesNotLeakPlatformSoftwareHevcAndCanAdvertiseVerifiedHighOnCodec2Hardware() {
        inventory(info(software=true))
        assertFalse(factory("HEVC").supportedCodecs.any { it.name=="H265" })
        inventory(info(software=true),info())
        assertTrue(factory("HEVC").supportedCodecs.any { it.name=="H265" })
        assertNull(factory("HEVC").createDecoder(VideoCodecInfo("H265",mapOf("profile-id" to "2"),emptyList())))
        assertFalse(factory("H264").supportedCodecs.any { it.name=="H265" })
        assertFalse(factory("H264_HIGH").supportedCodecs.any { it.params["profile-level-id"]=="640c1f" })
        inventory(info(software=true),info(),info("video/avc",MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
            MediaCodecInfo.CodecProfileLevel.AVCLevel42))
        assertTrue(factory("H264_HIGH").supportedCodecs.any { it.params["profile-level-id"]=="640c1f" })
    }
}
