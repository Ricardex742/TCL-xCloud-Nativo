package com.xcloud.tclnative

import com.xcloud.tclnative.catalog.*
import com.xcloud.tclnative.signaling.GameTitle
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.Proxy

class OfficialGalleryTest {
    private fun fixture(name: String) = javaClass.getResource("/catalog/$name-br-2026-11-06.json")!!.readText()
    private fun gallery(kind: OfficialGalleryKind, vararg ids: String) = JSONArray().put(JSONObject().put("siglId",kind.id)).also { a ->
        ids.forEach { a.put(JSONObject().put("id",it)) }
    }
    private fun service(server: MockWebServer, gate: suspend () -> Unit = {}) = CatalogService(
        client=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build(), baseUrl=server.url("/").toString().trimEnd('/'), beforeRequest=gate)

    @Test fun completeOfficialResponsesKeepEveryProductIncludingFreeOptionsAndPurchasedOverlap() {
        val pass=OfficialGallery.parse(OfficialGalleryKind.GAME_PASS,JSONArray(fixture("gamepass")))
        val purchased=OfficialGallery.parse(OfficialGalleryKind.PURCHASED,JSONArray(fixture("purchased")))
        assertEquals(560,pass.products.size); assertEquals(3781,purchased.products.size)
        val passIds=pass.products.map { it.productId }.toSet()
        assertTrue("The official Game Pass gallery includes Fortnite; do not subtract free games", "BT5P2X999VH2" in passIds)
        assertEquals(496,passIds.intersect(purchased.products.map { it.productId }.toSet()).size)
        // No authenticated launch bindings are required to browse ALL official products.
        val cards=OfficialGalleryCards.select(pass,emptyList(),emptyMap(),passIds)
        assertEquals(pass.products.size,cards.size)
        assertTrue(cards.all { it.game==null && it.product!=null && it.gamePass })
        assertEquals(pass.products.size,cards.map { it.key }.distinct().size)
        assertEquals(3781,OfficialGalleryCards.select(purchased,emptyList(),emptyMap(),passIds).size)
    }

    @Test fun missingOrWrongMetadataMalformedRowsAndPaginationCannotBecomeAConfirmedEmptyList() {
        val kind=OfficialGalleryKind.GAME_PASS
        val cases=listOf(JSONArray(),JSONArray("[{\"id\":\"BT5P2X999VH2\"}]"),
            gallery(OfficialGalleryKind.PURCHASED,"BT5P2X999VH2"),gallery(kind,"invalid"),
            gallery(kind,"BT5P2X999VH2").also { it.getJSONObject(0).put("continuationToken","more") })
        cases.forEach { data ->
            try { OfficialGallery.parse(kind,data); fail("An incomplete gallery was accepted: $data") }
            catch(_: IOException) { }
        }
        assertTrue(OfficialGallery.parse(kind,gallery(kind)).products.isEmpty())
        assertEquals(1,OfficialGallery.parse(kind,gallery(kind,"bt5p2x999vh2","BT5P2X999VH2")).products.size)
    }

    @Test fun editionsHaveDistinctCardsAndOnlyUnambiguousAuthenticatedBindingsCanLaunch() {
        val a=GalleryProduct("AAAAAAAAAAAA","Primeira edição")
        val b=GalleryProduct("BBBBBBBBBBBB","Segunda edição")
        val known=GameTitle("real-cloud-id","Jogo","AAAAAAAAAAAA",supportedInputTypes=setOf("NativeTouch"))
        val data=OfficialGallery(OfficialGalleryKind.GAME_PASS,listOf(a,b))
        val cards=OfficialGalleryCards.select(data,listOf(known),emptyMap(),setOf(a.productId,b.productId))
        assertSame(known,cards.single { it.product==a }.game)
        assertNull(cards.single { it.product==b }.game)
        val bound=OfficialGalleryCards.select(data,listOf(known),mapOf(b.productId to known),setOf(a.productId,b.productId))
        assertEquals(2,bound.size); assertEquals(2,bound.map { it.key }.distinct().size)
        assertTrue(bound.all { it.game?.titleId=="real-cloud-id" })
        assertTrue(OfficialGalleryCards.select(data,listOf(known,known.copy(titleId="ambiguous")),emptyMap(),emptySet())
            .all { it.game==null })
        assertEquals(listOf(b),OfficialGalleryCards.select(data,emptyList(),emptyMap(),emptySet(),"segunda").map { it.product })
        val unsupported=StoreMetadata.galleryProduct(JSONObject("""{"StoreId":"CCCCCCCCCCCC","ProductTitle":"PC only","XCloudOfferings":{"PC":{"Programs":["GPULTIMATE"]}}}"""))!!
        assertEquals(false,unsupported.cloudSupported)
        assertTrue(OfficialGalleryCards.select(data.copy(products=listOf(unsupported)),emptyList(),emptyMap(),emptySet()).isEmpty())
        val unknown=StoreMetadata.galleryProduct(JSONObject("""{"StoreId":"CCCCCCCCCCCC","XCloudOfferings":{"XGPUWEB":{}}}"""))!!
        assertNull(unknown.cloudSupported)
    }

