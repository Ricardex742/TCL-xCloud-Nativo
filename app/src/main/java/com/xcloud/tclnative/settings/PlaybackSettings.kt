package com.xcloud.tclnative.settings

import android.content.Context
import com.xcloud.tclnative.webrtc.StreamResolution

enum class VideoMode(val label: String, val lowLatency: Boolean) {
    LOW_LATENCY("Menor atraso", true), ADAPTIVE("Adaptativo", false)
}

data class PlaybackSettings(
    val videoMode: VideoMode = VideoMode.LOW_LATENCY,
    val resolution: StreamResolution = StreamResolution.HD_1080_HQ,
    val nativeUpscaling: Boolean = true
)

class PlaybackSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("stream_preferences",Context.MODE_PRIVATE)
    fun read(): PlaybackSettings {
        val legacy = if (prefs.getBoolean("low_latency_video",true)) VideoMode.LOW_LATENCY else VideoMode.ADAPTIVE
        return PlaybackSettings(
            VideoMode.entries.firstOrNull { it.name == prefs.getString("video_mode",null) } ?: legacy,
            StreamResolution.entries.firstOrNull { it.name == prefs.getString("resolution",null) } ?: StreamResolution.HD_1080_HQ,
            prefs.getBoolean("native_upscaling",true))
    }
    fun save(settings: PlaybackSettings) {
        prefs.edit().putString("video_mode",settings.videoMode.name).putBoolean("low_latency_video",settings.videoMode.lowLatency)
            .putString("resolution",settings.resolution.name).putBoolean("native_upscaling",settings.nativeUpscaling).apply()
    }
}

/** Every new game starts hidden. Only an explicit controller/keyboard action changes visibility. */
class StatisticsVisibility {
    @Volatile var mode = 0
        private set
    fun beginSession() { mode = 0 }
    fun cycle(): Int { mode = (mode+1)%3; return mode }
}
