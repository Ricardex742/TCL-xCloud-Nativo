package com.xcloud.tclnative

import android.content.Context
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.webrtc.StreamProfile
import com.xcloud.tclnative.webrtc.StreamResolution
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SessionOptionsTest {
    @Test fun gameOverridesPersistAreAccountScopedAndResetToTheCurrentDefault() {
        val context=RuntimeEnvironment.getApplication()
        val first=SessionOptionsStore(context,"account-one")
        val defaults=SessionOptions(12000,"en-US","BrazilSouth")
        first.save(null,defaults)
        assertEquals(defaults,first.read("game-one"))
        val game=SessionOptions(3000,"es-MX","EastUS")
        first.save("game-one",game)
        first.save(null,defaults.copy(maxBitrateKbps=30000))
        assertEquals(game,SessionOptionsStore(context,"account-one").read("game-one"))
        assertEquals(SessionOptions(),SessionOptionsStore(context,"account-two").read("game-one"))
        first.reset("game-one")
        assertFalse(first.hasOverride("game-one"))
        assertEquals(defaults.copy(maxBitrateKbps=30000),first.read("game-one"))
    }
    @Test fun newPreferencesPersistWithOptionalFeaturesOffByDefaultAndBoundsApplied() {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("stream_preferences",Context.MODE_PRIVATE).edit().clear().commit()
        val store=PlaybackSettingsStore(context)
        assertEquals(PlaybackSettings(),store.read())
        val selected=PlaybackSettings(brightness=25,contrast=125,ipPreference=IpPreference.IPV6,
            desktopInput=DesktopInputMode.NATIVE,mouseSensitivity=150,gyroMode=GyroMode.LT,gyroSensitivity=75,
            microphone=true,audioGain=175,touchRumble=true)
        store.save(selected)
        assertEquals(selected,PlaybackSettingsStore(context).read())
        store.save(selected.copy(brightness=500,contrast=999,audioGain=1000,mouseSensitivity=-1,gyroSensitivity=500))
        assertEquals(selected.copy(brightness=50,contrast=150,audioGain=300,mouseSensitivity=25,gyroSensitivity=300),store.read())
    }
    @Test fun codecBudgetCanBeLowerOrHigherAtAnyResolutionWithoutRewritingTheProfile() {
        val sdp="v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=rtpmap:96 H264/90000\r\na=fmtp:96 profile-level-id=42e01f;packetization-mode=1\r\n"
        for(resolution in StreamResolution.entries) for(cap in listOf(1000,6000,50000)) {
            val output=StreamProfile.receiveSdp(sdp,resolution,false,cap)
            assertTrue(output.contains("b=AS:$cap\r\n"))
            assertTrue(output.contains("x-google-max-bitrate=$cap"))
            assertTrue(output.contains("profile-level-id=42e01f;packetization-mode=1"))
            val min=Regex("x-google-min-bitrate=([0-9]+)").find(output)!!.groupValues[1].toInt()
            assertTrue(min<=cap)
            assertEquals(output,StreamProfile.receiveSdp(output,resolution,false,cap))
        }
        assertTrue(StreamProfile.receiveSdp(sdp,StreamResolution.HD_720,true,0).contains("b=AS:6000"))
    }
}
