package com.xcloud.tclnative.catalog

import org.json.JSONObject

data class XboxProfile(val displayName: String = "PERFIL XBOX", val gamertag: String = "Conta conectada",
                       val avatarUrl: String? = null, val subscription: String? = null, val xuid: String? = null) {
    companion object {
        private val claimSeparators=Regex("[^a-z0-9]")
        private val shortTierPrefix=Regex("^(?:xgp|gp)(?=(?:ultimate|premium|standard|essential|core)$)")
        fun parse(data: JSONObject): XboxProfile {
            val values = data.optJSONArray("profileUsers")?.optJSONObject(0)?.optJSONArray("settings")
            val settings = values?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i)?.let { value ->
                value.optString("id") to value.optString("value")
            } }.toMap() } ?: emptyMap()
            val tag = settings["Gamertag"]?.takeIf { it.isNotBlank() } ?: "PERFIL XBOX"
            return XboxProfile(settings["GameDisplayName"]?.takeIf { it.isNotBlank() } ?: tag, tag,
                CatalogService.imageUrl(settings["GameDisplayPicRaw"]), xuid = data.optJSONArray("profileUsers")
                    ?.optJSONObject(0)?.optString("id")?.takeIf { it.matches(Regex("[0-9]{1,20}")) })
            // Gamerscore does not declare a subscription tier.
        }
        fun subscriptionClaims(value: Any?, depth: Int = 0): List<String> {
            if (depth > 4) return emptyList()
            return when (value) {
                is String -> listOf(value)
                is org.json.JSONArray -> (0 until value.length()).flatMap { subscriptionClaims(value.opt(it), depth+1) }
                is JSONObject -> {
                    if (!value.optBoolean("isActive", value.optBoolean("active",true)) ||
                        value.optString("status").lowercase() in setOf("expired","inactive","cancelled","suspended")) emptyList()
                    else value.keys().asSequence().flatMap { key ->
                        val item=value.opt(key)
                        when {
                            item == true -> sequenceOf(key)
                            key in setOf("id","name","subscriptionId","tier","type","productName","productId") && item is String -> sequenceOf(item)
                            item is JSONObject || item is org.json.JSONArray -> subscriptionClaims(item,depth+1).asSequence()
                            else -> emptySequence()
                        }
                    }.toList()
                }
                else -> emptyList()
            }
        }
        fun subscription(values: List<String>): String? {
            val normalized = values.map { it.lowercase().replace(claimSeparators, "").replace(shortTierPrefix, "") }
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
