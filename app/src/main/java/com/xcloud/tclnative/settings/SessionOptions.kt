package com.xcloud.tclnative.settings

import com.xcloud.tclnative.i18n.UiText

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

enum class IpPreference(private val sourceLabel: String,val preferredFamily: Int? = null) {
    AUTO("Automático — IPv4/IPv6 durante a partida"),
    IPV4("Preferir IPv4 — com IPv6 disponível",4),
    IPV6("Preferir IPv6 — experimental, com IPv4 disponível",6);
    val label: String get()=UiText.text(sourceLabel)
}
enum class DesktopInputMode(private val sourceLabel: String) { OFF("Desativado"), EMULATED("Emular controle com mouse e teclado"), NATIVE("Mouse e teclado nativos — jogos compatíveis") ;
    val label: String get()=UiText.text(sourceLabel)
}
enum class GyroMode(private val sourceLabel: String) { OFF("Desativado"), LT("Enquanto segurar LT"), LB("Enquanto segurar LB"), ALWAYS("Sempre durante a partida") ;
    val label: String get()=UiText.text(sourceLabel)
}

data class SessionOptions(val maxBitrateKbps: Int=0,val locale: String="pt-BR",val region: String="") {
    fun normalized()=copy(maxBitrateKbps=if(maxBitrateKbps==0) 0 else maxBitrateKbps.coerceIn(1000,50000),
        locale=locale.takeIf { it in LOCALES } ?: "pt-BR",region=region.take(100))
    companion object {
        val LOCALES=linkedMapOf("pt-BR" to "Português (Brasil)","en-US" to "English (US)","es-MX" to "Español (México)",
            "es-ES" to "Español (España)","fr-FR" to "Français","de-DE" to "Deutsch","it-IT" to "Italiano","ja-JP" to "日本語")
    }
}

/** Account-scoped defaults plus per-game snapshots. Games without an override inherit the defaults. */
class SessionOptionsStore(context: Context,account: String) {
    private val scope=MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString("") { "%02x".format(it) }
    private val prefs=context.getSharedPreferences("session_options_$scope",Context.MODE_PRIVATE)
    fun read(game: String?=null): SessionOptions {
        val defaults=decode(prefs.getString("default",null),SessionOptions())
        return if(game==null) defaults else decode(prefs.getString("game:$game",null),defaults)
    }
    fun hasOverride(game: String)=prefs.contains("game:$game")
    fun save(game: String?,options: SessionOptions) {
        val value=options.normalized()
        prefs.edit().putString(if(game==null) "default" else "game:$game",JSONObject().put("bitrate",value.maxBitrateKbps)
            .put("locale",value.locale).put("region",value.region).toString()).apply()
    }
    fun reset(game: String) { prefs.edit().remove("game:$game").apply() }
    private fun decode(raw: String?,defaults: SessionOptions): SessionOptions = runCatching {
        if(raw==null) defaults else JSONObject(raw).let { SessionOptions(it.optInt("bitrate",defaults.maxBitrateKbps),
            it.optString("locale",defaults.locale),it.optString("region",defaults.region)).normalized() }
    }.getOrDefault(defaults)
}
