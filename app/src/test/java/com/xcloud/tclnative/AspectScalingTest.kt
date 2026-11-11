package com.xcloud.tclnative

import com.xcloud.tclnative.renderer.AspectTransform
import com.xcloud.tclnative.renderer.VideoScalingPolicy
import com.xcloud.tclnative.renderer.VideoViewport
import com.xcloud.tclnative.settings.VideoAspect
import org.junit.Assert.*
import org.junit.Test

class AspectScalingTest {
    @Test fun nativeTouchMappingIgnoresBarsAndFollowsTheCropInsteadOfSendingWrongCoordinates() {
        fun point(mode: VideoAspect,x: Float,y: Float)=VideoScalingPolicy.touchCoordinates(2400,1080,1920,1080,mode,x,y)
        assertNull(point(VideoAspect.ORIGINAL,100f,500f))
        assertEquals(.5f,point(VideoAspect.ORIGINAL,1200f,540f)!!.first,0f)
        assertEquals(0f,point(VideoAspect.ORIGINAL,240f,0f)!!.first,0f)
        assertEquals(0f,point(VideoAspect.STRETCH,0f,0f)!!.first,0f)
        assertEquals(.1f,point(VideoAspect.ZOOM,0f,0f)!!.second,.00001f)
        assertEquals(.9f,point(VideoAspect.ZOOM,2400f,1080f)!!.second,.00001f)
        assertEquals(.5f,VideoScalingPolicy.touchCoordinates(1920,1080,1280,720,VideoAspect.ORIGINAL,960f,540f)!!.first,0f)
    }
    @Test fun wideScreenModesFitTheWindowIndependentlyOfSourceResolution() {
        fun viewport(mode: VideoAspect)=VideoScalingPolicy.viewport(2400,1080,1920,1080,mode)
        assertEquals(VideoViewport(1920,1080),viewport(VideoAspect.RATIO_16_9))
        assertEquals(VideoViewport(2160,1080),viewport(VideoAspect.RATIO_18_9))
        assertEquals(VideoViewport(2400,1080),viewport(VideoAspect.STRETCH))
        assertEquals(VideoViewport(2400,1080),viewport(VideoAspect.ZOOM))
        assertEquals(VideoViewport(1440,1080),viewport(VideoAspect.RATIO_4_3))
        for(mode in VideoAspect.entries) {
            assertEquals(viewport(mode),VideoScalingPolicy.viewport(2400,1080,1280,720,mode))
            val small=VideoScalingPolicy.viewport(640,360,1920,1080,mode)
            assertTrue(small.width in 1..640); assertTrue(small.height in 1..360)
        }
    }
    @Test fun reportedLgPhoneUsesTheFullHeightAndTouchMapsToTheExpandedImage() {
        val standard=VideoScalingPolicy.viewport(1452,689,1392,656,VideoAspect.RATIO_16_9)
        assertEquals(VideoViewport(1225,689),standard) // Previously 1166x656 with hardware scaling disabled.
        assertEquals(VideoViewport(1452,684),VideoScalingPolicy.viewport(1452,689,1392,656,VideoAspect.ORIGINAL))
        assertEquals(VideoViewport(1452,689),VideoScalingPolicy.viewport(1452,689,1392,656,VideoAspect.STRETCH))
        val left=(1452-standard.width)/2f
        fun point(x: Float,y: Float)=VideoScalingPolicy.touchCoordinates(1452,689,1392,656,VideoAspect.RATIO_16_9,x,y)
        assertNull(point(left-1f,100f))
        assertEquals(0f,point(left,0f)!!.first,0f)
        assertEquals(.5f,point(726f,344.5f)!!.first,0f)
        assertEquals(1f,point(left+standard.width,689f)!!.second,0f)
    }
    @Test fun stretchUndoesOnlyTheCenteredCropAndKeepsTextureRotationMirroringAndOffset() {
        val base=floatArrayOf(0f,1f,0f,0f, -1f,0f,0f,0f, 0f,0f,1f,0f, .8f,.1f,0f,1f)
        for((source,layout) in listOf(16f/9 to 2f,16f/9 to 4f/3,9f/16 to 16f/9,1f to 1f)) {
            val sx=if(source>layout) layout/source else 1f
            val sy=if(source>layout) 1f else source/layout
            val cropped=FloatArray(16)
            for(row in 0..3) {
                cropped[row]=base[row]*sx
                cropped[4+row]=base[4+row]*sy
                cropped[8+row]=base[8+row]
                cropped[12+row]=base[12+row]+base[row]*(1-sx)/2+base[4+row]*(1-sy)/2
            }
            val result=FloatArray(16)
            AspectTransform.undoCrop(cropped,source,layout,result)
            assertArrayEquals(base,result,.00001f)
        }
    }
}
