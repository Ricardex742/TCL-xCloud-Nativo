package com.xcloud.tclnative.settings

import android.content.Context
import com.xcloud.tclnative.i18n.UiText

/** Reference countries used only for the optional xCloud login header. No traffic is proxied. */
enum class RegionBypass(private val source: String, val forwardedFor: String? = null) {
    OFF("Desativado"),
    BRAZIL("Brasil", "169.150.198.66"),
    UNITED_STATES("Estados Unidos", "143.244.47.65"),
    JAPAN("Japão", "138.199.21.239"),
    SOUTH_KOREA("Coreia do Sul", "121.125.60.151"),
    POLAND("Polônia", "45.134.212.66");

    val label get() = UiText.text(source)
}

/** Device preference available before authentication; it never writes account credentials. */
class RegionBypassStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("network_access", Context.MODE_PRIVATE)
    fun read() = RegionBypass.entries.firstOrNull { it.name == prefs.getString("region_bypass", null) } ?: RegionBypass.OFF
    fun save(value: RegionBypass) { prefs.edit().putString("region_bypass", value.name).apply() }
}
