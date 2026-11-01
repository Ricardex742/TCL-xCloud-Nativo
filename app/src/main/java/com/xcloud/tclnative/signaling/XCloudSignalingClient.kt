package com.xcloud.tclnative.signaling

import com.xcloud.tclnative.auth.XCloudSession
import com.xcloud.tclnative.net.awaitResponse
import com.xcloud.tclnative.net.checkedBody
import com.xcloud.tclnative.webrtc.StreamingPeer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class GameTitle(val titleId: String, val name: String)

class XCloudSignalingClient(
    private val session: XCloudSession,
    private val httpClient: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build(),
    private val pause: suspend (Long) -> Unit = { delay(it) }
) {
    private val activeSession = AtomicReference<HttpUrl?>()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchCatalogGames(): List<GameTitle> = withContext(Dispatchers.IO) {
        val text = request(CloudProtocol.sessionUrl(session.baseUri, "v2/titles"), "CATALOG")
        val results = JSONObject(text).getJSONArray("results")
        (0 until results.length()).mapNotNull { i ->
            val item = results.getJSONObject(i)
            val id = item.optString("titleId")
            if (id.isBlank()) null else GameTitle(id, item.optJSONObject("details")
                ?.optString("productTitle")?.takeIf { it.isNotBlank() } ?: item.optString("productId", id))
        }.distinctBy { it.titleId }
    }

    suspend fun startAndConnectWebRtcSession(titleId: String, peer: StreamingPeer,
                                            onProgress: (String) -> Unit): Result<String> = withContext(Dispatchers.IO) {
        try {
            check(activeSession.get() == null) { "PLAY: já existe uma sessão ativa" }
            onProgress("PLAY: solicitando sessão do jogo...")
            val device = JSONObject().put("appInfo", JSONObject().put("env", JSONObject()
                .put("clientAppId", "www.xbox.com").put("clientAppType", "browser").put("clientAppVersion", "26.1.0")))
                .put("dev", JSONObject().put("hw", JSONObject().put("make", "Microsoft").put("model", "Surface Pro"))
                    .put("os", JSONObject().put("name", "windows").put("ver", "10.0"))
                    .put("displayInfo", JSONObject().put("dimensions", JSONObject().put("widthInPixels", 1920).put("heightInPixels", 1080))
                        .put("pixelDensity", JSONObject().put("dpiX", 1).put("dpiY", 1))))
            val payload = JSONObject().put("titleId", titleId).put("systemUpdateGroup", "")
                .put("clientSessionId", "").put("serverId", "").put("fallbackRegionNames", JSONArray())
                .put("settings", JSONObject().put("nanoVersion", "V3;WebrtcTransport.dll")
                    .put("enableTextToSpeech", false).put("highContrast", 0)
                    .put("locale", Locale.getDefault().toLanguageTag()).put("useIceConnection", false)
                    .put("timezoneOffsetMinutes", TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000)
                    .put("sdkType", "web").put("osName", "windows"))
            val play = request(CloudProtocol.sessionUrl(session.baseUri, "v5/sessions/cloud/play"), "PLAY", payload, device.toString())
            activeSession.set(CloudProtocol.sessionUrl(session.baseUri, JSONObject(play).getString("sessionPath")))
            withTimeout(5 * 60_000L) {
                while (true) {
                    val body = JSONObject(request(endpoint("state"), "SESSION_STATE"))
                    val state = body.getString("state")
                    onProgress("SESSION_STATE: $state")
                    if (state == "ReadyToConnect") break
                    if (state !in setOf("Provisioning", "WaitingForResources", "Queued", "Initializing"))
                        throw IOException("SESSION_STATE: $state — código ${body.optJSONObject("errorDetails")?.optString("code").orEmpty()}")
                    pause(1500)
                }
            }
            onProgress("SDP: preparando áudio e vídeo...")
            val (offer, candidates) = peer.createOfferSdp()
            val configuration = JSONObject().put("chatConfiguration", JSONObject()
                .put("bytesPerSample", 2).put("expectedClipDurationMs", 20)
                .put("format", JSONObject().put("codec", "opus").put("container", "webm"))
                .put("numChannels", 1).put("sampleFrequencyHz", 24000))
                .put("chat", versions(1)).put("control", versions(3)).put("input", versions(8)).put("message", versions(1))
            request(endpoint("sdp"), "SDP_OFFER", JSONObject().put("messageType", "offer").put("sdp", offer).put("configuration", configuration))
            val answer = withTimeout(30_000) {
                var answer = ""
                while (answer.isBlank()) {
                    val exchange = CloudProtocol.exchange(request(endpoint("sdp"), "SDP_ANSWER"))
                    answer = (exchange as? JSONObject)?.optString("sdp").orEmpty()
                    if (answer.isBlank()) pause(750)
                }
                answer
            }
            peer.setRemoteAnswer(answer)
            onProgress("ICE: trocando candidatos de rede...")
            request(endpoint("ice"), "ICE_LOCAL", CloudProtocol.icePayload(candidates))
            val remote = withTimeout(20_000) {
                var list = emptyList<org.webrtc.IceCandidate>()
                while (list.isEmpty()) {
                    list = CloudProtocol.remoteCandidates(CloudProtocol.exchange(request(endpoint("ice"), "ICE_REMOTE")))
                    if (list.isEmpty()) pause(750)
                }
                list
            }
            peer.addRemoteCandidates(remote)
            onProgress("TRANSPORT: aguardando ICE/DTLS e confirmação dos canais...")
            peer.awaitReady()
            Result.success("Transporte conectado; aguardando primeiro quadro do jogo...")
        } catch (e: CancellationException) {
            withContext(NonCancellable) { withTimeoutOrNull(5_000) { runCatching { stop() } } }
            if (e is TimeoutCancellationException) Result.failure(IOException("Sessão: tempo limite na etapa atual; tente novamente", e))
            else throw e
        } catch (e: Exception) {
            withContext(NonCancellable) { withTimeoutOrNull(5_000) { runCatching { stop() } } }
            Result.failure(e)
        }
    }

    suspend fun keepAlive() { request(endpoint("keepalive"), "KEEPALIVE", JSONObject()) }
    suspend fun stop() {
        val url = activeSession.getAndSet(null) ?: return
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${session.gssvToken}").delete().build()
        httpClient.newCall(req).awaitResponse().use { it.checkedBody("SESSION_STOP") }
    }
    private fun endpoint(suffix: String): HttpUrl = (activeSession.get()
        ?: throw IOException("SESSION: sessão encerrada")).newBuilder().addPathSegment(suffix).build()
    private fun versions(max: Int) = JSONObject().put("minVersion", 1).put("maxVersion", max)
    private suspend fun request(url: HttpUrl, stage: String, payload: JSONObject? = null, deviceInfo: String? = null): String {
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${session.gssvToken}")
        deviceInfo?.let { req.header("x-ms-device-info", it) }
        if (payload != null) req.post(payload.toString().toRequestBody(jsonType))
        return httpClient.newCall(req.build()).awaitResponse().use { it.checkedBody(stage) }
    }
}
