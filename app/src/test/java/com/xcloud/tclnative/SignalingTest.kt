package com.xcloud.tclnative

import com.xcloud.tclnative.auth.XCloudSession
import com.xcloud.tclnative.signaling.XCloudSignalingClient
import com.xcloud.tclnative.webrtc.StreamingPeer
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.webrtc.IceCandidate
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class SignalingTest {
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val events = CopyOnWriteArrayList<String>()
    private var rejectIce = false
    private var rejectCatalog = false
    private var rejectTransport = false
    private val offer = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=rtpmap:96 H264/90000\r\n"
    private val peer = object : StreamingPeer {
        override suspend fun createOfferSdp(): Pair<String, List<IceCandidate>> {
            events.add("offer")
            return offer to listOf(IceCandidate("0", 0, "candidate:1 1 UDP 1 192.168.1.2 5000 typ host"),
                IceCandidate("1", 1, "candidate:2 1 UDP 1 2001:db8::1 5001 typ host"))
        }
        override suspend fun setRemoteAnswer(answerSdp: String) { assertEquals("v=0\r\nanswer", answerSdp); events.add("answer") }
        override suspend fun addRemoteCandidates(candidates: List<IceCandidate>) {
            assertEquals(1, candidates.size); assertTrue(candidates.single().sdp.startsWith("candidate:")); events.add("remoteIce")
        }
        override suspend fun awaitReady() { events.add("ready"); if (rejectTransport) throw IOException("TRANSPORT: failed") }
    }
    @Before fun setup() {
        requests.clear(); events.clear(); rejectIce = false; rejectCatalog = false; rejectTransport = false
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                return when (request.method to request.path) {
                    "GET" to "/v2/titles" -> if (rejectCatalog) response("{}", 503) else response("""{"results":[{"titleId":"real-id","details":{"productTitle":"Real Game"}}]}""")
                    "POST" to "/v5/sessions/cloud/play" -> response("""{"sessionPath":"/session"}""")
                    "GET" to "/session/state" -> response("""{"state":"ReadyToConnect"}""")
                    "POST" to "/session/sdp" -> response("{}")
                    "GET" to "/session/sdp" -> response(JSONObject().put("exchangeResponse", JSONObject().put("sdp", "v=0\r\nanswer").toString()).toString())
                    "POST" to "/session/ice" -> {
                        events.add(if (events.contains("answer")) "postIceAfterAnswer" else "postIceBeforeAnswer")
                        if (rejectIce) response("""{"code":"BadIce","token":"must-not-leak"}""", 400) else response("", 204)
                    }
                    "GET" to "/session/ice" -> response(JSONObject().put("exchangeResponse", JSONArray().put(JSONObject()
                        .put("candidate", "a=candidate:remote 1 UDP 1 203.0.113.1 9002 typ host").put("sdpMid", "0").put("sdpMLineIndex", 0)).toString()).toString())
                    "POST" to "/session/keepalive", "DELETE" to "/session" -> response("", 204)
                    else -> response("{}", 404)
                }
            }
        }
        server.start()
    }
    @After fun cleanup() { server.shutdown() }
    private fun response(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)
    private fun signaling() = XCloudSignalingClient(XCloudSession("test-token", server.url("/").toString(), "hash"),
        OkHttpClient.Builder().proxy(Proxy.NO_PROXY).callTimeout(3, TimeUnit.SECONDS).build(), {})

    @Test fun establishesSdpBeforeIcePreservesOfferAndWaitsForTransportReadiness() = runBlocking {
        val signaling = signaling()
        val result = signaling.startAndConnectWebRtcSession("real-id", peer) {}
        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        assertEquals(listOf("offer", "answer", "postIceAfterAnswer", "remoteIce", "ready"), events.toList())
        val sdp = JSONObject(requests.single { it.method == "POST" && it.path == "/session/sdp" }.body.readUtf8())
        assertEquals(offer, sdp.getString("sdp"))
        val ice = JSONObject(requests.single { it.method == "POST" && it.path == "/session/ice" }.body.readUtf8())
        assertEquals("iceCandidate", ice.getString("messageType")); assertEquals(3, ice.getJSONArray("candidate").length())
        assertFalse(result.getOrThrow().contains("Vídeo ativo"))
        signaling.keepAlive(); signaling.stop(); signaling.stop()
        assertEquals(1, requests.count { it.method == "DELETE" })
    }
    @Test fun iceRejectionIsReportedAndSessionIsDeletedWithoutClaimingConnection() = runBlocking {
        rejectIce = true
        val result = signaling().startAndConnectWebRtcSession("real-id", peer) {}
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("ICE_LOCAL"))
        assertTrue(result.exceptionOrNull()!!.message!!.contains("BadIce"))
        assertFalse(result.exceptionOrNull()!!.message!!.contains("must-not-leak"))
        assertFalse(events.contains("ready")); assertEquals(1, requests.count { it.method == "DELETE" })
    }
    @Test fun failedTransportCannotReturnAConnectedStatus() = runBlocking {
        rejectTransport = true
        assertTrue(signaling().startAndConnectWebRtcSession("real-id", peer) {}.isFailure)
        assertEquals(1, requests.count { it.method == "DELETE" })
    }
    @Test fun realCatalogContainsNoHardcodedVerifiedGames() = runBlocking {
        val games = signaling().fetchCatalogGames()
        assertEquals(listOf("real-id"), games.map { it.titleId }); assertEquals("Real Game", games.single().name)
    }
    @Test fun catalogHttpFailureDoesNotReturnFakeSuccess() = runBlocking {
        rejectCatalog = true
        try { signaling().fetchCatalogGames(); fail("Catalog failure was hidden") }
        catch (e: IOException) { assertTrue(e.message!!.contains("CATALOG")) }
    }
    @Test fun cancellingDuringOfferCreationDeletesTheRemoteSessionAndRemainsCancelled() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val waitingPeer = object : StreamingPeer by peer {
            override suspend fun createOfferSdp(): Pair<String, List<IceCandidate>> {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val job = async { signaling().startAndConnectWebRtcSession("real-id", waitingPeer) {} }
        withTimeout(2_000) { entered.await() }
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertEquals(1, requests.count { it.method == "DELETE" })
        assertFalse(events.contains("ready"))
    }
}
