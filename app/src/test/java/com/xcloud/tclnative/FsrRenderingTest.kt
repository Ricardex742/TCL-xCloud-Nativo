package com.xcloud.tclnative

import com.xcloud.tclnative.renderer.AspectTransform
import com.xcloud.tclnative.renderer.AspectVideoDrawer
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.*

class FsrRenderingTest {
    private val identity=floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
    private class Ordinary: RendererCommon.GlDrawer {
        var draws=0;var released=false;var matrix: FloatArray?=null
        private fun draw(m: FloatArray) { draws++;matrix=m.copyOf() }
        override fun drawOes(t: Int,m: FloatArray,fw: Int,fh: Int,x: Int,y: Int,w: Int,h: Int)=draw(m)
        override fun drawRgb(t: Int,m: FloatArray,fw: Int,fh: Int,x: Int,y: Int,w: Int,h: Int)=draw(m)
        override fun drawYuv(t: IntArray,m: FloatArray,fw: Int,fh: Int,x: Int,y: Int,w: Int,h: Int)=draw(m)
        override fun release() { released=true }
    }
    private class Upscaler(var succeeds: Boolean=true): FsrUpscaler {
        var width=0;var height=0;var sharpness=0;var saturation=0;var released=false;var calls=0
        var matrix: FloatArray?=null
        override fun configure(w: Int,h: Int,s: Int,c: Int) { width=w;height=h;sharpness=s;saturation=c }
        private fun draw(m: FloatArray): Boolean { calls++;matrix=m.copyOf();return succeeds }
        override fun drawOes(t: Int,m: FloatArray,fw: Int,fh: Int,x: Int,y: Int,w: Int,h: Int)=draw(m)
        override fun drawRgb(t: Int,m: FloatArray,fw: Int,fh: Int,x: Int,y: Int,w: Int,h: Int)=draw(m)
        override fun drawYuv(t: IntArray,m: FloatArray,fw: Int,fh: Int,x: Int,y: Int,w: Int,h: Int)=draw(m)
        override fun diagnostics()="FSR test status"
        override fun release() { released=true }
    }
    @Test fun offUsesOnlyTheOrdinaryDrawerAndNeverCreatesGpuUpscalingResources() {
        val base=Ordinary();var factories=0
        val drawer=AspectVideoDrawer(false,{ factories++;Upscaler() },base)
        repeat(10) { drawer.drawRgb(1,identity,1280,720,0,0,1920,1080) }
        assertEquals(10,base.draws);assertEquals(0,factories);assertTrue(drawer.fsrDiagnostics().contains("desligado"))
        drawer.release();assertTrue(base.released)
    }
    @Test fun activeFsrGetsTheImageControlsWithoutDrawingASecondUnsharpPass() {
        val base=Ordinary();val fsr=Upscaler()
        val drawer=AspectVideoDrawer(false,{fsr},base).apply { fsrEnabled=true;fsrSharpness=25;sharpness=100;saturation=125 }
        drawer.drawRgb(1,identity,1280,720,0,0,1920,1080)
        assertEquals(0,base.draws);assertEquals(25,fsr.sharpness);assertEquals(125,fsr.saturation)
        assertEquals(1280,fsr.width);assertEquals(720,fsr.height)
        assertArrayEquals(identity,fsr.matrix,0f)
        drawer.release();assertTrue(fsr.released);assertTrue(base.released)
    }
    @Test fun failedFsrDrawFallsBackForEveryInputTypeWithoutChangingTheTextureTransform() {
        val base=Ordinary();val fsr=Upscaler(false)
        val drawer=AspectVideoDrawer(false,{fsr},base).apply { fsrEnabled=true }
        drawer.drawOes(1,identity,1280,720,0,0,1920,1080)
        drawer.drawRgb(1,identity,1280,720,0,0,1920,1080)
        drawer.drawYuv(intArrayOf(1,2,3),identity,1280,720,0,0,1920,1080)
        assertEquals(3,base.draws);assertEquals(3,fsr.calls);assertArrayEquals(identity,base.matrix,0f)
    }
    @Test fun stretchingUsesTheUncroppedSourceAndPreservesRotationMirroringAndOffsetsForFsr() {
        val base=Ordinary();val fsr=Upscaler(false)
        val drawer=AspectVideoDrawer(true,{fsr},base).apply {
            fsrEnabled=true;sourceWidth=1920;sourceHeight=1080;sourceAspect=16f/9;layoutAspect=4f/3
        }
        val transformed=floatArrayOf(0f,1f,0f,0f,-1f,0f,0f,0f,0f,0f,1f,0f,.8f,.1f,0f,1f)
        val expected=FloatArray(16);AspectTransform.undoCrop(transformed,16f/9,4f/3,expected)
        drawer.drawOes(1,transformed,1440,1080,0,0,2560,1920)
        assertEquals(1920,fsr.width);assertEquals(1080,fsr.height)
        assertArrayEquals(expected,fsr.matrix,.00001f);assertArrayEquals(expected,base.matrix,.00001f)
    }
    @Test fun planGuardsNoUpscalingGpuLimitsPrecisionAndWorkingStorageBeforeAllocation() {
        fun plan(iw: Int,ih: Int,ow: Int,oh: Int,max: Int=8192,precision: Int=23)=Fsr1Plan.create(iw,ih,ow,oh,max,max,precision)
        val fourK=plan(1920,1080,3840,2160)
        assertTrue(fourK.active());assertEquals(41_472_000L,fourK.workingBytes)
        assertTrue(fourK.workingBytes<=Fsr1Plan.MAX_BYTES)
        assertFalse(plan(1920,1080,1920,1080).active())
        assertFalse(plan(1920,1080,1280,720).active())
        assertFalse(plan(1280,720,1920,1080,1024).active())
        assertFalse(plan(1280,720,1920,1080,precision=10).active())
        assertFalse(plan(1280,720,7680,4320).active())
        assertFalse(plan(0,720,1920,1080).active())
        assertFalse(plan(Int.MAX_VALUE,Int.MAX_VALUE,Int.MAX_VALUE,Int.MAX_VALUE).active())
        val mixed=plan(2080,992,1920,1080)
        assertTrue(mixed.active());assertEquals(1920,mixed.inputWidth);assertEquals(992,mixed.inputHeight)
        assertEquals(0f,Fsr1Plan.rcasStrength(0),0f);assertEquals(.5f,Fsr1Plan.rcasStrength(50),0f)
        assertEquals(1f,Fsr1Plan.rcasStrength(100),0f)
    }
}
