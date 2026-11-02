package com.xcloud.tclnative.catalog

import org.json.JSONObject

data class XboxProfile(val displayName: String = "PERFIL XBOX", val gamertag: String = "Conta conectada",
                       val avatarUrl: String? = null, val subscription: String? = null,
                       val rewardsPoints: Long? = null) {
    companion object {
        fun parse(data: JSONObject): XboxProfile {
            val values = data.optJSONArray("profileUsers")?.optJSONObject(0)?.optJSONArray("settings")
            val settings = values?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i)?.let { value ->
                value.optString("id") to value.optString("value")
            } }.toMap() } ?: emptyMap()
            val tag = settings["Gamertag"]?.takeIf { it.isNotBlank() } ?: "PERFIL XBOX"
            return XboxProfile(settings["GameDisplayName"]?.takeIf { it.isNotBlank() } ?: tag, tag,
                CatalogService.imageUrl(settings["GameDisplayPicRaw"]))
            // Gamerscore is deliberately not interpreted as Microsoft Rewards points.
        }
        fun subscription(values: List<String>): String? {
            val normalized = values.map { it.lowercase().replace(Regex("[^a-z0-9]"), "") }
            return when {
                normalized.any { it in setOf("ultimate", "xgpu", "xboxgamepassultimate", "gamepassultimate") } -> "ULTIMATE"
                normalized.any { it in setOf("premium", "gamepasspremium", "xboxgamepasspremium") } -> "PREMIUM"
                normalized.any { it in setOf("standard", "gamepassstandard", "xboxgamepassstandard") } -> "STANDARD"
                normalized.any { it in setOf("essential", "gamepassessential", "xboxgamepassessential") } -> "ESSENTIAL"
                normalized.any { it in setOf("core", "gamepasscore", "xboxgamepasscore") } -> "CORE"
                normalized.any { it == "free" } -> "FREE"
                else -> null
            }
        }
    }
}
