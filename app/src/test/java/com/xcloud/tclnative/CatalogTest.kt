package com.xcloud.tclnative

import com.xcloud.tclnative.catalog.*
import com.xcloud.tclnative.signaling.GameTitle
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.Proxy

class CatalogTest {
    @Test fun officialArtEnrichesOnlyExistingCloudTitlesAndPreservesTheirLaunchIds() {
        val games = listOf(GameTitle("FORZA", "Old name", "ABC"), GameTitle("SECOND", "Second", "DEF"))
        val data = JSONObject("""{"Products":{"ABC":{"ProductTitle":"Forza Horizon 5","Image_Poster":{"URL":"//store.example/poster.jpg"},"Image_Tile":{"URL":"https://store.example/tile.jpg"}},"NOT-CLOUD":{"ProductTitle":"Must not appear"}}}""")
        val enriched = CatalogService.merge(games, data)
        assertEquals(listOf("FORZA", "SECOND"), enriched.map { it.titleId })
        assertEquals("Forza Horizon 5", enriched.first().name)
        assertEquals("https://store.example/poster.jpg", enriched.first().posterUrl)
        assertEquals("https://store.example/tile.jpg", enriched.first().tileUrl)
        assertEquals(games.last(), enriched.last())
    }
    @Test fun unsafeMissingOrMalformedArtLeavesPlayableCardsWithoutInventedImages() {
        assertNull(CatalogService.imageUrl("javascript:alert(1)"))
        assertNull(CatalogService.imageUrl("http://example.com/art.jpg"))
        assertNull(CatalogService.imageUrl("null"))
        val game = GameTitle("REAL", "Real game", "ABC", posterUrl = "https://example.com/cached.jpg")
        assertEquals(listOf(game), CatalogService.merge(listOf(game), JSONObject("""{"Products":{"ABC":{}}}""")))
    }
    @Test fun publicGalleryOrderIsKeptAndDoesNotMakeUnavailableTitlesPlayable() {
        val gallery = CatalogService.galleryIds(JSONArray("""[{"title":"Header"},{"id":"C"},{"id":"MISSING"},{"id":"A"},{"id":"C"}]"""))
        val games = listOf(GameTitle("one", "One", "A"), GameTitle("two", "Two", "B"), GameTitle("three", "Three", "C"))
        assertEquals(listOf("three", "one"), GameLibrary.select(games, LibraryTab.ADDED, emptySet(), emptyMap(), null, gallery).map { it.titleId })
        assertEquals(listOf("one", "three"), GameLibrary.select(games, LibraryTab.GAME_PASS, emptySet(), emptyMap(), gallery, null).map { it.titleId })
    }
    @Test fun localHistoryAndFavoritesNeverPopulateGamePassWithoutConfirmedMembership() {
        val games = (1..4).map { GameTitle("$it", "Game $it") }
        val played = mapOf("1" to PlayRecord(100, 4), "3" to PlayRecord(200, 1))
        fun select(tab: LibraryTab) = GameLibrary.select(games, tab, setOf("2"), played, null, null)
        assertTrue(select(LibraryTab.RECENT).isEmpty())
        assertEquals(listOf("1", "3"),GameLibrary.select(games,LibraryTab.RECENT,setOf("2"),played,null,null,
            cloudRecent=listOf("1","3")).map { it.titleId })
        assertTrue(select(LibraryTab.GAME_PASS).isEmpty())
        assertEquals(listOf("2"), select(LibraryTab.FAVORITES).map { it.titleId })
        assertTrue(select(LibraryTab.ADDED).isEmpty())
        assertEquals(4, select(LibraryTab.ALL).size)
    }
    @Test fun gamePassTabUsesConfirmedProductIdsEvenWhenOwnedAndFreeGamesAreFavoritedOrMarked() {
        val games=listOf(GameTitle("gp","Included","GP"),GameTitle("owned","Owned","OWNED"),
            GameTitle("free","Free","FREE",gamePass=true),GameTitle("unresolved","Missing product"))
        val records=games.associate { it.titleId to PlayRecord(100,9) }
        fun select(ids: List<String>?)=GameLibrary.select(games,LibraryTab.GAME_PASS,
            games.map { it.titleId }.toSet(),records,ids,null)
        assertEquals(listOf("gp"),select(listOf("GP","NOT-STREAMABLE")).map { it.titleId })
        assertTrue(select(null).isEmpty()); assertTrue(select(emptyList()).isEmpty())
        assertEquals(listOf("gp"),GameLibrary.select(games,LibraryTab.GAME_PASS,emptySet(),records,
            listOf("GP"),null,"Included").map { it.titleId })
    }
    @Test fun subscriptionClaimsRecognizeActiveObjectsAndIgnoreExpiredOrUnrelatedOfferings() {
        val claims=XboxProfile.subscriptionClaims(JSONArray("""[
            {"name":"XboxGamePassUltimate","isActive":false},
            {"tier":"ultimate","status":"expired"},{"subscriptionId":"free","active":true},
            {"subscriptions":{"xgpuweb":true}}
        ]"""))
        assertEquals("FREE",XboxProfile.subscription(claims))
        assertEquals("ULTIMATE",XboxProfile.subscription(XboxProfile.subscriptionClaims(
            JSONObject("""{"subscriptions":[{"subscriptionId":"xgpu","isActive":true}]}"""))))
        assertNull(XboxProfile.subscription(XboxProfile.subscriptionClaims(JSONObject("""{"xgpuweb":true}"""))))
    }
    @Test fun tierAliasesAreAccountClaimsAndProductProgramsNeverBecomeTheUsersTier() {
        for (tier in listOf("ULTIMATE","PREMIUM","STANDARD","ESSENTIAL","CORE")) {
            assertEquals(tier,XboxProfile.subscription(listOf("XGP$tier")))
            assertEquals(tier,XboxProfile.subscription(listOf("GP$tier")))
        }
        assertNull(XboxProfile.subscription(listOf("xgpuweb","GPULTIMATE-BYOG")))
        val game=GameTitle("GP","Game","lowercase",programs=listOf("GPULTIMATE"))
        assertNull(XboxProfile.subscription(game.userSubscriptions+game.userPrograms))
        assertFalse(GamePassCatalog.contains(game,emptySet()))
        assertTrue(GamePassCatalog.contains(game,emptySet(),setOf("GP")))
        val galleryOnly=game.copy(programs=emptyList())
        assertEquals(listOf(galleryOnly),GameLibrary.select(listOf(galleryOnly),LibraryTab.GAME_PASS,
            emptySet(),emptyMap(),listOf("LOWERCASE"),null))
        assertTrue(GameLibrary.select(listOf(galleryOnly.copy(freeToPlay=true)),LibraryTab.GAME_PASS,
            emptySet(),emptyMap(),listOf("LOWERCASE"),null).isEmpty())
    }
    @Test fun xboxGamerscoreNeverBecomesMicrosoftRewardsAndOfferingIdDoesNotProveATier() {
        val profile = XboxProfile.parse(JSONObject("""{"profileUsers":[{"settings":[{"id":"Gamertag","value":"XboxUser"},{"id":"GameDisplayName","value":"Lucas"},{"id":"GameDisplayPicRaw","value":"https://example.com/avatar.jpg"},{"id":"Gamerscore","value":"12550"}]}]}"""))
        assertEquals("Lucas", profile.displayName)
        assertEquals("XboxUser", profile.gamertag)
        assertEquals("https://example.com/avatar.jpg", profile.avatarUrl)
        assertNull(profile.subscription)
        assertNull(XboxProfile.subscription(listOf("xgpuweb")))
        assertNull(XboxProfile.subscription(emptyList()))
        assertEquals("ULTIMATE", XboxProfile.subscription(listOf("XboxGamePassUltimate")))
        assertEquals("FREE", XboxProfile.subscription(listOf("free")))
    }
    @Test fun anArtBatchFailureDoesNotDiscardTheCatalogOrStopLaterArtBatches() = runBlocking {
        val server = MockWebServer().apply { start() }
        val requests = java.util.concurrent.CopyOnWriteArrayList<okhttp3.mockwebserver.RecordedRequest>()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                requests.add(request)
                if (request.requestUrl?.queryParameter("bigIds") == "P50") return MockResponse().setBody(
                    """{"Products":[{"ProductId":"P50","LocalizedProperties":[{"ProductTitle":"Recovered","Images":[{"ImagePurpose":"Poster","Uri":"https://example.com/50.jpg"}]}]}]}""")
                return MockResponse().setResponseCode(503)
            }
        }
        try {
            val games = (0..50).map { GameTitle("cloud-$it", "Game $it", "P$it") }
            var hydrated = games
            val service = CatalogService(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build(), server.url("/").toString().trimEnd('/'))
            service.hydrate(games) { hydrated = it }
            assertEquals(51, hydrated.size)
            assertEquals("Recovered", hydrated.last().name)
            assertEquals("cloud-50", hydrated.last().titleId)
            assertTrue(requests.all { it.getHeader("Authorization") == null })
            val store = requests.first { it.requestUrl?.queryParameter("bigIds")?.contains("P0") == true }
            assertEquals("details", store.requestUrl!!.queryParameter("fieldsTemplate"))
            assertEquals(50, store.requestUrl!!.queryParameter("bigIds")!!.split(",").size)
            val fallback = requests.first { it.method == "POST" }
            assertEquals(50, JSONObject(fallback.body.readUtf8()).getJSONArray("Products").length())
        } finally { server.shutdown() }
    }
    @Test fun storeArrayProvidesOriginalLocalizedArtAndSeriesRequiresOptimizationNotJustCompatibility() {
        val data = JSONObject("""{"Products":[{"ProductId":"ABC","Properties":{"XboxConsoleGenOptimized":["ConsoleGen9"],"XboxConsoleGenCompatible":["ConsoleGen8","ConsoleGen9"]},"LocalizedProperties":[{"Language":"en-US","ProductTitle":"English","Images":[]},{"Language":"pt-BR","ProductTitle":"Forza Horizon 5","Images":[{"ImagePurpose":"Poster","Uri":"//cdn.example/poster.jpg"},{"ImagePurpose":"SquareTile","Uri":"https://cdn.example/tile.jpg"},{"ImagePurpose":"SuperHeroArt","Uri":"https://cdn.example/hero.jpg"}]}]}]}""")
        val game = CatalogService.merge(listOf(GameTitle("FORZAHORIZON5", "FORZAHORIZON5", "ABC")), data).single()
        assertEquals("FORZAHORIZON5", game.titleId)
        assertEquals("Forza Horizon 5", game.name)
        assertEquals("https://cdn.example/poster.jpg", game.posterUrl)
        assertEquals("https://cdn.example/tile.jpg", game.tileUrl)
        assertEquals("https://cdn.example/hero.jpg", game.heroUrl)
        assertTrue(game.seriesOptimized)
        assertFalse(StoreMetadata.optimized(JSONObject("""{"Properties":{"XboxConsoleGenCompatible":["ConsoleGen9"]}}""")))
        assertFalse(StoreMetadata.optimized(JSONObject()))
    }
    @Test fun metadataCacheKeepsRealArtAcrossLaunchesWithoutRestoringUnavailableGamesOrEntitlements() {
        val dir = java.nio.file.Files.createTempDirectory("catalog-cache").toFile()
        try {
            val cache = CatalogCache(java.io.File(dir, "art.json"))
            cache.save(listOf(GameTitle("FORZA", "Forza Horizon 5", "A", "https://example.com/cover.jpg",
                gamePass = true, heroUrl = "https://example.com/background.jpg", seriesOptimized = true)))
            val restored = cache.restore(listOf(GameTitle("FORZA", "FORZA", "A"), GameTitle("NEW", "New", "B")))
            assertEquals("Forza Horizon 5", restored.first().name)
            assertEquals("https://example.com/background.jpg", restored.first().heroUrl)
            assertTrue(restored.first().seriesOptimized)
            assertFalse(restored.first().gamePass)
            assertEquals(listOf("NEW"), cache.restore(listOf(restored.last())).map { it.titleId })
        } finally { dir.deleteRecursively() }
    }
    @Test fun missingStoreIdUsesTheXboxTitleLookupWithoutChangingTheStreamingId() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("""{"Products":[{"ProductId":"STORE","LocalizedProperties":[{"ProductTitle":"Real title","Images":[{"ImagePurpose":"Poster","Uri":"https://example.com/real.jpg"}]}]}]}"""))
            var games = listOf(GameTitle("CLOUD-TITLE", "CLOUD-TITLE", xboxTitleId = "12345"))
            CatalogService(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build(), server.url("/").toString().trimEnd('/'))
                .hydrate(games) { games = it }
            val request = server.takeRequest()
            assertEquals("/v7.0/products/lookup", request.requestUrl!!.encodedPath)
            assertEquals("XboxTitleId", request.requestUrl!!.queryParameter("alternateId"))
            assertEquals("12345", request.requestUrl!!.queryParameter("value"))
            assertEquals("CLOUD-TITLE", games.single().titleId)
            assertEquals("STORE", games.single().productId)
            assertEquals("Real title", games.single().name)
            assertEquals("https://example.com/real.jpg", games.single().posterUrl)
        } finally { server.shutdown() }
    }
}
