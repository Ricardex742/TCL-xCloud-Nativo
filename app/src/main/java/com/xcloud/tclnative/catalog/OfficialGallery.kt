package com.xcloud.tclnative.catalog

import com.xcloud.tclnative.i18n.UiText

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
    val seriesOptimized: Boolean = false, val cloudSupported: Boolean? = null,
    val cloudTitleId: String? = null
)

data class OfficialGallery(
    val kind: OfficialGalleryKind, val products: List<GalleryProduct>,
    val metadataComplete: Boolean = false, val metadataFailed: Boolean = false
) {
    companion object {
        fun parse(kind: OfficialGalleryKind, data: JSONArray): OfficialGallery {
            val header = data.optJSONObject(0) ?: throw IOException(UiText.text("Galeria oficial sem cabeçalho."))
            if (!header.optString("siglId").equals(kind.id, true)) throw IOException("Galeria oficial diferente da solicitada.")
            if (listOf("continuationToken", "nextLink").any { !header.isNull(it) && header.optString(it).isNotBlank() })
                throw IOException(UiText.text("A galeria retornou paginação não reconhecida; a lista não foi confirmada como completa."))
            val ids = linkedSetOf<String>()
            for (index in 1 until data.length()) {
                val id = data.optJSONObject(index)?.optString("id")?.trim()?.uppercase(Locale.ROOT)
                if (id == null || !id.matches(Regex("[A-Z0-9]{12}")))
                    throw IOException(UiText.text("Produto inválido na galeria oficial; atualize o catálogo."))
                ids.add(id)
            }
            return OfficialGallery(kind, ids.map(::GalleryProduct))
        }
    }
}

/** A browse card may have no launch binding yet; a Store product never fabricates a titleId. */
data class CatalogCard(val game: GameTitle? = null, val product: GalleryProduct? = null,
                       val gamePass: Boolean = game?.gamePass == true, val nativeIdentity: Boolean = false) {
    init { require(game != null || product != null);require(!nativeIdentity || game!=null) }
    // A native row keeps its identity when official names/art arrive; gallery editions keep product IDs.
    val key = if(nativeIdentity) "title:${game!!.titleId}" else product?.let { "product:${it.productId}" } ?: "title:${game!!.titleId}"
    val name get() = product?.name ?: game?.name ?: product!!.productId
    val sortKey = name.lowercase(Locale.ROOT)
    val posterUrl get() = product?.posterUrl ?: game?.posterUrl
    val tileUrl get() = product?.tileUrl ?: game?.tileUrl
    val seriesOptimized get() = product?.seriesOptimized == true || game?.seriesOptimized == true
    /** Visual metadata never replaces authenticated identity, product or input capabilities. */
    fun playableGame(): GameTitle? = game?.let { native ->
        if(product==null && native.gamePass==gamePass) native else native.copy(
            name=name,posterUrl=posterUrl,tileUrl=tileUrl,heroUrl=product?.heroUrl ?: native.heroUrl,
            seriesOptimized=seriesOptimized,gamePass=gamePass)
    }
}

/** An explicit Store link is useful only when its target exists unambiguously in this live catalog. */
internal class CloudTitleIndex(games: List<GameTitle>) {
    private val byProduct=games.filter { it.productId!=null }.groupBy { GamePassCatalog.productKey(it.productId!!) }
    private val byTitle=games.groupBy { GamePassCatalog.productKey(it.titleId) }
    fun resolve(product: GalleryProduct): GameTitle? {
        val direct=byProduct[GamePassCatalog.productKey(product.productId)]
        if(!direct.isNullOrEmpty()) return direct.singleOrNull()
        if(product.cloudSupported!=true) return null
        val id=product.cloudTitleId ?: return null
        return byTitle[GamePassCatalog.productKey(id)]?.singleOrNull()
    }
}

/** Trademarks, punctuation and accents must not make an otherwise matching title disappear. */
internal class CatalogQuery(text: String) {
    private val raw=text.trim()
    private val compact=normalize(raw)
    private fun matchesName(name: String)=name.contains(raw,true) || (compact.isNotEmpty() && normalize(name).contains(compact))
    fun matches(card: CatalogCard)=raw.isEmpty() || matchesName(card.name) ||
        (card.game?.name?.takeIf { it!=card.name }?.let(::matchesName) == true)
    private fun normalize(text: String)=java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFD)
        .filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT)
}

object OfficialGalleryCards {
    fun select(gallery: OfficialGallery, games: List<GameTitle>, bindings: Map<String, GameTitle>,
               gamePassProducts: Set<String>, query: String = ""): List<CatalogCard> =
        matching(gallery,CloudTitleIndex(games),bindings,gamePassProducts,query).sortedBy { it.sortKey }.toList()
    fun resolve(product: GalleryProduct,games: List<GameTitle>): GameTitle? = CloudTitleIndex(games).resolve(product)
    internal fun matching(gallery: OfficialGallery, native: CloudTitleIndex,
                          bindings: Map<String,GameTitle>, gamePassProducts: Set<String>, query: String): Sequence<CatalogCard> {
        val search=CatalogQuery(query)
        return gallery.products.asSequence().filter { it.cloudSupported != false }.map { product ->
            val game=bindings[GamePassCatalog.productKey(product.productId)] ?: native.resolve(product)
            CatalogCard(game,product,GamePassCatalog.productKey(product.productId) in gamePassProducts)
        }.filter(search::matches)
    }
}
