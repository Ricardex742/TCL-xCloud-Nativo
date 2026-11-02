package com.xcloud.tclnative.catalog

import com.xcloud.tclnative.net.awaitResponse
import com.xcloud.tclnative.net.checkedBody
import com.xcloud.tclnative.signaling.GameTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Public metadata enriches only titles returned by the authenticated cloud catalog. */
class CatalogService(
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build(),
    private val baseUrl: String = "https://catalog.gamepass.com"
) {
    suspend fun hydrate(games: List<GameTitle>, onBatch: suspend (List<GameTitle>) -> Unit) = withContext(Dispatchers.IO) {
        var current = games
        for (batch in games.mapNotNull { it.productId }.distinct().chunked(50)) {
            try {
                val url = "$baseUrl/v3/products".toHttpUrl().newBuilder().addQueryParameter("market", "BR")
                    .addQueryParameter("language", "pt-BR").addQueryParameter("hydration", "RemoteHighSapphire0").build()
                val body = JSONObject().put("Products", JSONArray(batch)).toString()
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url(url).header("calling-app-name", "Xbox Cloud Gaming Web")
                    .header("calling-app-version", "26.1.0").post(body).build()
                val data = client.newCall(request).awaitResponse().use { JSONObject(it.checkedBody("CATALOG_ART")) }
                current = merge(current, data)
                onBatch(current)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep playable titles and continue with the next batch. */ }
        }
    }
    suspend fun gallery(id: String): List<String>? = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/sigls/v2".toHttpUrl().newBuilder().addQueryParameter("id", id)
                .addQueryParameter("market", "BR").addQueryParameter("language", "pt-BR").build()
            client.newCall(Request.Builder().url(url).build()).awaitResponse().use {
                galleryIds(JSONArray(it.checkedBody("CATALOG_GALLERY")))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
    companion object {
        const val POPULAR = "e7590b22-e299-44db-ae22-25c61405454c"
        const val RECENTLY_ADDED = "44a55037-770f-4bbf-bde5-a9fa27dba1da"
        fun galleryIds(data: JSONArray): List<String> = (0 until data.length()).mapNotNull {
            data.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() }
        }.distinct()
        fun imageUrl(raw: String?): String? {
            val value = raw?.trim()?.let { if (it.startsWith("//")) "https:$it" else it } ?: return null
            return runCatching { value.toHttpUrl().takeIf { it.isHttps }?.toString() }.getOrNull()
        }
        private fun image(data: JSONObject?, key: String): String? =
            imageUrl(data?.optJSONObject(key)?.optString("URL") ?: data?.optString(key))
        fun merge(games: List<GameTitle>, response: JSONObject): List<GameTitle> {
            val products = response.optJSONObject("Products") ?: return games
            return games.map { game ->
                val data = game.productId?.let { products.optJSONObject(it) } ?: return@map game
                game.copy(name = data.optString("ProductTitle").takeIf { it.isNotBlank() } ?: game.name,
                    posterUrl = image(data, "Image_Poster") ?: game.posterUrl,
                    tileUrl = image(data, "Image_Tile") ?: game.tileUrl)
            }
        }
    }
}
