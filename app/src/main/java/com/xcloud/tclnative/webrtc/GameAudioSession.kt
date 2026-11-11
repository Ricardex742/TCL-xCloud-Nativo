package com.xcloud.tclnative.webrtc

import com.xcloud.tclnative.i18n.UiText

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.Locale

/** Playback-only media focus. Never changes global volume, speakerphone, Bluetooth routing or call mode. */
class GameAudioSession(context: Context,private val onGain: (Double) -> Unit) {
    private val manager=context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile var gain=0.0
        private set
    @Volatile private var focus=UiText.text("não solicitado")
    @Volatile var playing=false
    @Volatile var error: String?=null
    private var registered=false
    private var closed=false
    private val listener=AudioManager.OnAudioFocusChangeListener { change -> synchronized(this) {
        if(!closed) when(change) {
            AudioManager.AUDIOFOCUS_GAIN -> update(UiText.text("ativo"),1.0)
            AudioManager.AUDIOFOCUS_LOSS -> update("perdido",0.0)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> update("interrompido",0.0)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> update("reduzido temporariamente",.2)
        }
    } }
    private val request=if(Build.VERSION.SDK_INT>=26) AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes()).setAcceptsDelayedFocusGain(true).setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(listener,Handler(Looper.getMainLooper())).build() else null

    @Suppress("DEPRECATION")
    @Synchronized fun requestFocus() {
        if(closed || (registered && focus in listOf(UiText.text("ativo"),UiText.text("aguardando"),"interrompido","reduzido temporariamente"))) return
        registered=true
        val result=if(Build.VERSION.SDK_INT>=26) manager.requestAudioFocus(request!!)
            else manager.requestAudioFocus(listener,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN)
        when(result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> update(UiText.text("ativo"),1.0)
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> update(UiText.text("aguardando"),0.0)
            else -> update(UiText.text("não concedido"),0.0)
        }
    }
    private fun update(state: String,value: Double) { focus=state; gain=value; onGain(value) }
    fun diagnostics(): String {
        val volume=runCatching { "${manager.getStreamVolume(AudioManager.STREAM_MUSIC)}/${manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}" }.getOrDefault("n/d")
        val muted=runCatching { if(manager.isStreamMute(AudioManager.STREAM_MUSIC)) UiText.text("sim") else UiText.text("não") }.getOrDefault("n/d")
        return UiText.join(UiText.text("Áudio: mídia/jogo • estéreo solicitado • volume mídia {0} • mudo: {1} • foco: {2} • ganho de foco {3}% • reprodução {4}",UiText.nested(volume),UiText.nested(muted),UiText.nested(focus),UiText.nested(String.format(Locale.US,"%.0f",gain*100)),UiText.nested(if(playing) UiText.text("iniciada") else UiText.text("aguardando"))),(error?.let { " • erro: $it" } ?: ""))
    }
    @Suppress("DEPRECATION")
    @Synchronized fun close() {
        if(closed) return
        closed=true
        if(registered) {
            if(Build.VERSION.SDK_INT>=26) manager.abandonAudioFocusRequest(request!!) else manager.abandonAudioFocus(listener)
        }
        registered=false; update("liberado",0.0)
    }
    companion object {
        fun attributes(): AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
    }
}
