package com.xcloud.tclnative.settings

import com.xcloud.tclnative.i18n.UiText

import android.content.Context
import com.xcloud.tclnative.webrtc.StreamResolution

enum class VideoMode(private val sourceLabel: String, val lowLatency: Boolean) {
    LOW_LATENCY("Menor atraso", true), ADAPTIVE("Adaptativo", false),
    COMPATIBILITY("Compatibilidade — TVs mais fracas", false);
    val label: String get()=UiText.text(sourceLabel)
}
enum class VideoCodecMode(private val sourceLabel: String) {
    H264("H.264 — padrão"), H264_BASELINE("H.264 leve — Baseline (720p)"), H264_MAIN("H.264 Main — experimental"),
    H264_HIGH_HW("H.264 High — experimental"), H265("H.265/HEVC Main — experimental"),
    VP9("VP9 — experimental"), AV1("AV1 — experimental");
    val label: String get()=UiText.text(sourceLabel)
}
enum class FsrMode(private val sourceLabel: String) { OFF("Desativado"), FSR1("AMD FSR 1.0.2 — experimental"), SGSR1("SGSR 1 — experimental"), RECONSTRUCTION("xCloud — reconstrução adaptativa (experimental)") ;
    val label: String get()=UiText.text(sourceLabel)
}
enum class FsrOutput(private val sourceLabel: String) {
    AUTO("Automático"), QHD_1440("1440p — 2560×1440"), UHD_4K("4K — 3840×2160 (experimental)");
    val label: String get()=UiText.text(sourceLabel)
}
enum class TouchMode(private val sourceLabel: String) {
    AUTO("Automático — detectar dispositivo e jogo"), OFF("Desativado — controle físico"), VIRTUAL("Exclusivo — controle virtual"),
    NATIVE("Toque nativo Xbox — experimental");
    val label: String get()=UiText.text(sourceLabel)
}

enum class VideoAspect(private val sourceLabel: String, val ratio: Double? = null, val stretch: Boolean = false) {
    ORIGINAL("Original — manter proporção"),
    RATIO_16_9("16:9 — padrão",16.0/9,true),
    RATIO_18_9("18:9",18.0/9,true),
    RATIO_21_9("21:9",21.0/9,true),
    RATIO_16_10("16:10",16.0/10,true),
    RATIO_4_3("4:3",4.0/3,true),
    STRETCH("Esticar — preencher a tela",stretch=true),
    ZOOM("Zoom — preencher com corte");
    val label: String get()=UiText.text(sourceLabel)
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
    val saturation: Int = 100,
    val sessionReport: Boolean = true,
    val videoCodec: VideoCodecMode = VideoCodecMode.H264,
    val fsr: FsrMode = FsrMode.OFF,
    val fsrSharpness: Int = 50,
    val brightness: Int = 0,val contrast: Int = 100,
    val ipPreference: IpPreference = IpPreference.AUTO,
    val desktopInput: DesktopInputMode = DesktopInputMode.OFF,val mouseSensitivity: Int = 100,
    val gyroMode: GyroMode = GyroMode.OFF,val gyroSensitivity: Int = 100,
    val microphone: Boolean = false,val audioGain: Int = 100,val touchRumble: Boolean = false,
    val gyroNativeTouch: Boolean = false,
    val fsrOutput: FsrOutput = FsrOutput.AUTO,
    val imageTreatment: ImageTreatmentSettings = ImageTreatmentSettings(),
    val presentationPacing: PresentationPacing = PresentationPacing.OFF,
    val reconstruction: ReconstructionSettings = ReconstructionSettings()
) {
    fun forSession(): PlaybackSettings {
        val selected=if(videoCodec==VideoCodecMode.H264_BASELINE || videoMode==VideoMode.COMPATIBILITY)
            copy(resolution=StreamResolution.HD_720) else this
        return when(videoMode) {
            VideoMode.COMPATIBILITY -> selected.copy(imageTreatment=ImageTreatmentSettings(),
                presentationPacing=PresentationPacing.OFF,fsr=FsrMode.OFF)
            VideoMode.LOW_LATENCY -> selected.copy(presentationPacing=PresentationPacing.OFF)
            else -> selected
        }
    }
}

class PlaybackSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("stream_preferences",Context.MODE_PRIVATE)
    init {
        if(prefs.getString("video_codec",null) in setOf("H264_HIGH","HEVC"))
            prefs.edit().putString("video_codec",VideoCodecMode.H264.name).apply()
        if(prefs.getString("touch_mode",null)=="WEB_NATIVE") prefs.edit().putString("touch_mode",TouchMode.NATIVE.name).putBoolean("game_hud_migrated",true).commit()
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
            prefs.getInt("sharpness",0).coerceIn(0,100),prefs.getInt("saturation",100).coerceIn(0,200),prefs.getBoolean("session_report",true),
            VideoCodecMode.entries.firstOrNull { it.name==prefs.getString("video_codec",null) } ?: VideoCodecMode.H264,
            FsrMode.entries.firstOrNull { it.name==prefs.getString("fsr_mode",null) } ?: FsrMode.OFF,
            prefs.getInt("fsr_sharpness",50).coerceIn(0,100),
            prefs.getInt("brightness",0).coerceIn(-50,50),prefs.getInt("contrast",100).coerceIn(50,150),
            IpPreference.entries.firstOrNull { it.name==prefs.getString("ip_preference",null) } ?: IpPreference.AUTO,
            DesktopInputMode.entries.firstOrNull { it.name==prefs.getString("desktop_input",null) } ?: DesktopInputMode.OFF,
            prefs.getInt("mouse_sensitivity",100).coerceIn(25,300),
            GyroMode.entries.firstOrNull { it.name==prefs.getString("gyro_mode",null) } ?: GyroMode.OFF,
            prefs.getInt("gyro_sensitivity",100).coerceIn(25,300),prefs.getBoolean("microphone",false),
            prefs.getInt("audio_gain",100).coerceIn(0,300),prefs.getBoolean("touch_rumble",false),prefs.getBoolean("gyro_native_touch",false),
            FsrOutput.entries.firstOrNull { it.name==prefs.getString("fsr_output",null) } ?: FsrOutput.AUTO,
            ImageTreatmentSettings(
                ImageSharpening.entries.firstOrNull { it.name==prefs.getString("image_sharpening",null) } ?: ImageSharpening.LEGACY,
                prefs.getInt("image_deband",0),prefs.getInt("image_cleanup",0),prefs.getInt("image_chroma",0),
                prefs.getInt("image_antialias",0),prefs.getInt("image_temporal",0),prefs.getInt("image_neural",0),
                prefs.getBoolean("image_auto_protect",true)).bounded(),
            PresentationPacing.entries.firstOrNull { it.name==prefs.getString("presentation_pacing",null) } ?: PresentationPacing.OFF,
            ReconstructionSettings(
                ReconstructionQuality.entries.firstOrNull { it.name==prefs.getString("reconstruction_quality",null) } ?: ReconstructionQuality.AUTO,
                prefs.getInt("reconstruction_strength",50),
                ImageComparison.entries.firstOrNull { it.name==prefs.getString("image_comparison",null) } ?: ImageComparison.PROCESSED).bounded())
    }
    fun save(settings: PlaybackSettings) {
        prefs.edit().putString("video_mode",settings.videoMode.name).putBoolean("low_latency_video",settings.videoMode.lowLatency)
            .putString("resolution",settings.resolution.name).putBoolean("native_upscaling",settings.nativeUpscaling)
            .putString("aspect",settings.aspect.name).putBoolean("local_coop",settings.localCoOp)
            .putBoolean("rumble",settings.rumble).putString("touch_mode",settings.touchMode.name)
            .putInt("sharpness",settings.sharpness.coerceIn(0,100)).putInt("saturation",settings.saturation.coerceIn(0,200))
            .putBoolean("session_report",settings.sessionReport).putString("video_codec",settings.videoCodec.name)
            .putString("fsr_mode",settings.fsr.name).putInt("fsr_sharpness",settings.fsrSharpness.coerceIn(0,100))
            .putInt("brightness",settings.brightness.coerceIn(-50,50)).putInt("contrast",settings.contrast.coerceIn(50,150))
            .putString("ip_preference",settings.ipPreference.name).putString("desktop_input",settings.desktopInput.name)
            .putInt("mouse_sensitivity",settings.mouseSensitivity.coerceIn(25,300)).putString("gyro_mode",settings.gyroMode.name)
            .putInt("gyro_sensitivity",settings.gyroSensitivity.coerceIn(25,300)).putBoolean("microphone",settings.microphone)
            .putInt("audio_gain",settings.audioGain.coerceIn(0,300)).putBoolean("touch_rumble",settings.touchRumble)
            .putBoolean("gyro_native_touch",settings.gyroNativeTouch).putString("fsr_output",settings.fsrOutput.name)
            .putString("image_sharpening",settings.imageTreatment.sharpening.name)
            .putInt("image_deband",settings.imageTreatment.deband.coerceIn(0,100))
            .putInt("image_cleanup",settings.imageTreatment.cleanup.coerceIn(0,100))
            .putInt("image_chroma",settings.imageTreatment.chroma.coerceIn(0,100))
            .putInt("image_antialias",settings.imageTreatment.antialias.coerceIn(0,100))
            .putInt("image_temporal",settings.imageTreatment.temporal.coerceIn(0,50))
            .putInt("image_neural",settings.imageTreatment.neural.coerceIn(0,100))
            .putBoolean("image_auto_protect",settings.imageTreatment.autoProtect)
            .putString("presentation_pacing",settings.presentationPacing.name)
            .putString("reconstruction_quality",settings.reconstruction.quality.name)
            .putInt("reconstruction_strength",settings.reconstruction.strength.coerceIn(0,100))
            .putString("image_comparison",settings.reconstruction.comparison.name).apply()
    }
}

/** Every new game starts hidden. Only an explicit controller/keyboard action changes visibility. */
class StatisticsVisibility {
    @Volatile var mode = 0
        private set
    fun beginSession() { mode = 0 }
    fun cycle(): Int { mode = (mode+1)%3; return mode }
}
