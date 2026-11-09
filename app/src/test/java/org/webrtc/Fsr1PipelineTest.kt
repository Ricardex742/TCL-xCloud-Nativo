package org.webrtc

import org.junit.Assert.*
import org.junit.Test

class Fsr1PipelineTest {
    private class Gpu(var fail: String?=null,var precision: Int=23): Fsr1Drawer.Backend {
        val calls=mutableListOf<String>()
        var target=42;var allocated=false;var allocations=0
        var lastPlan: Fsr1Plan?=null;var lastMatrix: FloatArray?=null
        var brightness=0;var contrast=100
        override fun colors(brightness: Int,contrast: Int) { this.brightness=brightness;this.contrast=contrast }
        var strength=-1f;var saturation=-1f;var lastX=-1;var lastY=-1
        private fun call(stage: String) { calls+=stage;if(fail==stage) throw IllegalStateException("private driver body") }
        override fun begin() { call("begin") }
        override fun capabilities(): IntArray { call("caps");return intArrayOf(8192,8192,precision) }
        override fun prepare(p: Fsr1Plan) {
            call("prepare");if(!allocated || lastPlan!==p) allocations++
            allocated=true;lastPlan=p;target=99
        }
        override fun copy(kind: Fsr1Drawer.Input,t: Int,textures: IntArray?,m: FloatArray,fw: Int,fh: Int,p: Fsr1Plan) {
            lastMatrix=m;call("copy:${kind.name}")
        }
        override fun easu(p: Fsr1Plan) { call("easu") }
        override fun easuDirect(kind: Fsr1Drawer.Input,t: Int,m: FloatArray,p: Fsr1Plan) {
            lastMatrix=m;call("directEasu:${kind.name}")
        }
        override fun rcasDirect(kind: Fsr1Drawer.Input,t: Int,m: FloatArray,p: Fsr1Plan,x: Int,y: Int,strength: Float,saturation: Float) {
            lastMatrix=m;call("directRcas:${kind.name}")
            this.strength=strength;this.saturation=saturation;lastX=x;lastY=y;target=42
        }
        override fun discardDirect() { calls+="discardDirect" }
        override fun rcas(p: Fsr1Plan,x: Int,y: Int,strength: Float,saturation: Float) {
            call("rcas");this.strength=strength;this.saturation=saturation;lastX=x;lastY=y;target=42
        }
        override fun end() { calls+="restore";target=42 }
        override fun release() { calls+="release";allocated=false;target=0 }
    }
    private val identity=floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
    @Test fun hardwareFrameSkipsPreparationAndRestoresTheOriginalTargetWithViewportOrigin() {
        val gpu=Gpu();val renderer=Fsr1Drawer(gpu)
        renderer.configure(1280,720,50,125)
        assertTrue(renderer.drawOes(7,identity,1280,720,9,11,1920,1080))
        assertEquals(listOf("begin","caps","prepare","directEasu:OES","rcas","restore"),gpu.calls)
        assertSame(identity,gpu.lastMatrix)
        assertEquals(42,gpu.target);assertEquals(.5f,gpu.strength,0f);assertEquals(1.25f,gpu.saturation,0f)
        assertEquals(9,gpu.lastX);assertEquals(11,gpu.lastY)
        assertTrue(renderer.diagnostics().contains("ativo EASU + RCAS"))
        assertTrue(renderer.diagnostics().contains("1280x720 → 1920x1080"))
    }
    @Test fun rgbAndOesReuseDirectPlanWhileYuvReplansForPreparation() {
        val gpu=Gpu();val renderer=Fsr1Drawer(gpu);renderer.configure(640,360,0,100)
        assertTrue(renderer.drawRgb(8,identity,640,360,0,0,1280,720))
        val plan=gpu.lastPlan
        assertTrue(renderer.drawOes(9,identity,640,360,0,0,1280,720))
        assertSame(plan,gpu.lastPlan);assertEquals(1,gpu.allocations)
        assertTrue(renderer.drawYuv(intArrayOf(1,2,3),identity,640,360,0,0,1280,720))
        assertFalse(gpu.lastPlan!!.directInput);assertEquals(2,gpu.allocations)
        assertEquals(1,gpu.calls.count { it=="caps" })
        assertEquals(0f,gpu.strength,0f)
        assertTrue(gpu.calls.containsAll(listOf("directEasu:RGB","copy:YUV","directEasu:OES","easu")))
    }
    @Test fun failureInAnyGpuStageReleasesResourcesRestoresTheCallerAndDoesNotRetryEveryFrame() {
        for(stage in listOf("caps","prepare","copy:YUV","easu","rcas")) {
            val gpu=Gpu(fail=stage);val renderer=Fsr1Drawer(gpu);renderer.configure(1280,720,50,100)
            assertFalse(renderer.drawYuv(intArrayOf(1,2,3),identity,1280,720,0,0,1920,1080))
            assertFalse(gpu.allocated);assertEquals(42,gpu.target)
            assertEquals(listOf("release","restore"),gpu.calls.takeLast(2))
            assertTrue(renderer.diagnostics().contains("retorno à escala padrão"))
            assertNotNull(renderer.failureReason())
            assertFalse(renderer.diagnostics().contains("private driver body"))
            val calls=gpu.calls.toList()
            assertFalse(renderer.drawYuv(intArrayOf(1,2,3),identity,1280,720,0,0,1920,1080))
            assertEquals(calls,gpu.calls)
        }
    }
    @Test fun insufficientPrecisionNeverAllocatesAndDoesNotKeepProbingTheDriver() {
        val gpu=Gpu(precision=10);val renderer=Fsr1Drawer(gpu);renderer.configure(1280,720,50,100)
        repeat(10) { assertFalse(renderer.drawRgb(7,identity,1280,720,0,0,1920,1080)) }
        assertEquals(0,gpu.allocations);assertEquals(1,gpu.calls.count { it=="begin" });assertEquals(42,gpu.target)
        assertTrue(renderer.diagnostics().contains("sem precisão highp"))
    }
    @Test fun aResolutionChangeCanBypassAndLaterResumeWithoutKeepingUnusedWorkingTargets() {
        val gpu=Gpu();val renderer=Fsr1Drawer(gpu);renderer.configure(1280,720,50,100)
        assertTrue(renderer.drawRgb(7,identity,1280,720,0,0,1920,1080))
        renderer.configure(1920,1080,0,100)
        assertFalse(renderer.drawRgb(7,identity,1920,1080,0,0,1920,1080))
        assertNull(renderer.failureReason())
        assertFalse(gpu.allocated);assertEquals(42,gpu.target)
        renderer.configure(1280,720,50,100)
        assertTrue(renderer.drawRgb(7,identity,1280,720,0,0,1920,1080))
        assertTrue(gpu.allocated);assertEquals(2,gpu.allocations)
    }
    @Test fun releaseIsIdempotentAndTheReleasedRendererNeverDrawsAgain() {
        val gpu=Gpu();val renderer=Fsr1Drawer(gpu);renderer.configure(1280,720,50,100)
        assertTrue(renderer.drawRgb(7,identity,1280,720,0,0,1920,1080))
        renderer.release();val calls=gpu.calls.toList();renderer.release()
        assertFalse(renderer.drawRgb(7,identity,1280,720,0,0,1920,1080));assertEquals(calls,gpu.calls)
    }
    @Test fun colorAdjustmentsUseTheExistingRcasPassWithoutAnExtraAllocation() {
        val gpu=Gpu();val renderer=Fsr1Drawer(gpu)
        renderer.configure(1280,720,50,100);renderer.setColorAdjustments(20,125)
        assertTrue(renderer.drawRgb(7,identity,1280,720,0,0,1920,1080))
        assertEquals(20,gpu.brightness);assertEquals(125,gpu.contrast)
        assertEquals(1,gpu.allocations)
        assertEquals(1,gpu.calls.count { it=="rcas" })
    }

