package com.xcloud.tclnative

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import com.xcloud.tclnative.catalog.GameLibrary
import com.xcloud.tclnative.catalog.LibraryTab
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.ui.dashboard.*
import com.xcloud.tclnative.webrtc.StreamResolution
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[25],qualifiers="w1280dp-h720dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class LowMemoryDeviceTest {
    private fun oneGb(context: Context) {
        val manager=context.getSystemService(ActivityManager::class.java)
        shadowOf(manager).apply {
            setIsLowRamDevice(false) // Generic box firmware need not set the Android low-RAM flag.
            setMemoryClass(256)
            setMemoryInfo(ActivityManager.MemoryInfo().apply { totalMem=900L*1024*1024 })
        }
    }
    @Test fun physicalMemoryCatchesOneGbBoxesWithoutMisclassifyingNewerTwoGbHardware() {
        val app=RuntimeEnvironment.getApplication();oneGb(app)
        assertTrue(DevicePerformancePolicy.read(app).constrainedMemory)
        assertTrue(DevicePerformancePolicy.forDevice(0,128,false).constrainedMemory)
        assertTrue(DevicePerformancePolicy.forDevice(4L shl 30,512,true).constrainedMemory)
        assertFalse(DevicePerformancePolicy.forDevice(1800L*1024*1024,256,false).constrainedMemory)
        assertFalse(DevicePerformancePolicy.forDevice(0,256,false).constrainedMemory)
    }
    @Test fun firstInstallUses720pBaselineWithoutImposingThirtyFpsAndManualChoicesSurviveReopening() {
        val app=RuntimeEnvironment.getApplication();oneGb(app)
        val prefs=app.getSharedPreferences("stream_preferences",0);prefs.edit().clear().commit()
        val store=PlaybackSettingsStore(app)
        val initial=store.read()
        assertEquals(StreamResolution.HD_720,initial.forSession().resolution)
        assertEquals(VideoCodecMode.H264_BASELINE,initial.videoCodec)
        assertEquals(VideoMode.LOW_LATENCY,initial.videoMode)
        assertEquals(FsrMode.OFF,initial.fsr)
        assertTrue(initial.nativeUpscaling)
        assertEquals(initial,PlaybackSettingsStore(app).read())
        val manual=PlaybackSettings(resolution=StreamResolution.HD_1080,videoCodec=VideoCodecMode.H264_MAIN,
            fsr=FsrMode.SGSR1)
        store.save(manual)
        assertEquals(manual,PlaybackSettingsStore(app).read())
        prefs.edit().clear().putBoolean("low_latency_video",false).commit()
        val legacy=PlaybackSettingsStore(app).read()
        assertEquals(VideoMode.ADAPTIVE,legacy.videoMode)
        assertEquals(StreamResolution.HD_1080_HQ,legacy.resolution)
        assertEquals(VideoCodecMode.H264,legacy.videoCodec)
        prefs.edit().clear().commit()
        assertEquals(PlaybackSettings(),PlaybackSettingsStore(app,DevicePerformancePolicy(false)).read())
    }
    @Test fun oldAndroidMenusCanNavigateBeyondTheSmallViewCacheAndResumeArtwork() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        oneGb(activity)
        val played=mutableListOf<String>()
        val games=(0 until 30).map { GameTitle("LOW-$it","Game $it") }
        fun layout(view: GameDashboardView) {
            view.awaitCatalog()
            repeat(3) {
                view.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY))
                view.layout(0,0,1280,720);shadowOf(Looper.getMainLooper()).idle()
            }
        }
        try {
            for(theme in listOf(UiTheme.XBOX_360_NXE,UiTheme.XBOX_SERIES)) {
                DashboardTheme.apply(activity,theme)
                val view=GameDashboardView(activity,GameLibrary(activity,"low-${theme.name}"),{played+=it.titleId},{},{},{})
                try {
                    activity.setContentView(view);view.setGames(games);view.selectTab(LibraryTab.ALL)
                    layout(view);view.restoreFocus();layout(view)
                    for(index in 1..29) {
                        view.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));layout(view)
                        assertEquals("LOW-$index",view.selectedGame?.titleId)
                    }
                    view.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_A))
                    assertEquals("LOW-29",played.last())
                    view.pauseArtwork();view.resumeArtwork();layout(view)
                    assertEquals("LOW-29",view.selectedGame?.titleId)
                } finally { view.close() }
            }
        } finally { DashboardTheme.apply(activity,UiTheme.GREEN);controller.pause().stop().destroy() }
    }
}
