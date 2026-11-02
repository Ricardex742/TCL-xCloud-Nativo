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
        assertEquals(listOf("three", "one"), GameLibrary.select(games, LibraryTab.POPULAR, emptySet(), emptyMap(), gallery, null).map { it.titleId })
    }
    @Test fun localRecentAndMostPlayedUseRecordedSessionsAndDoNotMislabelAlphabeticalLists() {
        val games = (1..4).map { GameTitle("$it", "Game $it") }
        val played = mapOf("1" to PlayRecord(100, 4), "3" to PlayRecord(200, 1))
        fun select(tab: LibraryTab) = GameLibrary.select(games, tab, setOf("2"), played, null, null)
        assertEquals(listOf("3", "1"), select(LibraryTab.RECENT).map { it.titleId })
        assertEquals(listOf("1", "3"), select(LibraryTab.POPULAR).map { it.titleId })
        assertEquals(listOf("2"), select(LibraryTab.FAVORITES).map { it.titleId })
        assertTrue(select(LibraryTab.ADDED).isEmpty())
        assertEquals(4, select(LibraryTab.ALL).size)
    }
    @Test fun xboxGamerscoreNeverBecomesMicrosoftRewardsAndOfferingIdDoesNotProveATier() {
        val profile = XboxProfile.parse(JSONObject("""{"profileUsers":[{"settings":[{"id":"Gamertag","value":"XboxUser"},{"id":"GameDisplayName","value":"Lucas"},{"id":"GameDisplayPicRaw","value":"https://example.com/avatar.jpg"},{"id":"Gamerscore","value":"12550"}]}]}"""))
        assertEquals("Lucas", profile.displayName)
        assertEquals("XboxUser", profile.gamertag)
        assertEquals("https://example.com/avatar.jpg", profile.avatarUrl)
        assertNull(profile.rewardsPoints)
        assertNull(XboxProfile.subscription(listOf("xgpuweb")))
        assertNull(XboxProfile.subscription(emptyList()))
        assertEquals("ULTIMATE", XboxProfile.subscription(listOf("XboxGamePassUltimate")))
        assertEquals("FREE", XboxProfile.subscription(listOf("free")))
    }
    @Test fun anArtBatchFailureDoesNotDiscardTheCatalogOrStopLaterArtBatches() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("""{"Products":{"P50":{"ProductTitle":"Recovered","Image_Poster":{"URL":"https://example.com/50.jpg"}}}}"""))
            val games = (0..50).map { GameTitle("cloud-$it", "Game $it", "P$it") }
            var hydrated = games
            val service = CatalogService(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build(), server.url("/").toString().trimEnd('/'))
            service.hydrate(games) { hydrated = it }
            assertEquals(51, hydrated.size)
            assertEquals("Recovered", hydrated.last().name)
            assertEquals("cloud-50", hydrated.last().titleId)
            val first = server.takeRequest()
            assertNull(first.getHeader("Authorization")) // Never send GSSV/XSTS to the public art service.
            assertTrue(first.path!!.contains("market=BR"))
            assertEquals(50, JSONObject(first.body.readUtf8()).getJSONArray("Products").length())
            assertEquals(1, JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("Products").length())
        } finally { server.shutdown() }
    }
}
