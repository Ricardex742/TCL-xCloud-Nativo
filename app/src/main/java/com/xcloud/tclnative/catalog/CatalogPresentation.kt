package com.xcloud.tclnative.catalog

import com.xcloud.tclnative.signaling.GameTitle

/** Immutable inputs captured on Main; all catalog-sized work runs on the catalog worker. */
internal data class CatalogInput(
    val games: List<GameTitle>,
    val galleries: Map<OfficialGalleryKind, OfficialGallery?>,
    val bindings: Map<String, GameTitle>,
    val legacyProducts: Set<String>?,
    val legacyTitles: Set<String>,
    val tab: LibraryTab,
    val favorites: Set<String>,
    val added: List<String>?,
    val recent: List<String>?,
    val query: String
)

internal data class CatalogPresentation(
    val cards: List<CatalogCard>,
    val positions: Map<String, Int>,
    val subscription: String?,
    val gamePassProducts: Set<String>?
) {
    companion object {
        fun build(input: CatalogInput): CatalogPresentation {
            val products = if (input.galleries.containsKey(OfficialGalleryKind.GAME_PASS))
                input.galleries[OfficialGalleryKind.GAME_PASS]?.products?.map { it.productId }?.toSet()
                else input.legacyProducts?.map(GamePassCatalog::productKey)?.toSet()
            val membership = products ?: emptySet()
            val titles = input.legacyTitles.map(GamePassCatalog::productKey).toSet()
            val games = input.games.map { game ->
                val member = GamePassCatalog.contains(game, membership, titles)
                if (game.gamePass == member) game else game.copy(gamePass = member)
            }
            val native=CloudTitleIndex(games)
            val search=CatalogQuery(input.query)
            val kind = when (input.tab) {
                LibraryTab.GAME_PASS -> OfficialGalleryKind.GAME_PASS
                LibraryTab.PURCHASED -> OfficialGalleryKind.PURCHASED
                else -> null
            }
            val gallery=input.galleries[kind]
            val cards=if(gallery!=null) {
                OfficialGalleryCards.matching(gallery,native,input.bindings,membership,input.query).sortedBy { it.sortKey }.toList()
            } else {
                val official=input.galleries.values.filterNotNull().asSequence().flatMap {
                    OfficialGalleryCards.matching(it,native,input.bindings,membership,"")
                }.distinctBy { it.key }.toList()
                val byProduct=official.associateBy { it.product!!.productId }
                val byTitle=official.filter { it.game!=null }.groupBy { GamePassCatalog.productKey(it.game!!.titleId) }
                fun display(game: GameTitle): CatalogCard {
                    val direct=game.productId?.let { byProduct[GamePassCatalog.productKey(it)] }
                        ?.takeIf { it.game?.titleId?.equals(game.titleId,true)==true }
                    val alias=direct ?: byTitle[GamePassCatalog.productKey(game.titleId)]?.singleOrNull()
                    return CatalogCard(game,alias?.product,alias?.gamePass ?: game.gamePass,nativeIdentity=true)
                }
                // Search the displayed aliases too, after preserving the requested category/order.
                val selected=GameLibrary.select(games,input.tab,input.favorites,emptyMap(),
                    products?.toList(),input.added,"",titles,input.recent).map(::display)
                if(input.tab==LibraryTab.ALL && official.isNotEmpty()) {
                    val represented=selected.mapNotNull { it.product?.productId }.toSet()
                    (selected.asSequence()+official.asSequence().filter { it.product!!.productId !in represented })
                        .filter(search::matches).sortedBy { it.sortKey }.toList()
                } else selected.filter(search::matches)
            }
            return CatalogPresentation(cards, cards.withIndex().associate { it.value.key to it.index },
                XboxProfile.subscription(input.games.flatMap { it.userSubscriptions + it.userPrograms }), products)
        }
    }
}

/** Reorders replace a stable-ID snapshot once, avoiding quadratic move detection/dispatch. */
internal data class CatalogChanges(val replace: Boolean, val ranges: List<IntRange>) {
    companion object {
        fun between(before: List<CatalogCard>, next: List<CatalogCard>): CatalogChanges {
            if (before.size != next.size || before.indices.any { before[it].key != next[it].key })
                return CatalogChanges(true, emptyList())
            val ranges = mutableListOf<IntRange>()
            var start = -1
            for (index in next.indices) {
                if (before[index] != next[index]) {
                    if (start == -1) start = index
                } else if (start != -1) { ranges.add(start until index); start = -1 }
            }
            if (start != -1) ranges.add(start until next.size)
            // Bound RecyclerView notification work even for fragmented metadata batches.
            return CatalogChanges(false, if (ranges.size > 32) listOf(next.indices) else ranges)
        }
    }
}
