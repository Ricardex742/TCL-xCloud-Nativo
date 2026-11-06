package com.xcloud.tclnative.catalog

import com.xcloud.tclnative.signaling.GameTitle
import org.json.JSONArray
import java.io.IOException
import java.util.Locale

/** IDs and Cloud:XGPUWEB context verified against the public Xbox gallery client. */
enum class OfficialGalleryKind(val id: String, val path: String) {
    GAME_PASS("af206485-e87d-4624-9007-cb7f6d0cc42e", "game-pass-games"),
    PURCHASED("e78d9a61-5ef4-43af-b400-edba1250b18e", "buy-and-stream")
}

/** Public Store data, deliberately separate from an authenticated cloud launch ID. */
data class GalleryProduct(
    val productId: String, val name: String? = null,
    val posterUrl: String? = null, val tileUrl: String? = null, val heroUrl: String? = null,
    val seriesOptimized: Boolean = false, val cloudSupported: Boolean? = null
)

data class OfficialGallery(
    val kind: OfficialGalleryKind, val products: List<GalleryProduct>,
    val metadataComplete: Boolean = false, val metadataFailed: Boolean = false
) {
    companion object {
        fun parse(kind: OfficialGalleryKind, data: JSONArray): OfficialGallery {
            val header = data.optJSONObject(0) ?: throw IOException("Galeria oficial sem cabeçalho.")
            if (!header.optString("siglId").equals(kind.id, true)) throw IOException("Galeria oficial diferente da solicitada.")
            if (listOf("continuationToken", "nextLink").any { !header.isNull(it) && header.optString(it).isNotBlank() })
                throw IOException("A galeria retornou paginação não reconhecida; a lista não foi confirmada como completa.")
            val ids = linkedSetOf<String>()
            for (index in 1 until data.length()) {
                val id = data.optJSONObject(index)?.optString("id")?.trim()?.uppercase(Locale.ROOT)
                if (id == null || !id.matches(Regex("[A-Z0-9]{12}")))
                    throw IOException("Produto inválido na galeria oficial; atualize o catálogo.")
                ids.add(id)
            }
            return OfficialGallery(kind, ids.map(::GalleryProduct))
        }
    }
}

/** A browse card may have no launch binding yet; a Store product never fabricates a titleId. */
data class CatalogCard(val game: GameTitle? = null, val product: GalleryProduct? = null, val gamePass: Boolean = game?.gamePass == true) {
    init { require(game != null || product != null) }
    // Comparisons and stable IDs must not allocate a new String for each access.
    val key = product?.let { "product:${it.productId}" } ?: "title:${game!!.titleId}"
    val name get() = product?.name ?: game?.name ?: product!!.productId
    val sortKey = name.lowercase(Locale.ROOT)
    val posterUrl get() = product?.posterUrl ?: game?.posterUrl
    val tileUrl get() = product?.tileUrl ?: game?.tileUrl
    val seriesOptimized get() = product?.seriesOptimized == true || game?.seriesOptimized == true
}

object OfficialGalleryCards {
    fun select(gallery: OfficialGallery, games: List<GameTitle>, bindings: Map<String, GameTitle>,
               gamePassProducts: Set<String>, query: String = ""): List<CatalogCard> {
        val native = games.filter { it.productId != null }.groupBy { GamePassCatalog.productKey(it.productId!!) }
        return matching(gallery,native,bindings,gamePassProducts,query).sortedBy { it.sortKey }.toList()
    }
    internal fun matching(gallery: OfficialGallery, native: Map<String,List<GameTitle>>,
                          bindings: Map<String,GameTitle>, gamePassProducts: Set<String>, query: String): Sequence<CatalogCard> =
        gallery.products.asSequence().filter { it.cloudSupported != false }.map { product ->
            val game = bindings[product.productId] ?: native[product.productId]?.singleOrNull()
            CatalogCard(game, product, product.productId in gamePassProducts)
        }.filter { query.isBlank() || it.name.contains(query.trim(), true) }
}
