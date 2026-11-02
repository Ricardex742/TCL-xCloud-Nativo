package com.xcloud.tclnative.catalog

import com.xcloud.tclnative.net.awaitResponse
import com.xcloud.tclnative.net.checkedBody
import com.xcloud.tclnative.signaling.GameTitle
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
    private val baseUrl: String = "https://catalog.gamepass.com",
    private val storeBaseUrl: String = if (baseUrl == "https://catalog.gamepass.com")
        "https://displaycatalog.mp.microsoft.com" else baseUrl,
    private val cache: CatalogCache? = null
) {
    data class Progress(val covers: Int, val total: Int, val finished: Boolean, val failed: Boolean)
    suspend fun hydrate(games: List<GameTitle>, onProgress: suspend (Progress) -> Unit = {},
                        onBatch: suspend (List<GameTitle>) -> Unit) = withContext(Dispatchers.IO) {
        var current = cache?.restore(games) ?: games
        onBatch(current)
        onProgress(Progress(current.count { it.posterUrl != null || it.tileUrl != null }, games.size, false, false))
        val mutex = Mutex()
        val slots = Semaphore(4)
        var completed = 0
        var failed = false
        val chunks = games.filter { it.productId != null }.chunked(50)
        coroutineScope {
            chunks.map { batch -> async {
                slots.withPermit {
                    var enriched = batch
                    try {
                        val url = "$storeBaseUrl/v7.0/products".toHttpUrl().newBuilder()
                            .addQueryParameter("bigIds", batch.mapNotNull { it.productId }.distinct().joinToString(","))
                            .addQueryParameter("market", "BR").addQueryParameter("languages", "pt-br,en-us")
                            .addQueryParameter("fieldsTemplate", "details").addQueryParameter("actionFilter", "Browse").build()
                        val data = client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
                            .awaitResponse().use { JSONObject(it.checkedBody("STORE_ART")) }
                        enriched = merge(enriched, data)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Retry missing art using the older Game Pass provider. */ }
                    val missing = enriched.filter { it.posterUrl == null && it.tileUrl == null }
                    if (missing.isNotEmpty()) try {
                        val url = "$baseUrl/v3/products".toHttpUrl().newBuilder().addQueryParameter("market", "BR")
                            .addQueryParameter("language", "pt-BR").addQueryParameter("hydration", "RemoteHighSapphire0").build()
                        val body = JSONObject().put("Products", JSONArray(missing.mapNotNull { it.productId })).toString()
                            .toRequestBody("application/json".toMediaType())
                        val request = Request.Builder().url(url).header("calling-app-name", "Xbox Cloud Gaming Web")
                            .header("calling-app-version", "26.1.0").post(body).build()
                        val data = client.newCall(request).awaitResponse().use { JSONObject(it.checkedBody("CATALOG_ART")) }
                        enriched = merge(enriched, data)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { }
                    mutex.withLock {
                        val updates = enriched.associateBy { it.titleId }
                        // Do not overwrite valid cached art with an incomplete/missing response.
                        current = current.map { old -> updates[old.titleId]?.let { fresh -> fresh.copy(
                            name = if (fresh.name == fresh.titleId) old.name else fresh.name,
                            posterUrl = fresh.posterUrl ?: old.posterUrl, tileUrl = fresh.tileUrl ?: old.tileUrl,
                            heroUrl = fresh.heroUrl ?: old.heroUrl, seriesOptimized = fresh.seriesOptimized || old.seriesOptimized)
                        } ?: old }
                        failed = failed || enriched.any { it.posterUrl == null && it.tileUrl == null }
                        completed++
                        onBatch(current)
                        onProgress(Progress(current.count { it.posterUrl != null || it.tileUrl != null }, games.size,
                            completed == chunks.size, failed))
                    }
                }
            } }.awaitAll()
        }
        // Some catalog versions omit productId but retain the Xbox title ID. Resolve these
        // against the Store alternate-ID endpoint instead of guessing an ID from the game name.
        val lookups = current.filter { it.productId == null && it.xboxTitleId != null }
        coroutineScope {
            lookups.map { game -> async {
                slots.withPermit {
                    try {
                        val url = "$storeBaseUrl/v7.0/products/lookup".toHttpUrl().newBuilder()
                            .addQueryParameter("alternateId", "XboxTitleId").addQueryParameter("value", game.xboxTitleId)
                            .addQueryParameter("market", "BR").addQueryParameter("languages", "pt-br,en-us")
                            .addQueryParameter("fieldsTemplate", "details").addQueryParameter("top", "1").build()
                        val data = client.newCall(Request.Builder().url(url).build()).awaitResponse().use {
                            JSONObject(it.checkedBody("STORE_LOOKUP"))
                        }
                        StoreMetadata.products(data).firstOrNull()?.let { product -> mutex.withLock {
                            current = current.map { if (it.titleId == game.titleId) StoreMetadata.enrich(it, product) else it }
                            onBatch(current)
                            onProgress(Progress(current.count { it.posterUrl != null || it.tileUrl != null }, games.size, false, failed))
                        } }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { mutex.withLock { failed = true } }
                }
            } }.awaitAll()
        }
        cache?.save(current)
        onProgress(Progress(current.count { it.posterUrl != null || it.tileUrl != null }, games.size, true,
            failed || current.any { it.posterUrl == null && it.tileUrl == null }))
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
        const val GAME_PASS = "29a81209-df6f-41fd-a528-2ae6b91f719c"
        const val FREE_TO_PLAY = "d8f4afcd-882a-49e3-86b3-f61fa0172b75"
        fun merge(games: List<GameTitle>, response: JSONObject): List<GameTitle> = StoreMetadata.merge(games, response)
    }
}
