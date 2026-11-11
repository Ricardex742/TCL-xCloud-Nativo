package com.xcloud.tclnative

import com.xcloud.tclnative.auth.XCloudSession
import com.xcloud.tclnative.signaling.XCloudSignalingClient
import com.xcloud.tclnative.webrtc.StreamingPeer
import com.xcloud.tclnative.webrtc.StreamResolution
import com.xcloud.tclnative.webrtc.LocalIceOfferRetry
import com.xcloud.tclnative.webrtc.LocalIceUnavailableException
import com.xcloud.tclnative.catalog.*
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
    private var now = 0L
    private var estimateBody = """{"estimatedTotalWaitTimeInSeconds":300}"""
    private var rejectEstimate = false
    private var stallEstimate = false
    private var failAfterFirstEstimate = false
    private var estimateCalls = 0
    private var estimateEntered = CompletableDeferred<Unit>()
    private var queuedStates = 0
    private var connectSent = false
    private var provisioned = false
    private var rejectConnect = false
    private var failedState = false
    private var rejectIce = false
    private var rejectCatalog = false
    private var rejectTransport = false
    private var rejectSdp = false
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
        estimateBody = """{"estimatedTotalWaitTimeInSeconds":300}"""; rejectEstimate = false
        stallEstimate = false; failAfterFirstEstimate = false; estimateCalls = 0; estimateEntered = CompletableDeferred()
        now = 0; queuedStates = 0; connectSent = false; provisioned = false; rejectConnect = false; failedState = false
        requests.clear(); events.clear(); rejectIce = false; rejectCatalog = false; rejectTransport = false; rejectSdp = false
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                return when (request.method to request.path) {
                    "GET" to "/v2/titles" -> if (rejectCatalog) response("{}", 503) else response("""{"results":[{"titleId":"real-id","productId":"TOPLEVEL-ID","details":{"productTitle":"Real Game","xboxTitleId":"123456"}}]}""")
                    "GET" to "/v1/waittime/real-id" -> {
                        estimateEntered.complete(Unit)
                        estimateCalls++
                        when {
                            stallEstimate -> MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
                            rejectEstimate || (failAfterFirstEstimate && estimateCalls > 1) -> response("{}", 503)
                            else -> response(estimateBody)
                        }
                    }
                    "POST" to "/v5/sessions/cloud/play" -> response("""{"sessionPath":"/session"}""")
                    "GET" to "/session/state" -> when {
                        failedState -> response("""{"state":"Failed","errorDetails":{"code":"NoCapacity"}}""")
                        queuedStates-- > 0 -> response("""{"state":"WaitingForResources"}""")
                        !connectSent -> response("""{"state":"ReadyToConnect"}""")
                        else -> { provisioned = true; response("""{"state":"Provisioned"}""") }
                    }
                    "POST" to "/session/connect" -> {
                        assertEquals("console-token", JSONObject(request.body.clone().readUtf8()).getString("userToken"))
                        events.add("connect")
                        if (rejectConnect) response("""{"code":"Unauthorized","token":"must-not-leak"}""", 401)
                        else { connectSent = true; response("", 204) }
                    }
                    "POST" to "/session/sdp" -> if(rejectSdp) response("{}",400) else if (provisioned) response("{}") else
                        response("""{"code":"SessionNotConnectable"}""", 400)
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
    private fun signaling(queueTimeout: Long = 15 * 60_000, transferToken: String = "console-token") = XCloudSignalingClient(
        XCloudSession("test-token", server.url("/").toString(), "hash", transferToken = transferToken),
        OkHttpClient.Builder().proxy(Proxy.NO_PROXY).callTimeout(3, TimeUnit.SECONDS).build(),
        { now += it }, { now }, queueTimeout)

    @Test fun establishesSdpBeforeIcePreservesOfferAndWaitsForTransportReadiness() = runBlocking {
        val signaling = signaling()
        val result = signaling.startAndConnectWebRtcSession("real-id", peer) {}
        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        assertEquals(listOf("connect", "offer", "answer", "postIceAfterAnswer", "remoteIce", "ready"), events.toList())
        val playRequest = requests.single { it.path == "/v5/sessions/cloud/play" }
        assertEquals("tizen", JSONObject(playRequest.body.readUtf8()).getJSONObject("settings").getString("osName"))
        val device = JSONObject(playRequest.getHeader("X-MS-Device-Info")!!).getJSONObject("dev")
        assertEquals("tizen", device.getJSONObject("os").getString("name"))
        assertEquals(1920, device.getJSONObject("displayInfo").getJSONObject("dimensions").getInt("widthInPixels"))
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
    @Test fun offerPostIsConfirmedOnlyAfterHttpSuccessAndBeforeReadingTheAnswer() = runBlocking {
        var posted=false
        val audited=object: StreamingPeer by peer {
            override fun onOfferPosted() {
                assertTrue(requests.any { it.method=="POST" && it.path=="/session/sdp" })
                assertFalse(requests.any { it.method=="GET" && it.path=="/session/sdp" })
                posted=true
            }
            override suspend fun setRemoteAnswer(answerSdp: String) {
                assertTrue(posted)
                peer.setRemoteAnswer(answerSdp)
            }
        }
        val client=signaling()
        assertTrue(client.startAndConnectWebRtcSession("real-id",audited) {}.isSuccess)
        assertTrue(posted)
        client.stop()
    }
    @Test fun aRejectedSdpPostDoesNotReportAnOfferAsSentOrApplyAnAnswer() = runBlocking {
        rejectSdp=true
        var posted=false
        val audited=object: StreamingPeer by peer { override fun onOfferPosted() { posted=true } }
        val result=signaling().startAndConnectWebRtcSession("real-id",audited) {}
        assertTrue(result.isFailure)
        assertFalse(posted)
        assertFalse(events.contains("answer"))
        assertEquals(1,requests.count { it.method=="DELETE" })
    }
    @Test fun selectedResolutionReachesBothPlaySettingsAndDeviceHeadersAcrossSessions() = runBlocking {
        val client=signaling()
        for (resolution in listOf(StreamResolution.HD_720,StreamResolution.HD_1080,StreamResolution.HD_1080_HQ)) {
            connectSent=false; provisioned=false
            assertTrue(client.startAndConnectWebRtcSession("real-id",peer,resolution=resolution) {}.isSuccess)
            val request=requests.last { it.path=="/v5/sessions/cloud/play" }
            assertEquals(resolution.osName,JSONObject(request.body.clone().readUtf8()).getJSONObject("settings").getString("osName"))
            val device=JSONObject(request.getHeader("X-MS-Device-Info")!!).getJSONObject("dev")
            assertEquals(resolution.osName,device.getJSONObject("os").getString("name"))
            val dimensions=device.getJSONObject("displayInfo").getJSONObject("dimensions")
            assertEquals(resolution.width,dimensions.getInt("widthInPixels"))
            assertEquals(resolution.height,dimensions.getInt("heightInPixels"))
            client.stop()
        }
    }
    @Test fun nativeTouchKeepsEverySelectedVideoProfileAndNextControllerSessionWorks() = runBlocking {
        val client=signaling()
        for(resolution in StreamResolution.entries) {
            connectSent=false; provisioned=false
            assertTrue(client.startAndConnectWebRtcSession("FORTNITE",peer,resolution=resolution,nativeTouch=true) {}.isSuccess)
            val mobile=requests.last { it.path=="/v5/sessions/cloud/play" }
            assertEquals(resolution.osName,JSONObject(mobile.body.clone().readUtf8()).getJSONObject("settings").getString("osName"))
            val device=JSONObject(mobile.getHeader("X-MS-Device-Info")!!).getJSONObject("dev")
            assertEquals(resolution.osName,device.getJSONObject("os").getString("name"))
            assertEquals(resolution.width,device.getJSONObject("displayInfo").getJSONObject("dimensions").getInt("widthInPixels"))
            assertEquals(resolution.height,device.getJSONObject("displayInfo").getJSONObject("dimensions").getInt("heightInPixels"))
            assertFalse(device.getJSONObject("hw").getString("model").contains("Surface"))
            assertTrue(client.profileDiagnostics().contains(resolution.osName))
            client.stop()
        }
        connectSent=false; provisioned=false
        assertTrue(client.startAndConnectWebRtcSession("real-id",peer) {}.isSuccess)
        val tv=requests.last { it.path=="/v5/sessions/cloud/play" }
        assertEquals("tizen",JSONObject(tv.body.clone().readUtf8()).getJSONObject("settings").getString("osName"))
        client.stop()
    }
    @Test fun failedTransportCannotReturnAConnectedStatus() = runBlocking {
        rejectTransport = true
        assertTrue(signaling().startAndConnectWebRtcSession("real-id", peer) {}.isFailure)
        assertEquals(1, requests.count { it.method == "DELETE" })
    }
    @Test fun realCatalogContainsNoHardcodedVerifiedGames() = runBlocking {
        val games = signaling().fetchCatalogGames()
        assertEquals(listOf("real-id"), games.map { it.titleId }); assertEquals("Real Game", games.single().name)
        assertEquals("TOPLEVEL-ID", games.single().productId)
        assertEquals("123456", games.single().xboxTitleId)
    }
    @Test fun onlyOfficialProductLookupsRecoverGamePassEditionsEvenWhenEveryGameAdvertisesGpUltimate() = runBlocking {
        server.dispatcher=object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if(request.method=="POST") {
                    val body=JSONObject(request.body.readUtf8())
                    assertEquals("productId",body.getString("alternateIdType"))
                    assertEquals(1,body.getJSONArray("alternateIds").length())
                    val id=body.getJSONArray("alternateIds").getString(0)
                    return when(id) {
                        "BUNDLE-FH5" -> response("""{"results":[{"titleId":"FORZAHORIZON5"}]}""")
                        "BUNDLE-FH6" -> response("""{"results":[{"titleId":"FORZAHORIZON6"}]}""")
                        "UNFILTERED" -> response("""{"totalItems":2788,"results":[{"titleId":"OWNED"}]}""")
                        else -> response("{}",503)
                    }
                }
                return response("""{"results":[
                {"titleId":"FORZAHORIZON5","details":{"productId":"CLOUD-FH5","programs":["GPULTIMATE"],"userSubscriptions":["XGPULTIMATE"]}},
                {"titleId":"FORZAHORIZON6","details":{"productId":"CLOUD-FH6","programs":["GPULTIMATE"],"userPrograms":["GPULTIMATE"],"supportedInputTypes":["CustomTouchOverlay","NativeTouch"]}},
                {"titleId":"OWNED","details":{"productId":"OWNED","programs":["GPULTIMATE"],"userPrograms":["GPULTIMATE"],"hasEntitlement":true,"userSubscriptions":["XGPULTIMATE"]}},
                {"titleId":"FREE","details":{"productId":"FREE","isFreeInStore":true,"programs":["GPULTIMATE"]}}
            ]}""")
            }
        }
        val client=signaling()
        val games=client.fetchCatalogGames()
        assertEquals("ULTIMATE",XboxProfile.subscription(games.flatMap { it.userSubscriptions+it.userPrograms }))
        assertEquals(setOf("CustomTouchOverlay","NativeTouch"),games.single { it.titleId=="FORZAHORIZON6" }.supportedInputTypes)
        fun selected(ids: List<String>?,titles: Set<String> = emptySet())=GameLibrary.select(games,LibraryTab.GAME_PASS,emptySet(),emptyMap(),ids,null,gamePassTitles=titles)
        assertTrue(selected(listOf("BUNDLE-FH5","BUNDLE-FH6")).isEmpty())
        val resolved=client.resolveCatalogProducts(setOf("BUNDLE-FH5","BUNDLE-FH6","FAILURE","UNFILTERED"))
        assertEquals(setOf("FORZAHORIZON5","FORZAHORIZON6"),resolved)
        assertEquals(listOf("FORZAHORIZON5","FORZAHORIZON6"),selected(listOf("BUNDLE-FH5","BUNDLE-FH6"),resolved).map { it.titleId })
        assertTrue(selected(null).isEmpty())
        assertEquals(listOf("FREE"),selected(listOf("FREE")).map { it.titleId })
    }
    @Test fun galleryProductsResolveOnlyToASingleAuthenticatedTitleWithItsRealInputCapabilities() = runBlocking {
        val bodies=java.util.ArrayDeque<String>().apply {
            add("""{"results":[{"titleId":"actual-id","details":{"productId":"OTHEREDITION","productTitle":"Real game","supportedInputTypes":["NativeTouch"]}}]}""")
            add("""{"results":[]}""")
            add("""{"totalItems":900,"results":[{"titleId":"unfiltered"}]}""")
            add("""{"results":[{"titleId":"one"},{"titleId":"two"}]}""")
        }
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertTrue(request.getHeader("Authorization")!!.startsWith("Bearer "))
                val data=JSONObject(request.body.readUtf8())
                assertEquals("productId",data.getString("alternateIdType"))
                assertEquals("BT5P2X999VH2",data.getJSONArray("alternateIds").getString(0))
                return response(bodies.removeFirst())
            }
        }
        val client=signaling()
        val game=client.resolveGalleryProduct("BT5P2X999VH2")!!
        assertEquals("actual-id",game.titleId);assertEquals(setOf("NativeTouch"),game.supportedInputTypes)
        repeat(3) {assertNull(client.resolveGalleryProduct("BT5P2X999VH2"))}
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
    @Test fun localIceRetryKeepsTheAllocatedXboxSessionAndPostsOnlyTheSuccessfulOffer() = runBlocking {
        var attempts=0;var disposed=false
        val recovering=object: StreamingPeer by peer {
            override suspend fun createOfferSdp()=LocalIceOfferRetry.prepare(create={ attempt ->
                attempts++
                if(attempt==1) throw LocalIceUnavailableException(attempt,"coleta vazia")
                assertTrue(disposed)
                peer.createOfferSdp()
            },discard={ disposed=true },onRetry={})
        }
        val client=signaling()
        assertTrue(client.startAndConnectWebRtcSession("real-id",recovering) {}.isSuccess)
        assertEquals(2,attempts)
        assertEquals(1,requests.count { it.method=="POST" && it.path=="/v5/sessions/cloud/play" })
        assertEquals(1,requests.count { it.method=="POST" && it.path=="/session/connect" })
        assertEquals(1,requests.count { it.method=="POST" && it.path=="/session/sdp" })
        assertEquals(1,requests.count { it.method=="POST" && it.path=="/session/ice" })
        assertEquals(0,requests.count { it.method=="DELETE" })
        client.stop()
        assertEquals(1,requests.count { it.method=="DELETE" })
    }
    @Test fun twoEmptyLocalIceCollectionsStopTheXboxSessionWithoutPostingSdpOrFakingCandidates() = runBlocking {
        var attempts=0
        val unavailable=object: StreamingPeer by peer {
            override suspend fun createOfferSdp()=LocalIceOfferRetry.prepare(create={ attempt ->
                attempts++;throw LocalIceUnavailableException(attempt,"coleta vazia")
            },discard={},onRetry={})
        }
        val error=signaling().startAndConnectWebRtcSession("real-id",unavailable) {}.exceptionOrNull()!!
        assertEquals(2,attempts)
        assertTrue(error.message!!.startsWith("ICE_LOCAL:"))
        assertFalse(requests.any { it.path=="/session/sdp" || it.path=="/session/ice" })
        assertEquals(1,requests.count { it.method=="DELETE" })
    }

    @Test fun keepAliveSocketFailureKeepsTheRequestStageForThePostMatchReport() = runBlocking {
        val client=XCloudSignalingClient(
            XCloudSession("test-token",server.url("/").toString(),"hash",transferToken="console-token"),
            OkHttpClient.Builder().proxy(Proxy.NO_PROXY).callTimeout(3,TimeUnit.SECONDS).addInterceptor { chain ->
                if(chain.request().url.encodedPath=="/session/keepalive")
                    throw java.net.SocketTimeoutException("private endpoint token must not appear in report")
                chain.proceed(chain.request())
            }.build(),{now+=it},{now})
        assertTrue(client.startAndConnectWebRtcSession("real-id",peer) {}.isSuccess)
        val error=try { client.keepAlive();null } catch(e: com.xcloud.tclnative.net.CloudRequestException) { e }
        assertNotNull(error);assertEquals("KEEPALIVE",error!!.stage)
        val reason=com.xcloud.tclnative.diagnostics.SessionFailure.from(error)
        assertEquals("KEEPALIVE/TIMEOUT",reason.code);assertFalse(reason.description.contains("private endpoint"))
        client.stop()
    }
    @Test fun keepAliveHttpRejectionPreservesStatusWithoutExposingTheResponseBody() = runBlocking {
        val original=server.dispatcher
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest)=if(request.path=="/session/keepalive")
                response("""{"code":"ServiceUnavailable","token":"private-body"}""",503) else original.dispatch(request)
        }
        val client=signaling();assertTrue(client.startAndConnectWebRtcSession("real-id",peer) {}.isSuccess)
        val error=try { client.keepAlive();null } catch(e: com.xcloud.tclnative.net.CloudHttpException) { e }
        assertNotNull(error)
        val reason=com.xcloud.tclnative.diagnostics.SessionFailure.from(error!!)
        assertEquals("KEEPALIVE/HTTP_503",reason.code);assertTrue(reason.description.contains("ServiceUnavailable"))
        assertFalse(reason.description.contains("private-body"));client.stop()
    }
    @Test fun backgroundKeepAliveRecoversHttp502WithoutDeletingOrRecreatingThePlayingSession() = runBlocking {
        val original=server.dispatcher
        val pulses=java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if(request.path!="/session/keepalive") return original.dispatch(request)
                requests.add(request)
                return when(pulses.incrementAndGet()) {
                    1 -> response("{}",502).setHeader("Retry-After","2")
                    2 -> response("{}",503)
                    else -> response("",204)
                }
            }
        }
        val confirmed=CompletableDeferred<Unit>();val waits=CopyOnWriteArrayList<Long>()
        val client=XCloudSignalingClient(
            XCloudSession("test-token",server.url("/").toString(),"hash",transferToken="console-token"),
            OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build(),
            { wait ->
                now+=wait;waits.add(wait)
                if(wait==30_000L && pulses.get()>=3) { confirmed.complete(Unit);awaitCancellation() }
            },{now})
        assertTrue(client.startAndConnectWebRtcSession("real-id",peer) {}.isSuccess)
        waits.clear()
        val maintenance=launch { client.maintainSession() }
        try {
            withTimeout(4000) { confirmed.await() }
            assertEquals(3,pulses.get())
            assertEquals(listOf(30_000L,2000L,2000L,30_000L),waits.toList())
            assertEquals(1,requests.count { it.path=="/v5/sessions/cloud/play" })
            assertEquals(1,requests.count { it.method=="POST" && it.path=="/session/sdp" })
            assertFalse(requests.any { it.method=="DELETE" })
            assertTrue(client.keepAliveDiagnostics().contains("recuperações 1"))
            assertTrue(client.keepAliveDiagnostics().contains("falhas 2"))
        } finally { maintenance.cancelAndJoin();client.stop() }
    }
    @Test fun queueLongerThanNinetySecondsCanStillProvisionAndConnect() = runBlocking {
        queuedStates = 70
        val progress = mutableListOf<String>()
        val client = signaling()
        assertTrue(client.startAndConnectWebRtcSession("real-id", peer) { progress.add(it) }.isSuccess)
        assertTrue(now > 90_000)
        assertTrue(progress.any { it.startsWith("QUEUE:") })
        assertEquals(1, requests.count { it.path == "/session/connect" })
        client.stop()
    }
    @Test fun queueDeadlineStopsAndDeletesTheAllocatedSession() = runBlocking {
        queuedStates = Int.MAX_VALUE
        val failure = signaling(queueTimeout = 4500).startAndConnectWebRtcSession("real-id", peer) {}.exceptionOrNull()!!
        assertTrue(failure.message!!.contains("QUEUE_TIMEOUT"))
        assertFalse(events.contains("offer"))
        assertEquals(1, requests.count { it.method == "DELETE" })
    }
    @Test fun readyToConnectWithoutTransferCredentialDoesNotSendSdpOrGssvAsUserToken() = runBlocking {
        val failure = signaling(transferToken = "").startAndConnectWebRtcSession("real-id", peer) {}.exceptionOrNull()!!
        assertTrue(failure.message!!.contains("QR Code"))
        assertFalse(events.contains("offer"))
        assertFalse(requests.any { it.path == "/session/connect" })
        assertEquals(1, requests.count { it.method == "DELETE" })
    }
    @Test fun rejectedConsoleAuthenticationNeverReachesSdpAndRedactsResponseBody() = runBlocking {
        rejectConnect = true
        val failure = signaling().startAndConnectWebRtcSession("real-id", peer) {}.exceptionOrNull()!!
        assertTrue(failure.message!!.contains("CONSOLE_CONNECT"))
        assertFalse(failure.message!!.contains("must-not-leak"))
        assertFalse(events.contains("offer"))
        assertEquals(1, requests.count { it.method == "DELETE" })
    }
    @Test fun terminalStateReportsServiceCodeAndCleansUpImmediately() = runBlocking {
        failedState = true
        val failure = signaling().startAndConnectWebRtcSession("real-id", peer) {}.exceptionOrNull()!!
        assertTrue(failure.message!!.contains("NoCapacity"))
        assertEquals(0L, now)
        assertFalse(events.contains("offer"))
        assertEquals(1, requests.count { it.method == "DELETE" })
    }

    @Test fun waitEstimateIsRefreshedAtThirtySecondIntervalsAndDoesNotFloodTheService() = runBlocking {
        queuedStates = 45
        val snapshots = mutableListOf<com.xcloud.tclnative.signaling.QueueStatus?>()
        val client = signaling()
        assertTrue(client.startAndConnectWebRtcSession("real-id", peer, onQueueStatus = { snapshots.add(it) }) {}.isSuccess)
        val requestsForEstimate = requests.filter { it.path == "/v1/waittime/real-id" }
        assertEquals(3, requestsForEstimate.size)
        assertTrue(snapshots.filterNotNull().any { it.estimate?.seconds == 300L })
        assertNull(snapshots.last())
        client.stop()
    }
    @Test fun failureOfOptionalEstimateDoesNotFailTheActualQueue() = runBlocking {
        queuedStates = 3; rejectEstimate = true
        val snapshots = mutableListOf<com.xcloud.tclnative.signaling.QueueStatus?>()
        val client = signaling()
        val result = client.startAndConnectWebRtcSession("real-id", peer, onQueueStatus = { snapshots.add(it) }) {}
        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        assertTrue(snapshots.filterNotNull().any { it.estimateFailed && it.estimate == null })
        assertTrue(events.contains("ready"))
        client.stop()
    }
    @Test fun missingEstimateDoesNotTurnIntoAFakeZeroMinuteWait() = runBlocking {
        queuedStates = 2; estimateBody = "{}"
        val snapshots = mutableListOf<com.xcloud.tclnative.signaling.QueueStatus?>()
        val client = signaling()
        assertTrue(client.startAndConnectWebRtcSession("real-id", peer, onQueueStatus = { snapshots.add(it) }) {}.isSuccess)
        assertTrue(snapshots.filterNotNull().all { it.estimate == null })
        client.stop()
    }

    @Test fun successfulForecastIsRetainedAndMarkedStaleWhenTheNextRefreshFails() = runBlocking {
        queuedStates = 45; failAfterFirstEstimate = true
        val snapshots = mutableListOf<com.xcloud.tclnative.signaling.QueueStatus?>()
        val client = signaling()
        assertTrue(client.startAndConnectWebRtcSession("real-id", peer, onQueueStatus = { snapshots.add(it) }) {}.isSuccess)
        assertTrue(snapshots.filterNotNull().any { it.estimate?.seconds == 300L && it.estimateFailed })
        client.stop()
    }
    @Test fun cancellingWhileTheForecastRequestIsStalledStillDeletesTheSession() = runBlocking {
        queuedStates = Int.MAX_VALUE; stallEstimate = true
        val snapshots = java.util.concurrent.CopyOnWriteArrayList<com.xcloud.tclnative.signaling.QueueStatus?>()
        val job = async { signaling().startAndConnectWebRtcSession("real-id", peer, onQueueStatus = { snapshots.add(it) }) {} }
        withTimeout(2000) { estimateEntered.await() }
        withTimeout(2000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertEquals(1, requests.count { it.method == "DELETE" })
        assertFalse(events.contains("offer"))
        assertNull(snapshots.last())
    }
}
