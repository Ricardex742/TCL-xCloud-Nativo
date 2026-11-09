package com.xcloud.tclnative.renderer

import android.app.Activity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.xcloud.tclnative.settings.*
import org.junit.Assert.*
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
        }
        return recordingHolder
    }
}
