package com.xcloud.tclnative.catalog

import com.xcloud.tclnative.signaling.GameTitle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Handles Store v7 arrays and the older Game Pass object map without changing cloud launch IDs. */
object StoreMetadata {
    private fun JSONObject.text(key: String) = optString(key).takeIf { it.isNotBlank() && it != "null" }
    fun products(response: JSONObject): List<JSONObject> = when (val data = response.opt("Products")) {
        is JSONArray -> (0 until data.length()).mapNotNull { data.optJSONObject(it) }
        is JSONObject -> data.keys().asSequence().mapNotNull { key -> data.optJSONObject(key)?.let {
            if (it.text("ProductId") == null && it.text("StoreId") == null) JSONObject(it.toString()).put("StoreId", key) else it
        } }.toList()
        else -> emptyList()
    }
    private fun direct(data: JSONObject, key: String) = CatalogService.imageUrl(
        data.optJSONObject(key)?.text("URL") ?: data.text(key))
    private fun localized(data: JSONObject): JSONObject? {
        val list = data.optJSONArray("LocalizedProperties") ?: return null
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .firstOrNull { it.optString("Language").equals("pt-BR", true) } ?: list.optJSONObject(0)
    }
    private fun artwork(localized: JSONObject?, purposes: List<String>): String? {
        val list = localized?.optJSONArray("Images") ?: return null
        val images = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
        for (purpose in purposes) {
            val found = images.filter { it.optString("ImagePurpose").equals(purpose, true) }
                .sortedByDescending { it.optInt("Height") }.firstNotNullOfOrNull { CatalogService.imageUrl(it.text("Uri")) }
            if (found != null) return found
        }
        return null
    }
    private fun strings(value: Any?): List<String> = when (value) {
        is JSONArray -> (0 until value.length()).mapNotNull { (value.opt(it) as? String)?.lowercase() }
        is String -> listOf(value.lowercase())
        else -> emptyList()
    }
    fun optimized(data: JSONObject): Boolean {
        val supported = setOf("consolegen9", "xboxseriesx", "xboxseriess", "xboxseriesxs")
        // Compatibility with Series alone is backward compatibility, not a Series edition badge.
        return data.optBoolean("SeriesOptimized", false) || strings(data.optJSONObject("Properties")
            ?.opt("XboxConsoleGenOptimized")).any { it.replace(Regex("[^a-z0-9]"), "") in supported }
    }
    fun enrich(game: GameTitle, data: JSONObject): GameTitle {
        val local = localized(data)
        return game.copy(
            productId = data.text("ProductId") ?: data.text("StoreId") ?: game.productId,
            name = local?.text("ProductTitle") ?: data.text("ProductTitle") ?: game.name,
            posterUrl = artwork(local, listOf("Poster", "BoxArt", "BrandedKeyArt", "Portrait"))
                ?: direct(data, "Image_Poster") ?: game.posterUrl,
            tileUrl = artwork(local, listOf("SquareTile", "Tile", "BoxArt")) ?: direct(data, "Image_Tile") ?: game.tileUrl,
            heroUrl = artwork(local, listOf("SuperHeroArt", "TitledHeroArt", "Hero", "Screenshot"))
                ?: direct(data, "Image_Hero") ?: game.heroUrl,
            seriesOptimized = optimized(data) || game.seriesOptimized)
    }
    fun merge(games: List<GameTitle>, response: JSONObject): List<GameTitle> {
        val data = products(response)
        val byProduct = data.mapNotNull { value -> (value.text("ProductId") ?: value.text("StoreId"))?.let { it to value } }.toMap()
        val byCloud = data.mapNotNull { value -> value.text("XCloudTitleId")?.let { it to value } }.toMap()
        return games.map { game -> (byProduct[game.productId] ?: byCloud[game.titleId])?.let { enrich(game, it) } ?: game }
    }
}

/** Public art only. A cached product never adds a playable title or restores account entitlements. */
class CatalogCache(private val file: File) {
    fun restore(games: List<GameTitle>): List<GameTitle> = try {
        if (!file.exists() || file.length() > 10*1024*1024) games else StoreMetadata.merge(games, JSONObject(file.readText()))
    } catch (_: Exception) { games }
    fun save(games: List<GameTitle>) {
        try {
            val products = JSONArray()
            games.filter { it.productId != null && (it.posterUrl != null || it.tileUrl != null) }.forEach { game ->
                products.put(JSONObject().put("StoreId", game.productId).put("XCloudTitleId", game.titleId)
                    .put("ProductTitle", game.name).put("Image_Poster", game.posterUrl).put("Image_Tile", game.tileUrl)
                    .put("Image_Hero", game.heroUrl).put("SeriesOptimized", game.seriesOptimized))
            }
            file.parentFile?.mkdirs()
            val next = File(file.parentFile, file.name+".tmp")
            next.writeText(JSONObject().put("Products", products).toString())
            next.renameTo(file)
        } catch (_: Exception) { /* Cache failure does not block browsing/playing. */ }
    }
}