    @Test fun exactNativeSizeUsesOnlyDirectRcasWithNoWorkingTexturesAndZeroStrengthBypasses() {
        val gpu=Gpu();val renderer=Fsr1Drawer(gpu);renderer.configure(1920,1080,50,100)
        assertTrue(renderer.drawOes(7,identity,1920,1080,3,5,1920,1080))
        assertEquals(listOf("begin","caps","prepare","directRcas:OES","restore"),gpu.calls)
        assertEquals(0L,gpu.lastPlan!!.workingBytes);assertEquals(1,gpu.lastPlan!!.passes())
        assertTrue(renderer.diagnostics().contains("RCAS nativo (sem EASU)"))
        assertEquals(3,gpu.lastX);assertEquals(5,gpu.lastY)
        renderer.configure(1920,1080,0,100)
        assertFalse(renderer.drawOes(7,identity,1920,1080,0,0,1920,1080))
        assertFalse(gpu.allocated);assertNull(renderer.failureReason());assertTrue(renderer.isBypassed())
        renderer.configure(1920,1080,75,100)
        assertTrue(renderer.drawOes(7,identity,1920,1080,0,0,1920,1080));assertFalse(renderer.isBypassed())
        assertEquals(1,gpu.calls.count { it=="caps" })
    }
    @Test fun yuvAtNativeSizeUsesCopyAndRcasWithoutEasuOrUpscaledTexture() {
        val gpu=Gpu();val renderer=Fsr1Drawer(gpu);renderer.configure(1280,720,50,100)
        assertTrue(renderer.drawYuv(intArrayOf(1,2,3),identity,1280,720,0,0,1280,720))
        assertEquals(listOf("begin","caps","prepare","copy:YUV","rcas","restore"),gpu.calls)
        assertEquals(3_686_400L,gpu.lastPlan!!.workingBytes);assertEquals(2,gpu.lastPlan!!.passes())
    }
    @Test fun directFailureRetriesThePreparedPathOnceAndNeverAttemptsDirectAgain() {
        for(native in listOf(false,true)) {
            val gpu=Gpu(fail=if(native) "directRcas:OES" else "directEasu:OES")
            val renderer=Fsr1Drawer(gpu);renderer.configure(1280,720,50,100)
            val w=if(native) 1280 else 1920;val h=if(native) 720 else 1080
            repeat(3) { assertTrue(renderer.drawOes(7,identity,1280,720,9,11,w,h)) }
            assertEquals(1,gpu.calls.count { it==gpu.fail });assertEquals(1,gpu.calls.count { it=="discardDirect" })
            assertEquals(3,gpu.calls.count { it=="copy:OES" });assertEquals(3,gpu.calls.count { it=="rcas" })
            assertEquals(if(native) 0 else 3,gpu.calls.count { it=="easu" })
            assertFalse(gpu.lastPlan!!.directInput);assertNull(renderer.failureReason())
            assertEquals(42,gpu.target);assertTrue(renderer.diagnostics().contains("compatibilidade nesta sessão"))
            assertFalse(renderer.diagnostics().contains("private driver body"))
        }
    }
    @Test fun failureSharedByDirectAndCompatibilityPathStillDisablesTheRenderer() {
        val gpu=Gpu(fail="rcas");val renderer=Fsr1Drawer(gpu);renderer.configure(1280,720,50,100)
        assertFalse(renderer.drawOes(7,identity,1280,720,0,0,1920,1080))
        assertEquals(1,gpu.calls.count { it=="discardDirect" });assertFalse(gpu.allocated)
        assertEquals(listOf("release","restore"),gpu.calls.takeLast(2));assertNotNull(renderer.failureReason())
    }
    @Test fun nativeRcasIsNotUsedForDownscalingAndWorkingMemoryMatchesActualTargets() {
        val direct=Fsr1Plan.create(1920,1080,3840,2160,8192,8192,23,true,false)
        assertTrue(direct.active());assertEquals(33_177_600L,direct.workingBytes);assertEquals(2,direct.passes())
        val native=Fsr1Plan.create(1920,1080,1920,1080,8192,8192,23,true,true)
        assertTrue(native.active());assertEquals(0L,native.workingBytes)
        val down=Fsr1Plan.create(1920,1080,1280,720,8192,8192,23,true,true)
        assertFalse(down.active());assertEquals(Fsr1Plan.NO_UPSCALING,down.bypass)
    }
    @Test fun pixelCentersFollowOriginalTextureMatrixForRotationMirrorAndCrop() {
        val matrices=listOf(identity,
            floatArrayOf(0f,-1f,0f,0f,1f,0f,0f,0f,0f,0f,1f,0f,0f,1f,0f,1f),
            floatArrayOf(-.8f,0f,0f,0f,0f,.6f,0f,0f,0f,0f,1f,0f,.9f,.2f,0f,1f))
        for(m in matrices) {
            val transform=FloatArray(6);Fsr1Drawer.sourceTransform(m,8,6,transform)
            for((x,y) in listOf(0 to 0,7 to 0,0 to 5,7 to 5,3 to 2)) {
                val u=(x+.5f)/8;val v=(y+.5f)/6
                assertEquals(m[0]*u+m[4]*v+m[12],transform[0]+x*transform[2]+y*transform[4],.000001f)
                assertEquals(m[1]*u+m[5]*v+m[13],transform[1]+x*transform[3]+y*transform[5],.000001f)
            }
        }
    }

}
