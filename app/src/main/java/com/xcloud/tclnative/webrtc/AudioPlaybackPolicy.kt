package com.xcloud.tclnative.webrtc

import android.os.Build
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.settings.AudioPlaybackMode
import org.webrtc.audio.JavaAudioDeviceModule

/** Optional Android output request. Never changes call mode, system effects, volume or routing. */
object AudioPlaybackPolicy {
    fun requestsLowLatency(mode: AudioPlaybackMode, sdk: Int = Build.VERSION.SDK_INT) =
        mode == AudioPlaybackMode.LOW_LATENCY && sdk >= 26

    fun configure(builder: JavaAudioDeviceModule.Builder, mode: AudioPlaybackMode, sdk: Int = Build.VERSION.SDK_INT) =
        builder.setAudioAttributes(GameAudioSession.attributes()).setUseStereoOutput(true)
            .setEnableVolumeLogger(false).setUseLowLatency(requestsLowLatency(mode, sdk))

    fun diagnostics(mode: AudioPlaybackMode, sdk: Int = Build.VERSION.SDK_INT): String {
        val request = when {
            mode == AudioPlaybackMode.LOW_LATENCY && sdk < 26 -> UiText.text("indisponível nesta API")
            requestsLowLatency(mode, sdk) -> UiText.text("solicitada")
            else -> UiText.text("não solicitada")
        }
        return UiText.text("Perfil de áudio: {0} | Baixa latência Android: {1} | Modo efetivo: não exposto pela biblioteca",
            UiText.nested(mode.label), UiText.nested(request))
    }
}
