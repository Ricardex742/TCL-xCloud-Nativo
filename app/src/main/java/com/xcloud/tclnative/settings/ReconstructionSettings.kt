package com.xcloud.tclnative.settings

import com.xcloud.tclnative.i18n.UiText

enum class ReconstructionQuality(val level: Int,private val source: String) {
    AUTO(1,"Automático"), LIGHT(1,"Leve"), BALANCED(2,"Equilibrado"), QUALITY(3,"Qualidade");
    val label get()=UiText.text(source)
}
enum class ImageComparison(private val source: String) {
    PROCESSED("Imagem tratada"), ORIGINAL("Imagem original"), SPLIT("Dividida — original à esquerda");
    val label get()=UiText.text(source)
}
data class ReconstructionSettings(val quality: ReconstructionQuality=ReconstructionQuality.AUTO,
    val strength: Int=50,val comparison: ImageComparison=ImageComparison.PROCESSED) {
    fun bounded()=copy(strength=strength.coerceIn(0,100))
}
