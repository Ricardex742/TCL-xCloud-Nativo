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
    @Test fun videoResolutionAndScalingSurviveReopeningAndLegacyVideoPreferenceIsKept() {
        val context=RuntimeEnvironment.getApplication()
        val prefs=context.getSharedPreferences("stream_preferences",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertEquals(PlaybackSettings(),PlaybackSettingsStore(context).read())
        prefs.edit().putBoolean("low_latency_video",false).commit()
        assertEquals(VideoMode.ADAPTIVE,PlaybackSettingsStore(context).read().videoMode)
        val chosen=PlaybackSettings(VideoMode.LOW_LATENCY,StreamResolution.HD_720,false,VideoAspect.RATIO_18_9)
        PlaybackSettingsStore(context).save(chosen)
        assertEquals(chosen,PlaybackSettingsStore(context).read())
        prefs.edit().putString("video_mode","removed-mode").putString("resolution","unsupported").commit()
        assertEquals(chosen.copy(resolution=StreamResolution.HD_1080_HQ),PlaybackSettingsStore(context).read())
    }
    @Test fun disablingUpscalingKeeps720pSmallButStillFitsOversizedVideoWithoutCropping() {
        assertEquals(VideoViewport(1920,1080),VideoScalingPolicy.viewport(1920,1080,1280,720,true))
        assertEquals(VideoViewport(1280,720),VideoScalingPolicy.viewport(1920,1080,1280,720,false))
        assertEquals(VideoViewport(1280,720),VideoScalingPolicy.viewport(1280,720,1920,1080,false))
        assertEquals(VideoViewport(1920,1080),VideoScalingPolicy.viewport(3840,2160,1920,1080,false))
        assertEquals(VideoViewport(405,720),VideoScalingPolicy.viewport(1280,720,1080,1920,false))
        assertEquals(VideoViewport(1,1),VideoScalingPolicy.viewport(0,0,0,0,false))
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
}
