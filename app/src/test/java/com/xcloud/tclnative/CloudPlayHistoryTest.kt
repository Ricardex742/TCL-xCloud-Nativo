package com.xcloud.tclnative

import com.xcloud.tclnative.auth.XCloudSession
import com.xcloud.tclnative.catalog.CloudPlayHistory
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.signaling.XCloudSignalingClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.Proxy

class CloudPlayHistoryTest {
    private val games=listOf(GameTitle("FORZA","Forza Horizon 5","P-FORZA",xboxTitleId="111"),
        GameTitle("FORTNITE","Fortnite","P-FORTNITE",xboxTitleId="222"),GameTitle("COD","Call of Duty","P-COD"))

    @Test fun serverOrderSurvivesCatalogSortingAndUsesCloudProductOrXboxIds() {
        val result=CloudPlayHistory.resolve(games,JSONObject("""{"results":[
            {"details":{"productId":"p-fortnite"}}, {"titleId":"forza"},
            {"titleId":"COD"}, {"details":{"xboxTitleId":"111"}}, {"titleId":"FORTNITE"}
        ]}"""))
        assertEquals(listOf("FORTNITE","FORZA","COD"),result)
        assertEquals(result,CloudPlayHistory.select(games.reversed(),result).map { it.titleId })
    }
    @Test fun historyCannotInventAccessOrChooseAnAmbiguousEdition() {
        val catalog=games+listOf(GameTitle("EDITION-A","Edition A","SHARED"),GameTitle("EDITION-B","Edition B","SHARED"))
        val result=CloudPlayHistory.resolve(catalog,JSONObject("""{"results":[
            {"titleId":"NO-LONGER-CLOUD","details":{"productId":"MISSING"}},
            {"details":{"productId":"SHARED"}}, {"titleId":"EDITION-B","details":{"productId":"SHARED"}},
            {"details":{"xboxTitleId":"222"}}, null, {}, {"details":{"productId":null}}
        ]}"""))
        assertEquals(listOf("EDITION-B","FORTNITE"),result)
        assertEquals(listOf("FORZA"),CloudPlayHistory.select(games,listOf("unknown","forza","FORZA")).map { it.titleId })
    }
    @Test fun confirmedEmptyIsAcceptedButMissingOrMalformedResultsAreNotEmptyHistory() {
        assertTrue(CloudPlayHistory.resolve(games,JSONObject("""{"results":[]}""")).isEmpty())
        for(body in listOf("{}","""{"results":null}""","""{"results":{}}""")) {
            try { CloudPlayHistory.resolve(games,JSONObject(body)); fail("Malformed history must preserve the cache") }
            catch(_: JSONException) { }
        }
    }
    @Test fun historyIsBoundedToTwentyFivePlayableUniqueTitles() {
        val catalog=(0..49).map { GameTitle("GAME-$it","Game $it") }
        val rows=org.json.JSONArray((0..49).map { JSONObject().put("titleId","GAME-$it") })
        assertEquals(catalog.take(25).map { it.titleId },CloudPlayHistory.resolve(catalog,JSONObject().put("results",rows)))
    }
    @Test fun authenticatedMruRequestUsesTheSessionServiceAndPreservesItsOrder() = runBlocking {
        val server=MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("""{"results":[{"titleId":"COD"},{"details":{"productId":"P-FORZA"}}]}"""))
            val client=XCloudSignalingClient(XCloudSession("test-history-token",server.url("/").toString(),"history-user"),
                OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build())
            assertEquals(listOf("COD","FORZA"),client.fetchRecentGames(games))
            val request=server.takeRequest()
            assertEquals("GET",request.method)
            assertEquals("/v2/titles/mru?mr=25",request.path)
            assertEquals("Bearer test-history-token",request.getHeader("Authorization"))
            assertEquals("XboxComBrowser",request.getHeader("X-Gssv-Client"))
            assertNotNull(request.getHeader("X-MS-Device-Info"))
        } finally { server.shutdown() }
    }
    @Test fun serviceFailureIsNotConvertedIntoSuccessfulEmptyHistory() = runBlocking {
        val server=MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
            server.enqueue(MockResponse().setBody("{}"))
            val client=XCloudSignalingClient(XCloudSession("test-history-token",server.url("/").toString(),"history-user"),
                OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build())
            try { client.fetchRecentGames(games); fail("HTTP failure must be visible to the caller") } catch(_: IOException) { }
            try { client.fetchRecentGames(games); fail("Malformed success must preserve cached history") } catch(_: JSONException) { }
        } finally { server.shutdown() }
    }
}
