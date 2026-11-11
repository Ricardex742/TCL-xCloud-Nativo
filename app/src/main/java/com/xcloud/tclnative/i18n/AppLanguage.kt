package com.xcloud.tclnative.i18n

import android.content.Context
import java.util.Locale

enum class AppLanguage(val tag: String,val nativeName: String) {
    PORTUGUESE("pt-BR","Português (Brasil)"), ENGLISH("en-US","English (United States)"),
    SPANISH("es-ES","Español (España)"), FRENCH("fr-FR","Français (France)"),
    GERMAN("de-DE","Deutsch (Deutschland)"), ITALIAN("it-IT","Italiano (Italia)"),
    JAPANESE("ja-JP","日本語（日本）"), KOREAN("ko-KR","한국어 (대한민국)"),
    CHINESE("zh-CN","简体中文（中国）"), RUSSIAN("ru-RU","Русский (Россия)"),
    TURKISH("tr-TR","Türkçe (Türkiye)");
    val locale: Locale get()=Locale.forLanguageTag(tag)
}

class AppLanguageStore(context: Context) {
    private val prefs=context.getSharedPreferences("app_language",Context.MODE_PRIVATE)
    fun read()=AppLanguage.entries.firstOrNull { it.tag==prefs.getString("language",null) } ?: AppLanguage.PORTUGUESE
    fun save(language: AppLanguage) { prefs.edit().putString("language",language.tag).apply() }
}
