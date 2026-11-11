package com.xcloud.tclnative.settings

import com.xcloud.tclnative.i18n.UiText
import org.webrtc.TreatmentOptions

enum class ImageSharpening(private val text: String) {
    LEGACY("Padrão"), RCAS_LUMA("RCAS adaptativo — luminância"), USM_LUMA("USM — luminância");
    val label: String get() = UiText.text(text)
}

enum class PresentationPacing(private val text: String) {
    OFF("Desativado"), SMOOTH("Suave — experimental");
    val label: String get() = UiText.text(text)
}

/** Opt-in stages; saved values are never rewritten by the per-session performance guard. */
data class ImageTreatmentSettings(
    val sharpening: ImageSharpening = ImageSharpening.LEGACY,
    val deband: Int = 0,
    val cleanup: Int = 0,
    val chroma: Int = 0,
    val antialias: Int = 0,
    val temporal: Int = 0,
    val neural: Int = 0,
    val autoProtect: Boolean = true
) {
    val active: Boolean get() = sharpening != ImageSharpening.LEGACY || spatial || temporal > 0 || neural > 0
    val spatial: Boolean get() = deband > 0 || cleanup > 0 || chroma > 0 || antialias > 0
    fun bounded() = copy(deband=deband.coerceIn(0,100),cleanup=cleanup.coerceIn(0,100),
        chroma=chroma.coerceIn(0,100),antialias=antialias.coerceIn(0,100),
        temporal=temporal.coerceIn(0,50),neural=neural.coerceIn(0,100))
    fun glOptions() = bounded().let { TreatmentOptions(it.sharpening.ordinal,it.deband,it.cleanup,
        it.chroma,it.antialias,it.temporal,it.neural) }
}
