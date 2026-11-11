package com.xcloud.tclnative

import android.app.Activity
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.webrtc.StreamResolution
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ImageTreatmentSettingsTest {
    @Test fun newStagesAreOptInAndRoundTripWithoutChangingTheStreamOrTouchPreferences() {
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity=c.get();activity.getSharedPreferences("stream_preferences",0).edit().clear().commit()
            val store=PlaybackSettingsStore(activity)
            assertFalse(store.read().imageTreatment.active)
            assertEquals(PresentationPacing.OFF,store.read().presentationPacing)
            val value=store.read().copy(videoMode=VideoMode.ADAPTIVE,videoCodec=VideoCodecMode.H264_MAIN,
                fsr=FsrMode.SGSR1,presentationPacing=PresentationPacing.SMOOTH,
                imageTreatment=ImageTreatmentSettings(ImageSharpening.USM_LUMA,25,50,75,25,50,25,false))
            store.save(value)
            assertEquals(value,PlaybackSettingsStore(activity).read())
            assertEquals(StreamResolution.HD_1080_HQ,store.read().resolution)
            assertEquals(TouchMode.AUTO,store.read().touchMode)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun corruptedStrengthsAreBoundedAndEffectiveCompatibilityDoesNotOverwriteSavedChoices() {
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val store=PlaybackSettingsStore(c.get())
            val selected=PlaybackSettings(videoMode=VideoMode.COMPATIBILITY,videoCodec=VideoCodecMode.H264_MAIN,
                fsr=FsrMode.SGSR1,presentationPacing=PresentationPacing.SMOOTH,
                imageTreatment=ImageTreatmentSettings(ImageSharpening.RCAS_LUMA,200,-10,200,200,200,200))
            store.save(selected)
            val saved=store.read();assertEquals(100,saved.imageTreatment.deband);assertEquals(0,saved.imageTreatment.cleanup)
            assertEquals(50,saved.imageTreatment.temporal)
            val effective=saved.forSession()
            assertEquals(StreamResolution.HD_720,effective.resolution)
            assertFalse(effective.imageTreatment.active);assertEquals(FsrMode.OFF,effective.fsr)
            assertEquals(PresentationPacing.OFF,effective.presentationPacing)
            assertEquals(saved,store.read())
            val low=saved.copy(videoMode=VideoMode.LOW_LATENCY,videoCodec=VideoCodecMode.H264_BASELINE).forSession()
            assertEquals(PresentationPacing.OFF,low.presentationPacing)
            assertEquals(saved.imageTreatment,low.imageTreatment)
        } finally { c.pause().stop().destroy() }
    }
}
