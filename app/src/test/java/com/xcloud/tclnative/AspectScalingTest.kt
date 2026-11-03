package com.xcloud.tclnative

import com.xcloud.tclnative.renderer.AspectTransform
import com.xcloud.tclnative.renderer.VideoScalingPolicy
import com.xcloud.tclnative.renderer.VideoViewport
import com.xcloud.tclnative.settings.VideoAspect
import org.junit.Assert.*
import org.junit.Test

class AspectScalingTest {
    @Test fun wideScreenModesHaveDifferentGeometryAndNativeOffNeverEnlargesEitherDimension() {
        fun viewport(mode: VideoAspect)=VideoScalingPolicy.viewport(2400,1080,1920,1080,true,mode)
        assertEquals(VideoViewport(1920,1080),viewport(VideoAspect.RATIO_16_9))
        assertEquals(VideoViewport(2160,1080),viewport(VideoAspect.RATIO_18_9))
        assertEquals(VideoViewport(2400,1080),viewport(VideoAspect.STRETCH))
        assertEquals(VideoViewport(2400,1080),viewport(VideoAspect.ZOOM))
        assertEquals(VideoViewport(1440,1080),viewport(VideoAspect.RATIO_4_3))
        for(mode in VideoAspect.entries) {
            val native=VideoScalingPolicy.viewport(2400,1080,1280,720,false,mode)
            assertTrue(native.width in 1..1280); assertTrue(native.height in 1..720)
            val small=VideoScalingPolicy.viewport(640,360,1920,1080,true,mode)
            assertTrue(small.width in 1..640); assertTrue(small.height in 1..360)
        }
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
