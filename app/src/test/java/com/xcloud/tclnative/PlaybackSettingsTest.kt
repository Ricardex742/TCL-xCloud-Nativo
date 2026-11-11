package com.xcloud.tclnative

import android.content.Context
import com.xcloud.tclnative.renderer.VideoScalingPolicy
import com.xcloud.tclnative.renderer.VideoViewport
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.webrtc.StreamResolution
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PlaybackSettingsTest {
    @Test fun qhdPersistsWithoutEnablingFsrAndTheLightProfilesStillUse720p() {
        val app=RuntimeEnvironment.getApplication();val store=PlaybackSettingsStore(app)
        val selected=PlaybackSettings(resolution=StreamResolution.QHD_1440,fsr=FsrMode.OFF)
        store.save(selected)
        assertEquals(selected,PlaybackSettingsStore(app).read())
        assertEquals(selected,selected.forSession())
        assertEquals(StreamResolution.HD_720,selected.copy(videoCodec=VideoCodecMode.H264_BASELINE).forSession().resolution)
        assertEquals(StreamResolution.HD_720,selected.copy(videoMode=VideoMode.COMPATIBILITY).forSession().resolution)
        assertEquals(FsrMode.OFF,store.read().fsr)
        assertEquals(FsrOutput.AUTO,store.read().fsrOutput)
        assertEquals(StreamResolution.HD_1080_HQ,PlaybackSettings().resolution)
    }
    @Test fun videoResolutionAndScalingSurviveReopeningAndLegacyVideoPreferenceIsKept() {
        val context=RuntimeEnvironment.getApplication()
        val prefs=context.getSharedPreferences("stream_preferences",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertEquals(PlaybackSettings(),PlaybackSettingsStore(context).read())
        prefs.edit().putBoolean("low_latency_video",false).commit()
        assertEquals(VideoMode.ADAPTIVE,PlaybackSettingsStore(context).read().videoMode)
        val chosen=PlaybackSettings(VideoMode.LOW_LATENCY,StreamResolution.HD_720,false,VideoAspect.RATIO_18_9,
            localCoOp=true,rumble=false,touchMode=TouchMode.VIRTUAL,sharpness=50,saturation=125)
        PlaybackSettingsStore(context).save(chosen)
        assertEquals(chosen,PlaybackSettingsStore(context).read())
        prefs.edit().putString("video_mode","removed-mode").putString("resolution","unsupported").commit()
        assertEquals(chosen.copy(resolution=StreamResolution.HD_1080_HQ),PlaybackSettingsStore(context).read())
    }
    @Test fun layoutFitsSmallAndOversizedSourcesWithoutCroppingOrDependingOnHardwareScaling() {
        assertEquals(VideoViewport(1920,1080),VideoScalingPolicy.viewport(1920,1080,1280,720))
        assertEquals(VideoViewport(1280,720),VideoScalingPolicy.viewport(1280,720,1920,1080))
        assertEquals(VideoViewport(3840,2160),VideoScalingPolicy.viewport(3840,2160,1920,1080))
        assertEquals(VideoViewport(405,720),VideoScalingPolicy.viewport(1280,720,1080,1920))
        assertEquals(VideoViewport(1,1),VideoScalingPolicy.viewport(0,0,0,0))
    }
    @Test fun lightCodecPersistsAndRemovedExperimentalPreferencesMigrateToStandard() {
        val context=RuntimeEnvironment.getApplication()
        val prefs=context.getSharedPreferences("stream_preferences",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store=PlaybackSettingsStore(context)
        assertEquals(VideoCodecMode.H264,store.read().videoCodec)
        for(mode in VideoCodecMode.entries) {
            val selected=PlaybackSettings(videoCodec=mode,resolution=StreamResolution.HD_720,touchMode=TouchMode.NATIVE)
            store.save(selected)
            assertEquals(selected,PlaybackSettingsStore(context).read())
        }
        for(removed in listOf("H264_HIGH","HEVC")) {
            prefs.edit().putString("video_codec",removed).commit()
            assertEquals(VideoCodecMode.H264,PlaybackSettingsStore(context).read().videoCodec)
            assertEquals("H264",prefs.getString("video_codec",null))
        }
        prefs.edit().putString("video_codec","removed-or-invalid").commit()
        assertEquals(VideoCodecMode.H264,store.read().videoCodec)
        assertEquals(StreamResolution.HD_720,store.read().resolution)
    }
    @Test fun lightSessionEnforces720pWithoutChangingFsrOrOtherSavedSettings() {
        val saved=PlaybackSettings(videoCodec=VideoCodecMode.H264_BASELINE,fsr=FsrMode.FSR1,fsrSharpness=75)
        assertEquals(StreamResolution.HD_1080_HQ,saved.resolution)
        assertEquals(saved.copy(resolution=StreamResolution.HD_720),saved.forSession())
        assertEquals(PlaybackSettings(),PlaybackSettings().forSession())
    }
    @Test fun statisticsRequireManualActivationAndEveryNewGameStartsHidden() {
        val visibility=StatisticsVisibility()
        assertEquals(0,visibility.mode)
        assertEquals(1,visibility.cycle())
        assertEquals(2,visibility.cycle())
        visibility.beginSession()
        assertEquals(0,visibility.mode)
        assertEquals(1,visibility.cycle())
        assertEquals(2,visibility.cycle())
        assertEquals(0,visibility.cycle())
    }
    @Test fun fsrDefaultsOffPersistsIndependentlyAndUnknownModesStayOff() {
        val context=RuntimeEnvironment.getApplication()
        val prefs=context.getSharedPreferences("stream_preferences",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store=PlaybackSettingsStore(context)
        assertEquals(FsrMode.OFF,store.read().fsr)
        val selected=PlaybackSettings(fsr=FsrMode.FSR1,fsrSharpness=75,nativeUpscaling=false,
            videoCodec=VideoCodecMode.H264_BASELINE,touchMode=TouchMode.NATIVE)
        store.save(selected);assertEquals(selected,PlaybackSettingsStore(context).read())
        prefs.edit().putString("fsr_mode","future-or-invalid").putInt("fsr_sharpness",400).commit()
        assertEquals(selected.copy(fsr=FsrMode.OFF,fsrSharpness=100),store.read())
        prefs.edit().putInt("fsr_sharpness",-10).commit()
        assertEquals(0,store.read().fsrSharpness)
    }
}
