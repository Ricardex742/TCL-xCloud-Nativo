package com.xcloud.tclnative

import com.xcloud.tclnative.auth.CloudRegion
import com.xcloud.tclnative.auth.XCloudSession
import com.xcloud.tclnative.settings.SessionOptions
import com.xcloud.tclnative.signaling.XCloudSignalingClient
import com.xcloud.tclnative.webrtc.StreamingPeer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.IceCandidate
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList

class RegionChatSignalingTest {
    private class Endpoint: AutoCloseable {
        val server=MockWebServer()
        val requests=CopyOnWriteArrayList<RecordedRequest>()
        var id="1";var staleChat=true;var rejectChat=false
        init {
            server.dispatcher=object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    val body=when(request.method to request.path) {
                        "POST" to "/v5/sessions/cloud/play" -> "{\"sessionPath\":\"/session\"}"
                        "GET" to "/session/state" -> "{\"state\":\"Provisioned\"}"
                        "POST" to "/session/sdp" -> {
                            val payload=JSONObject(request.body.clone().readUtf8());id=payload.getString("requestId")
                            if(id!="1" && rejectChat) return MockResponse().setResponseCode(400).setBody("{}")
                            "{}"
                        }
                        "GET" to "/session/sdp" -> {
                            val responseId=if(id!="1" && staleChat) { staleChat=false;"1" } else id
                            JSONObject().put("exchangeResponse",JSONObject().put("requestId",responseId).put("sdp","answer-$responseId").put("chatStream",1).put("input",8)).toString()
                        }
                        "GET" to "/session/ice" -> JSONObject().put("exchangeResponse",JSONArray().put(JSONObject()
                            .put("candidate","candidate:1 1 UDP 100 192.0.2.1 5000 typ host").put("sdpMid","0").put("sdpMLineIndex",0))).toString()
                        else -> "{}"
                    }
                    return MockResponse().setBody(body)
                }
            }
            server.start()
        }
        val uri get()=server.url("/").toString().trimEnd('/')
        override fun close() { server.shutdown() }
    }
    private class Peer: StreamingPeer {
        var supported=false
        override fun configureChat(supported: Boolean) { this.supported=supported }
        override suspend fun createOfferSdp()="offer" to listOf(IceCandidate("0",0,"candidate:1 1 UDP 100 192.0.2.1 5000 typ host"))
        override suspend fun setRemoteAnswer(answerSdp: String) { assertEquals("answer-1",answerSdp) }
        override suspend fun addRemoteCandidates(candidates: List<IceCandidate>) { assertEquals(1,candidates.size) }
        override suspend fun awaitReady() {}
    }
    @Test fun selectedAccountRegionAndLocaleAreUsedAndChatRenegotiatesTheExistingSession()=runBlocking {
        Endpoint().use { original -> Endpoint().use { region ->
            val session=XCloudSession("token",original.uri,"hash",regions=listOf(CloudRegion("offered",region.uri)))
            val client=XCloudSignalingClient(session,OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build(),pause={})
            val peer=Peer()
            assertTrue(client.startAndConnectWebRtcSession("game",peer,options=SessionOptions(5000,"en-US","offered"),microphone=true) {}.isSuccess)
            assertTrue(peer.supported);assertEquals(0,original.requests.size)
            val play=JSONObject(region.requests.single { it.path=="/v5/sessions/cloud/play" }.body.clone().readUtf8())
            assertEquals("en-US",play.getJSONObject("settings").getString("locale"))
            val initial=JSONObject(region.requests.single { it.path=="/session/sdp" && it.method=="POST" }.body.clone().readUtf8())
            assertTrue(initial.getJSONObject("configuration").has("chatStream"))
            assertEquals("answer-2",client.renegotiateChat("chat-offer"))
            val posts=region.requests.filter { it.path=="/session/sdp" && it.method=="POST" }
            assertEquals(2,posts.size)
            assertTrue(JSONObject(posts.last().body.clone().readUtf8()).getJSONObject("configuration").getBoolean("isMediaStreamsChatRenegotiation"))
            assertEquals(1,region.requests.count { it.path=="/v5/sessions/cloud/play" })
            assertEquals(0,region.requests.count { it.method=="DELETE" })
            client.stop();assertEquals(1,region.requests.count { it.method=="DELETE" })
        } }
    }
    @Test fun anUnavailableSavedRegionUsesDefaultAndChatFailureDoesNotStopTheGame()=runBlocking {
        Endpoint().use { endpoint ->
            val session=XCloudSession("token",endpoint.uri,"hash")
            val client=XCloudSignalingClient(session,OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build(),pause={})
            val peer=Peer()
            assertTrue(client.startAndConnectWebRtcSession("game",peer,options=SessionOptions(region="not-offered")) {}.isSuccess)
            assertFalse(peer.supported)
            assertTrue(client.profileDiagnostics().contains("região salva não oferecida"))
            val initial=JSONObject(endpoint.requests.single { it.path=="/session/sdp" && it.method=="POST" }.body.clone().readUtf8())
            assertFalse(initial.getJSONObject("configuration").has("chatStream"))
            endpoint.rejectChat=true
            try { client.renegotiateChat("chat-offer");fail("chat rejection expected") } catch(_: java.io.IOException) { }
            client.keepAlive()
            assertEquals(0,endpoint.requests.count { it.method=="DELETE" })
            assertEquals(1,endpoint.requests.count { it.path=="/session/keepalive" })
            client.stop()
        }
    }
}
