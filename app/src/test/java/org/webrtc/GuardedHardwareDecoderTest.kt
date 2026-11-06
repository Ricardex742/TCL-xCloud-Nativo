package org.webrtc

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class GuardedHardwareDecoderTest {
    private class Driver: VideoDecoder {
        var inits=0;var decoded=0;var releases=0
        var initResult=VideoCodecStatus.OK;var decodeResult=VideoCodecStatus.OK
        override fun initDecode(settings: VideoDecoder.Settings,callback: VideoDecoder.Callback): VideoCodecStatus { inits++;return initResult }
        override fun decode(image: EncodedImage,info: VideoDecoder.DecodeInfo): VideoCodecStatus { decoded++;return decodeResult }
        override fun release(): VideoCodecStatus { releases++;return VideoCodecStatus.OK }
        override fun getImplementationName()="test hardware"
    }
    private val callback=VideoDecoder.Callback { _,_,_ -> }
    private fun image(w: Int,h: Int)=EncodedImage.builder().setBuffer(ByteBuffer.allocateDirect(1),null)
        .setEncodedWidth(w).setEncodedHeight(h).setFrameType(EncodedImage.FrameType.VideoFrameKey).createEncodedImage()
    @Test fun unsupportedRuntimeSizeIsRejectedBeforeTheDriverAllocatesOrConsumesPackets() {
        val driver=Driver();val failed=mutableListOf<String>()
        val guarded=GuardedHardwareDecoder(driver,{w,h -> w<=1920 && h<=1080},failed::add)
        assertEquals(VideoCodecStatus.ERR_SIZE,guarded.initDecode(VideoDecoder.Settings(2,2080,992),callback))
        assertEquals(0,driver.inits)
        val image=image(2080,992)
        try { assertEquals(VideoCodecStatus.ERROR,guarded.decode(image,VideoDecoder.DecodeInfo(false,0))) }
        finally { image.release() }
        assertEquals(0,driver.decoded);assertEquals(1,failed.size)
        assertTrue(failed.single().startsWith("VIDEO_CODEC:"))
        guarded.release();assertEquals(1,driver.releases)
    }
    @Test fun resolutionChangeIsCheckedAndDriverFailuresNotifyOnceWithoutChangingCodecFamily() {
        val driver=Driver();val failed=mutableListOf<String>();var checks=0
        val guarded=GuardedHardwareDecoder(driver,{w,h -> checks++;w<=1920 && h<=1080},failed::add)
        assertEquals(VideoCodecStatus.OK,guarded.initDecode(VideoDecoder.Settings(2,1920,1080),callback))
        val ordinary=image(1920,1080);val oversized=image(2080,992)
        try {
            repeat(5) { assertEquals(VideoCodecStatus.OK,guarded.decode(ordinary,VideoDecoder.DecodeInfo(false,0))) }
            assertEquals(1,checks)
            assertEquals(VideoCodecStatus.ERR_SIZE,guarded.decode(oversized,VideoDecoder.DecodeInfo(false,0)))
            assertEquals(5,driver.decoded);assertEquals(1,failed.size)
        } finally { ordinary.release();oversized.release();guarded.release() }
        val broken=Driver().apply { initResult=VideoCodecStatus.FALLBACK_SOFTWARE }
        val errors=mutableListOf<String>()
        val hardwareOnly=GuardedHardwareDecoder(broken,{_,_ -> true},errors::add)
        assertEquals(VideoCodecStatus.FALLBACK_SOFTWARE,hardwareOnly.initDecode(VideoDecoder.Settings(2,1920,1080),callback))
        assertEquals(1,broken.inits);assertEquals(1,errors.size)
        hardwareOnly.release()
    }
    @Test fun decoderErrorAfterSuccessfulStartupRequestsAnH264SessionRetry() {
        val driver=Driver();val failed=mutableListOf<String>()
        val guarded=GuardedHardwareDecoder(driver,{_,_ -> true},failed::add)
        guarded.initDecode(VideoDecoder.Settings(2,1280,720),callback)
        driver.decodeResult=VideoCodecStatus.ERROR
        val image=image(1280,720)
        try { repeat(3) { assertEquals(VideoCodecStatus.ERROR,guarded.decode(image,VideoDecoder.DecodeInfo(false,0))) } }
        finally { image.release();guarded.release() }
        assertEquals(1,driver.decoded);assertEquals(1,failed.size)
    }
}
