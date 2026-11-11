package com.xcloud.tclnative.renderer

import android.app.Activity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.xcloud.tclnative.settings.*
import org.junit.Assert.*
import com.xcloud.tclnative.webrtc.StreamMetrics
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSurfaceView
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],shadows=[RecordingSurfaceShadow::class])
class FsrOutputSurfaceTest {
    @Test fun actualWebRtcLayoutAndFrameCallbacksRetain4kBufferInside1080UiAndRestoreNativeScaling() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val view=OutputSurfaceViewRenderer(controller.get())
        fun requested(): VideoViewport?=Shadow.extract<RecordingSurfaceShadow>(view).lastSize
        view.setOutputBuffer(VideoViewport(3840,2160))
        view.setEnableHardwareScaler(false)
        view.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY))
        view.layout(0,0,1920,1080)
        view.onFrameResolutionChanged(1920,1080,0)
        assertEquals(1920,view.width);assertEquals(1080,view.height)
        assertEquals(VideoViewport(3840,2160),requested())
        view.onFrameResolutionChanged(1280,720,0)
        view.surfaceCreated(view.holder)
        assertEquals(VideoViewport(3840,2160),requested())
        view.setOutputBuffer(null);view.setEnableHardwareScaler(true)
        assertEquals(VideoViewport(1280,720),requested())
        view.setEnableHardwareScaler(false)
        assertNull(requested())
        controller.pause().stop().destroy()
    }
    private class Holder(base: SurfaceHolder): SurfaceHolder by base {
        val sizes=mutableListOf<VideoViewport>();var layouts=0;var rejects=false
        override fun setFixedSize(w: Int,h: Int) { if(rejects) throw IllegalArgumentException("driver body");sizes.add(VideoViewport(w,h)) }
        override fun setSizeFromLayout() { layouts++ }
    }
    @Test fun holderDoesNotReallocateOnEveryFrameAndReturnsToLayoutAfterFailure() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val raw=Holder(SurfaceView(controller.get()).holder);var failed=0
        val holder=OutputSurfaceHolder(raw) { failed++ }
        holder.setOutput(VideoViewport(2560,1440))
        repeat(20) { holder.setSizeFromLayout();holder.setFixedSize(1920,1080) }
        assertEquals(listOf(VideoViewport(2560,1440)),raw.sizes);assertEquals(0,raw.layouts)
        raw.rejects=true
        holder.setOutput(VideoViewport(3840,2160))
        assertEquals(1,failed);assertEquals(1,raw.layouts)
        repeat(20) { holder.setSizeFromLayout() }
        assertEquals(1,raw.layouts)
        holder.setOutput(null)
        assertEquals(1,raw.layouts)
        controller.pause().stop().destroy()
    }
    @Test fun outputPreferencePersistsAndUnknownOrMissingValuesUseAutomaticWithoutEnablingFsr() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val context=controller.get()
        val prefs=context.getSharedPreferences("stream_preferences",0);prefs.edit().clear().commit()
        val store=PlaybackSettingsStore(context)
        assertEquals(FsrOutput.AUTO,store.read().fsrOutput)
        FsrOutput.entries.forEach { output ->
            store.save(PlaybackSettings(fsrOutput=output))
            assertEquals(output,PlaybackSettingsStore(context).read().fsrOutput);assertEquals(FsrMode.OFF,store.read().fsr)
        }
        prefs.edit().putString("fsr_output","future-option").commit()
        assertEquals(FsrOutput.AUTO,PlaybackSettingsStore(context).read().fsrOutput)
        controller.pause().stop().destroy()
    }
    @Test @Config(qualifiers="w3840dp-h2160dp-land-mdpi")
    fun slowFsrProtectionProgressivelyRestoresTheRealHolderWithoutChangingTheUiOrInputSpace() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        Shadow.extract<org.robolectric.shadows.ShadowUIModeManager>(controller.get().getSystemService(android.content.Context.UI_MODE_SERVICE))
            .setCurrentModeType(android.content.res.Configuration.UI_MODE_TYPE_TELEVISION)
        val owner=AiPqSurfaceRenderer(controller.get());owner.layout(0,0,1920,1080)
        controller.get().setContentView(owner)
        owner.setFsr(true,50,FsrOutput.UHD_4K)
        val dimensions=owner.displayDimensions()
        val view=OutputSurfaceViewRenderer(controller.get())
        view.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY))
        view.layout(0,0,1920,1080);view.onFrameResolutionChanged(1920,1080,0)
        // The EGL/Surface boundary is simulated; run the real owner/guard/fallback/stock sizing.
        ReflectionHelpers.setField(owner,"renderer",view)
        ReflectionHelpers.setField(owner,"requestedBuffer",VideoViewport(3840,2160))
        view.setOutputBuffer(VideoViewport(3840,2160))
        val draws=ReflectionHelpers.getField<AtomicLong>(owner,"allDraws")
        val observedSizes=mutableListOf<VideoViewport?>()
        repeat(21) { second ->
            draws.set((second+1L)*13)
            val now=android.os.SystemClock.elapsedRealtime()
            owner.observeFsrPerformance(now,StreamMetrics(receivedVideoBytes=500000,packetLossPercent=0.0,intervalSeconds=1.0,receivedFrames=60),true)
            observedSizes+=Shadow.extract<RecordingSurfaceShadow>(view).lastSize
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        }
        assertEquals(VideoViewport(1920,1080),Shadow.extract<RecordingSurfaceShadow>(view).lastSize)
        assertTrue(observedSizes.contains(VideoViewport(2560,1440)))
        assertTrue(owner.diagnostics().contains("output 1440p (13.0 draws/s)"))
        assertTrue(owner.diagnostics().contains("output native (13.0 draws/s)"))
        assertTrue(owner.diagnostics().contains("scaling off (13.0 draws/s)"))
        assertTrue(owner.diagnostics().contains("buffer solicitado: escala padrão"))
        assertEquals(FsrOutput.UHD_4K,ReflectionHelpers.getField<FsrOutput>(owner,"fsrOutput"))
        assertEquals(dimensions,owner.displayDimensions());assertEquals(.25f to .5f,owner.touchCoordinates(480f,540f))
        owner.release();controller.pause().stop().destroy()
    }
    @Test fun nativeBypassPausesTheGuardAndFilteringResumesWithANewWarmup() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val owner=AiPqSurfaceRenderer(controller.get());owner.layout(0,0,1920,1080);owner.setFsr(true,0,FsrOutput.AUTO)
        var bypass=true
        val noOp=object: org.webrtc.RendererCommon.GlDrawer {
            override fun drawOes(t: Int,m: FloatArray,fw: Int,fh: Int,x: Int,y: Int,w: Int,h: Int) {}
            override fun drawRgb(t: Int,m: FloatArray,fw: Int,fh: Int,x: Int,y: Int,w: Int,h: Int) {}
            override fun drawYuv(t: IntArray,m: FloatArray,fw: Int,fh: Int,x: Int,y: Int,w: Int,h: Int) {}
            override fun release() {}
        }
        val fsr=object: org.webrtc.FsrUpscaler {
            override fun configure(w: Int,h: Int,s: Int,c: Int) {}
            override fun drawOes(t: Int,m: FloatArray,fw: Int,fh: Int,x: Int,y: Int,w: Int,h: Int)=!bypass
            override fun drawRgb(t: Int,m: FloatArray,fw: Int,fh: Int,x: Int,y: Int,w: Int,h: Int)=!bypass
            override fun drawYuv(t: IntArray,m: FloatArray,fw: Int,fh: Int,x: Int,y: Int,w: Int,h: Int)=!bypass
            override fun isBypassed()=bypass
            override fun diagnostics()="simulated GPU boundary"
            override fun release() {}
        }
        val drawer=AspectVideoDrawer(false,{fsr},noOp).apply { fsrEnabled=true }
        drawer.drawRgb(1,FloatArray(16),1920,1080,0,0,1920,1080)
        ReflectionHelpers.setField(owner,"aspectDrawer",drawer)
        val draws=ReflectionHelpers.getField<AtomicLong>(owner,"allDraws").apply { set(120) }
        val metrics=StreamMetrics(receivedVideoBytes=500000,packetLossPercent=0.0,intervalSeconds=1.0)
        repeat(10) {
            owner.observeFsrPerformance(android.os.SystemClock.elapsedRealtime(),metrics,true)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        }
        assertFalse(owner.diagnostics().contains("scaling off"))
        bypass=false
        repeat(7) {
            draws.addAndGet(13)
            owner.observeFsrPerformance(android.os.SystemClock.elapsedRealtime(),metrics,true)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        }
        assertTrue(owner.diagnostics().contains("scaling off (13.0 draws/s)"))
        owner.release();controller.pause().stop().destroy()
    }
    @Test fun changingFsrOutputKeepsTheActualVideoViewTouchSpaceAndClientMetadataInUiPixels() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val view=AiPqSurfaceRenderer(controller.get())
        view.layout(0,0,1920,1080)
        val dimensions=view.displayDimensions()
        for(output in FsrOutput.entries) {
            view.setFsr(true,50,output)
            assertEquals(.25f to .5f,view.touchCoordinates(480f,540f))
            assertEquals(dimensions,view.displayDimensions())
            assertEquals(1920,view.width);assertEquals(1080,view.height)
        }
        controller.pause().stop().destroy()
    }
}

/** Robolectric's stock holder ignores size requests. Record that platform boundary, not WebRTC. */
@Implements(SurfaceView::class)
class RecordingSurfaceShadow: ShadowSurfaceView() {
    var lastSize: VideoViewport?=null
    private lateinit var recordingHolder: SurfaceHolder
    @Implementation
    override fun getHolder(): SurfaceHolder {
        if(!::recordingHolder.isInitialized) recordingHolder=object: SurfaceHolder by super.getHolder() {
            override fun setFixedSize(w: Int,h: Int) { lastSize=VideoViewport(w,h) }
            override fun setSizeFromLayout() { lastSize=null }
            override fun getSurfaceFrame()=android.graphics.Rect(0,0,lastSize?.width ?: 1920,lastSize?.height ?: 1080)
        }
        return recordingHolder
    }
}
