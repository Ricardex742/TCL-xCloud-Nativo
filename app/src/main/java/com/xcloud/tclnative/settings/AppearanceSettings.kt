package com.xcloud.tclnative.settings

import android.content.Context

enum class UiTheme(val label: String,val accent: Int,val hue: Float,val saturation: Float=1f) {
    GREEN("Verde — padrão",0xff54de69.toInt(),128f),
    CYBER_BLUE("Azul Cibernético",0xff54caff.toInt(),199f),
    VOLCANIC_RED("Vermelho Vulcânico",0xffff6973.toInt(),356f),
    PRESTIGE_GOLD("Dourado Prestígio",0xfff2cd68.toInt(),45f,.65f),
    INDUSTRIAL_ORANGE("Laranja Industrial",0xffffa052.toInt(),27f,.25f),
    GALAXY_PURPLE("Roxo Galáxia",0xffc58aff.toInt(),275f,1.1f),
    PLATINUM_SILVER("Prata Platina",0xffd9e2ef.toInt(),216f,.08f)
}

/** Interface preference only: independent of account, stream profile and video filters. */
class AppearanceSettingsStore(context: Context) {
    private val prefs=context.getSharedPreferences("app_appearance",Context.MODE_PRIVATE)
    fun read(): UiTheme = UiTheme.entries.firstOrNull { it.name==prefs.getString("theme",null) } ?: UiTheme.GREEN
    fun save(theme: UiTheme) { prefs.edit().putString("theme",theme.name).apply() }
}