    @Test fun bothOfficialGalleriesUseV3CloudPlatformBrazilAndNoAccountCredential() = runBlocking {
        val server=MockWebServer().apply { start() }
        try {
            val service=service(server)
            for(kind in OfficialGalleryKind.entries) {
                server.enqueue(MockResponse().setBody(gallery(kind,"BT5P2X999VH2").toString()))
                assertEquals(1,service.officialGallery(kind).products.size)
                val request=server.takeRequest();val url=request.requestUrl!!
                assertEquals("/sigls/v3",url.encodedPath); assertEquals(kind.id,url.queryParameter("id"))
                assertEquals("BR",url.queryParameter("market")); assertEquals("pt-BR",url.queryParameter("language"))
                assertEquals("Cloud:XGPUWEB",url.queryParameter("platformContext")); assertEquals("none",url.queryParameter("subscriptionContext"))
                assertNull(request.getHeader("Authorization")); assertNull(url.queryParameter("top"))
            }
            server.enqueue(MockResponse().setResponseCode(503))
            try { service.officialGallery(OfficialGalleryKind.GAME_PASS); fail("Unavailable is not empty") }
            catch(_: IOException) { }
        } finally { server.shutdown() }
    }

    @Test fun galleryMetadataCannotAddProductsOrNativeLaunchIdsAndFailuresKeepTheFullList() = runBlocking {
        val server=MockWebServer().apply { start() }
        try {
            val service=service(server)
            val source=OfficialGallery.parse(OfficialGalleryKind.PURCHASED,
                gallery(OfficialGalleryKind.PURCHASED,"AAAAAAAAAAAA","BBBBBBBBBBBB"))
            server.enqueue(MockResponse().setBody("""{"Products":[
                {"ProductId":"BBBBBBBBBBBB","XCloudOfferings":{"XGPUWEB":{"Programs":["GPULTIMATE"]}},"LocalizedProperties":[{"Language":"pt-BR","ProductTitle":"Meu jogo","Images":[{"ImagePurpose":"Poster","Uri":"https://example.com/poster.png"}]}]},
                {"ProductId":"CCCCCCCCCCCC","ProductTitle":"Not in this gallery"}]}"""))
            var loaded=source
            service.hydrateOfficialGallery(source,setOf("AAAAAAAAAAAA")) { loaded=it }
            assertEquals(listOf("AAAAAAAAAAAA","BBBBBBBBBBBB"),loaded.products.map { it.productId })
            assertEquals("Meu jogo",loaded.products.last().name);assertTrue(loaded.metadataComplete);assertFalse(loaded.metadataFailed)
            val request=server.takeRequest()
            assertEquals("/v3/products",request.requestUrl!!.encodedPath)
            assertEquals("RemoteLowJade0",request.requestUrl!!.queryParameter("hydration"))
            assertEquals("BBBBBBBBBBBB",JSONObject(request.body.readUtf8()).getJSONArray("Products").getString(0))
            assertTrue(request.getHeader("MS-CV")!!.matches(Regex("[A-Za-z0-9+/]{22}[.]0")))
            assertNull(request.getHeader("Authorization"))
            server.enqueue(MockResponse().setResponseCode(503))
            service.hydrateOfficialGallery(source,setOf("AAAAAAAAAAAA")) { loaded=it }
            assertEquals(2,loaded.products.size);assertTrue(loaded.metadataFailed)
        } finally { server.shutdown() }
    }

    @Test fun aPausedCatalogDoesNotStartNewOfficialRequestsDuringGameplay() = runBlocking {
        val server=MockWebServer().apply { start() }
        val gate=CatalogWorkGate().apply { pause() }
        try {
            server.enqueue(MockResponse().setBody(gallery(OfficialGalleryKind.GAME_PASS,"BT5P2X999VH2").toString()))
            val pending=async(start=CoroutineStart.UNDISPATCHED) { service(server,gate::await).officialGallery(OfficialGalleryKind.GAME_PASS) }
            yield();assertEquals(0,server.requestCount)
            gate.resume();assertEquals(1,pending.await().products.size)
        } finally { server.shutdown() }
    }
}
