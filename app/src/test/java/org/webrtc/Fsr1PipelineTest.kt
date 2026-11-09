package org.webrtc

import org.junit.Assert.*
import org.junit.Test

class Fsr1PipelineTest {
    private class Gpu(var fail: String?=null,var precision: Int=23): Fsr1Drawer.Backend {
        val calls=mutableListOf<String>()
        var target=42;var allocated=false;var allocations=0
        var lastPlan: Fsr1Plan?=null;var lastMatrix: FloatArray?=null
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
        override fun rcas(p: Fsr1Plan,x: Int,y: Int,strength: Float,saturation: Float) {
            call("rcas");this.strength=strength;this.saturation=saturation;lastX=x;lastY=y;target=42
        }
        override fun end() { calls+="restore";target=42 }
        override fun release() { calls+="release";allocated=false;target=0 }
    }
    private val identity=floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
    @Test fun oneFrameRunsPreparationEasuRcasAndRestoresTheOriginalTargetWithViewportOrigin() {
        val gpu=Gpu();val renderer=Fsr1Drawer(gpu)
        renderer.configure(1280,720,50,125)
        assertTrue(renderer.drawOes(7,identity,1280,720,9,11,1920,1080))
        assertEquals(listOf("begin","caps","prepare","copy:OES","easu","rcas","restore"),gpu.calls)
        assertSame(identity,gpu.lastMatrix)
        assertEquals(42,gpu.target);assertEquals(.5f,gpu.strength,0f);assertEquals(1.25f,gpu.saturation,0f)
        assertEquals(9,gpu.lastX);assertEquals(11,gpu.lastY)
        assertTrue(renderer.diagnostics().contains("ativo EASU + RCAS"))
        assertTrue(renderer.diagnostics().contains("1280x720 → 1920x1080"))
    }
    @Test fun rgbYuvAndOesUseTheSamePipelineAndUnchangedDimensionsReuseThePlan() {
        val gpu=Gpu();val renderer=Fsr1Drawer(gpu);renderer.configure(640,360,0,100)
        assertTrue(renderer.drawRgb(8,identity,640,360,0,0,1280,720))
        val plan=gpu.lastPlan
        assertTrue(renderer.drawYuv(intArrayOf(1,2,3),identity,640,360,0,0,1280,720))
        assertTrue(renderer.drawOes(9,identity,640,360,0,0,1280,720))
        assertSame(plan,gpu.lastPlan);assertEquals(1,gpu.allocations)
        assertEquals(1,gpu.calls.count { it=="caps" })
        assertEquals(0f,gpu.strength,0f)
        assertTrue(gpu.calls.containsAll(listOf("copy:RGB","copy:YUV","copy:OES")))
    }
    @Test fun failureInAnyGpuStageReleasesResourcesRestoresTheCallerAndDoesNotRetryEveryFrame() {
        for(stage in listOf("caps","prepare","copy:OES","easu","rcas")) {
            val gpu=Gpu(fail=stage);val renderer=Fsr1Drawer(gpu);renderer.configure(1280,720,50,100)
            assertFalse(renderer.drawOes(7,identity,1280,720,0,0,1920,1080))
            assertFalse(gpu.allocated);assertEquals(42,gpu.target)
            assertEquals(listOf("release","restore"),gpu.calls.takeLast(2))
            assertTrue(renderer.diagnostics().contains("retorno à escala padrão"))
            assertFalse(renderer.diagnostics().contains("private driver body"))
            val calls=gpu.calls.toList()
            assertFalse(renderer.drawOes(7,identity,1280,720,0,0,1920,1080))
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
        renderer.configure(1920,1080,50,100)
        assertFalse(renderer.drawRgb(7,identity,1920,1080,0,0,1920,1080))
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
}
