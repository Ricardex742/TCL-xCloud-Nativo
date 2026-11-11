package com.xcloud.tclnative

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import com.xcloud.tclnative.webrtc.GameAudioSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class GameAudioSessionTest {
    private fun manager()=RuntimeEnvironment.getApplication().getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Test fun actualWebRtcAudioTrackMapsTheGameAttributesToMediaInsteadOfCallVolume() {
        // Exercise the library's real Android-attributes adapter without starting a JNI peer.
        val method=Class.forName("org.webrtc.audio.WebRtcAudioTrack").getDeclaredMethod("getAudioAttributes",AudioAttributes::class.java).apply { isAccessible=true }
        val previous=method.invoke(null,null) as AudioAttributes
        assertEquals(AudioManager.STREAM_VOICE_CALL,previous.volumeControlStream)
        val media=method.invoke(null,GameAudioSession.attributes()) as AudioAttributes
        assertEquals(AudioManager.STREAM_MUSIC,media.volumeControlStream)
        assertEquals(AudioAttributes.CONTENT_TYPE_MOVIE,media.contentType)
    }
    @Test fun focusUsesMediaAtUnityGainAndRestoresAfterLossWithoutChangingSystemVolumeOrCallMode() {
        val m=manager(); val shadow=shadowOf(m)
        m.setStreamVolume(AudioManager.STREAM_MUSIC,4,0)
        val before=m.getStreamVolume(AudioManager.STREAM_MUSIC); val call=m.getStreamVolume(AudioManager.STREAM_VOICE_CALL); val mode=m.mode
        val gains=mutableListOf<Double>(); val audio=GameAudioSession(RuntimeEnvironment.getApplication(),gains::add)
        audio.requestFocus()
        val request=shadow.lastAudioFocusRequest.audioFocusRequest
        val listener=shadow.lastAudioFocusRequest.listener
        assertEquals(AudioManager.STREAM_MUSIC,request.audioAttributes.volumeControlStream)
        assertEquals(1.0,gains.last(),0.0)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(.2,gains.last(),0.0)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(0.0,gains.last(),0.0)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1.0,gains.last(),0.0)
        assertEquals(before,m.getStreamVolume(AudioManager.STREAM_MUSIC)); assertEquals(call,m.getStreamVolume(AudioManager.STREAM_VOICE_CALL)); assertEquals(mode,m.mode)
        audio.close(); assertSame(request,shadow.lastAbandonedAudioFocusRequest)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(0.0,gains.last(),0.0)
    }
    @Test fun delayedFocusKeepsAudioMutedUntilTheSystemActuallyGrantsIt() {
        val m=manager(); shadowOf(m).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        val audio=GameAudioSession(RuntimeEnvironment.getApplication()) {}
        audio.requestFocus(); assertEquals(0.0,audio.gain,0.0)
        assertTrue(audio.diagnostics().contains("foco: aguardando"))
        shadowOf(m).lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1.0,audio.gain,0.0); audio.close()
    }
    @Test fun deniedOrPermanentlyLostFocusCanBeRequestedAgainOnReturningToTheGame() {
        val m=manager(); val shadow=shadowOf(m)
        shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val audio=GameAudioSession(RuntimeEnvironment.getApplication()) {}
        audio.requestFocus(); assertEquals(0.0,audio.gain,0.0)
        assertTrue(audio.diagnostics().contains("não concedido"))
        shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        audio.requestFocus(); assertEquals(1.0,audio.gain,0.0)
        shadow.lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(0.0,audio.gain,0.0)
        audio.requestFocus(); assertEquals(1.0,audio.gain,0.0); audio.close()
    }
    @Test @Config(sdk=[24]) fun androidSevenUsesTheLegacyMediaFocusPathAndReleasesItsListener() {
        val m=manager(); val shadow=shadowOf(m); val mode=m.mode; val volume=m.getStreamVolume(AudioManager.STREAM_MUSIC)
        val audio=GameAudioSession(RuntimeEnvironment.getApplication()) {}
        audio.requestFocus()
        val request=shadow.lastAudioFocusRequest
        assertEquals(AudioManager.STREAM_MUSIC,request.streamType)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN,request.durationHint)
        assertEquals(1.0,audio.gain,0.0)
        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(0.0,audio.gain,0.0)
        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1.0,audio.gain,0.0)
        assertEquals(mode,m.mode); assertEquals(volume,m.getStreamVolume(AudioManager.STREAM_MUSIC))
        audio.close(); assertSame(request.listener,shadow.lastAbandonedAudioFocusListener)
    }
}
