package com.xcloud.tclnative.settings

import android.content.Context
import com.xcloud.tclnative.webrtc.StreamResolution

enum class VideoMode(val label: String, val lowLatency: Boolean) {
    LOW_LATENCY("Menor atraso", true), ADAPTIVE("Adaptativo", false),
    COMPATIBILITY("Compatibilidade — TVs mais fracas", false)
}
enum class TouchMode(val label: String) {
    AUTO("Automático — escolher no celular"), OFF("Desativado"), VIRTUAL("Controle virtual — HUD por jogo"), NATIVE("Toque nativo Xbox — experimental")
}

enum class VideoAspect(val label: String, val ratio: Double? = null, val stretch: Boolean = false) {
    ORIGINAL("Original — manter proporção"),
    RATIO_16_9("16:9 — padrão",16.0/9,true),
    RATIO_18_9("18:9",18.0/9,true),
    RATIO_21_9("21:9",21.0/9,true),
    RATIO_16_10("16:10",16.0/10,true),
    RATIO_4_3("4:3",4.0/3,true),
    STRETCH("Esticar — preencher a tela",stretch=true),
    ZOOM("Zoom — preencher com corte")
}

data class PlaybackSettings(
    val videoMode: VideoMode = VideoMode.LOW_LATENCY,
    val resolution: StreamResolution = StreamResolution.HD_1080_HQ,
    val nativeUpscaling: Boolean = true,
    val aspect: VideoAspect = VideoAspect.RATIO_16_9,
    val localCoOp: Boolean = false,
    val rumble: Boolean = true,
    val touchMode: TouchMode = TouchMode.AUTO,
    val sharpness: Int = 0,
    val saturation: Int = 100
)

class PlaybackSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("stream_preferences",Context.MODE_PRIVATE)
    init {
        if(!prefs.getBoolean("game_hud_migrated",false)) {
            val editor=prefs.edit().putBoolean("game_hud_migrated",true)
            if(prefs.getString("touch_mode",null)==TouchMode.NATIVE.name) editor.putString("touch_mode",TouchMode.VIRTUAL.name)
            editor.apply()
        }
    }
    fun read(): PlaybackSettings {
        val legacy = if (prefs.getBoolean("low_latency_video",true)) VideoMode.LOW_LATENCY else VideoMode.ADAPTIVE
        return PlaybackSettings(
            VideoMode.entries.firstOrNull { it.name == prefs.getString("video_mode",null) } ?: legacy,
            StreamResolution.entries.firstOrNull { it.name == prefs.getString("resolution",null) } ?: StreamResolution.HD_1080_HQ,
            prefs.getBoolean("native_upscaling",true),
            VideoAspect.entries.firstOrNull { it.name == prefs.getString("aspect",null) } ?: VideoAspect.RATIO_16_9,
            prefs.getBoolean("local_coop",false),prefs.getBoolean("rumble",true),
            TouchMode.entries.firstOrNull { it.name==prefs.getString("touch_mode",null) } ?: TouchMode.AUTO,
            prefs.getInt("sharpness",0).coerceIn(0,100),prefs.getInt("saturation",100).coerceIn(0,200))
    }
    fun save(settings: PlaybackSettings) {
        prefs.edit().putString("video_mode",settings.videoMode.name).putBoolean("low_latency_video",settings.videoMode.lowLatency)
            .putString("resolution",settings.resolution.name).putBoolean("native_upscaling",settings.nativeUpscaling)
            .putString("aspect",settings.aspect.name).putBoolean("local_coop",settings.localCoOp)
            .putBoolean("rumble",settings.rumble).putString("touch_mode",settings.touchMode.name)
            .putInt("sharpness",settings.sharpness.coerceIn(0,100)).putInt("saturation",settings.saturation.coerceIn(0,200)).apply()
    }
}

/** Every new game starts hidden. Only an explicit controller/keyboard action changes visibility. */
class StatisticsVisibility {
    @Volatile var mode = 0
        private set
    fun beginSession() { mode = 0 }
    fun cycle(): Int { mode = (mode+1)%3; return mode }
}
