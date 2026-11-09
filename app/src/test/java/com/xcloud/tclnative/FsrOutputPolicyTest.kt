package com.xcloud.tclnative

import com.xcloud.tclnative.renderer.*
import com.xcloud.tclnative.settings.*
import org.junit.Assert.*
import org.junit.Test

class FsrOutputPolicyTest {
    private fun tv(mode: FsrOutput,w: Int=1920,h: Int=1080,viewport: VideoViewport=VideoViewport(w,h),pw: Int=3840,ph: Int=2160,full: Boolean=true)=
        FsrOutputPolicy.target(mode,w,h,viewport,pw,ph,true,full)
    @Test fun tcl1080UiCanRequest1440Or4kWithoutChangingTheLayoutOrSourceVideo() {
        assertEquals(VideoViewport(1920,1080),tv(FsrOutput.AUTO))
        assertEquals(VideoViewport(1920,1080),tv(FsrOutput.AUTO,w=3840,h=2160))
        assertEquals(VideoViewport(2560,1440),tv(FsrOutput.QHD_1440))
        assertEquals(VideoViewport(3840,2160),tv(FsrOutput.UHD_4K))
        assertTrue(org.webrtc.Fsr1Plan.create(1920,1080,3840,2160,8192,8192,23).active())
        assertTrue(org.webrtc.Fsr1Plan.create(1920,1080,2560,1440,8192,8192,23).active())
    }
    @Test fun aspectAndPhysicalOutputLimitsArePreservedAndNo4kBufferIsRequestedOn1080Tv() {
        assertEquals(VideoViewport(1920,1440),tv(FsrOutput.QHD_1440,viewport=VideoViewport(1440,1080)))
        assertEquals(VideoViewport(2880,2160),tv(FsrOutput.UHD_4K,viewport=VideoViewport(1440,1080)))
        assertEquals(VideoViewport(1920,1080),tv(FsrOutput.UHD_4K,pw=1920,ph=1080))
        assertEquals(VideoViewport(1920,1080),tv(FsrOutput.UHD_4K,pw=0,ph=0))
        assertEquals(VideoViewport(1920,1080),tv(FsrOutput.UHD_4K,full=false))
        assertNull(tv(FsrOutput.UHD_4K,w=0))
    }
    @Test fun automaticOnPhonesUsesTheWindowAndRotatedModesNeverGenerateOversizedBuffers() {
        assertEquals(VideoViewport(1452,684),FsrOutputPolicy.target(FsrOutput.AUTO,1452,684,VideoViewport(1452,684),720,1520,false))
        assertEquals(VideoViewport(1080,1920),FsrOutputPolicy.target(FsrOutput.UHD_4K,1080,1920,VideoViewport(1080,1920),1080,1920,false))
        assertEquals(VideoViewport(2160,3840),tv(FsrOutput.UHD_4K,w=1080,h=1920))
        assertEquals(VideoViewport(3840,2160),tv(FsrOutput.UHD_4K,pw=7680,ph=4320))
    }
}
