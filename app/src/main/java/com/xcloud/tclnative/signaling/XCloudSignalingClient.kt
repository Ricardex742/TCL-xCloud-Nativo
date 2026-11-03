package com.xcloud.tclnative.signaling

import com.xcloud.tclnative.auth.AuthenticationRequiredException
import com.xcloud.tclnative.auth.XCloudSession
import com.xcloud.tclnative.net.awaitResponse
import com.xcloud.tclnative.net.checkedBody
import com.xcloud.tclnative.webrtc.StreamingPeer
import com.xcloud.tclnative.webrtc.StreamResolution
import com.xcloud.tclnative.catalog.XboxProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class GameTitle(
    val titleId: String, val name: String, val productId: String? = null,
    val posterUrl: String? = null, val tileUrl: String? = null, val gamePass: Boolean = false,
    val userSubscriptions: List<String> = emptyList(), val heroUrl: String? = null,
    val seriesOptimized: Boolean = false, val xboxTitleId: String? = null,
    val programs: List<String> = emptyList(), val userPrograms: List<String> = emptyList(),
    val freeToPlay: Boolean = false
)

class XCloudSignalingClient(
    private val session: XCloudSession,
    private val httpClient: OkHttpClient = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build(),
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val queueTimeoutMs: Long = 60 * 60_000,
    private val transferTokenProvider: suspend () -> String = { session.transferToken }
) {
    private val activePath = AtomicReference<String?>(null)
    private val waitTimeClient = httpClient.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private var activeResolution = StreamResolution.HD_1080_HQ
    private fun deviceInfo() = JSONObject().put("appInfo", JSONObject().put("env", JSONObject()
        .put("clientAppId", "www.xbox.com").put("clientAppType", "browser").put("clientAppVersion", "26.1.0")))
        .put("dev", JSONObject().put("hw", JSONObject().put("make", "Microsoft").put("model", "Surface Pro"))
            .put("os", JSONObject().put("name", activeResolution.osName).put("ver", "10.0"))
            .put("displayInfo", JSONObject().put("dimensions", JSONObject().put("widthInPixels", activeResolution.width).put("heightInPixels", activeResolution.height))
                .put("pixelDensity", JSONObject().put("dpiX", 1).put("dpiY", 1)))).toString()

    private suspend fun request(path: String, stage: String, method: String = "GET", body: JSONObject? = null,
                                client: OkHttpClient = httpClient): String {
        val builder = Request.Builder().url(CloudProtocol.sessionUrl(session.baseUri, path))
            .header("Authorization", "Bearer ${session.gssvToken}")
            .header("Accept", "application/json").header("X-Gssv-Client", "XboxComBrowser")
            .header("X-MS-Device-Info", deviceInfo())
        when (method) {
            "POST" -> builder.post((body?.toString() ?: "").toRequestBody(jsonType))
            "DELETE" -> builder.delete()
        }
        return client.newCall(builder.build()).awaitResponse().use { it.checkedBody(stage) }
    }

    suspend fun fetchCatalogGames(): List<GameTitle> = withContext(Dispatchers.IO) {
        val results = JSONObject(request("/v2/titles", "CATALOG")).getJSONArray("results")
        (0 until results.length()).mapNotNull { index ->
            val game = results.getJSONObject(index)
            val id = game.optString("titleId").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val details = game.optJSONObject("details")
            GameTitle(id, details?.optString("productTitle")?.takeIf { it.isNotBlank() } ?: id,
                productId = details?.optString("productId")?.takeIf { it.isNotBlank() && it != "null" }
                    ?: game.optString("productId").takeIf { it.isNotBlank() && it != "null" },
                xboxTitleId = details?.optString("xboxTitleId")?.takeIf { it.isNotBlank() && it != "null" },
                userSubscriptions = XboxProfile.subscriptionClaims(details?.opt("userSubscriptions")),
                userPrograms = XboxProfile.subscriptionClaims(details?.opt("userPrograms")),
                programs = XboxProfile.subscriptionClaims(details?.opt("programs")),
                freeToPlay = details?.optBoolean("isFreeInStore",false) == true)
        }.distinctBy { it.titleId }.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
    }

    private suspend fun awaitProvisioned(path: String, titleId: String, onQueueStatus: (QueueStatus?) -> Unit,
                                         onProgress: (String) -> Unit) {
        val started = clockMs()
        var connectSentAt: Long? = null
        var queue: QueueStatus? = null
        var estimateAttemptAt: Long? = null
        try {
            while (true) {
                val state = JSONObject(request("$path/state", "SESSION_STATE"))
                val name = state.optString("state")
                val elapsed = (clockMs() - started) / 1000
                if (name != "WaitingForResources") onQueueStatus(null)
                when (name) {
                    "Provisioned" -> return
                    "ReadyToConnect" -> {
                        if (connectSentAt == null) {
                            onProgress("CONSOLE_CONNECT: Autenticando a sessão Xbox...")
                            val token = transferTokenProvider()
                            if (token.isBlank()) throw AuthenticationRequiredException(
                                "CONSOLE_CONNECT: login sem credencial de transferência. Entre novamente pelo QR Code.")
                            request("$path/connect", "CONSOLE_CONNECT", "POST", JSONObject().put("userToken", token))
                            connectSentAt = clockMs()
                        }
                        onProgress("PROVISIONING: Aguardando confirmação da sessão Xbox (${elapsed}s). Voltar cancela.")
                    }
                    "WaitingForResources" -> {
                        val confirmedAt = clockMs()
                        queue = (queue ?: QueueStatus(confirmedAt, confirmedAt, limitMs = queueTimeoutMs))
                            .copy(confirmedAtMs = confirmedAt)
                        onQueueStatus(queue)
                        onProgress("QUEUE: Aguardando vaga no Xbox Cloud (${QueueStatus.duration(elapsed)}). Voltar cancela.")
                        if (estimateAttemptAt == null || confirmedAt - estimateAttemptAt >= 30_000) {
                            estimateAttemptAt = confirmedAt
                            val url = CloudProtocol.sessionUrl(session.baseUri, "/v1/waittime/")
                                .newBuilder().addPathSegment(titleId).build().encodedPath
                            try {
                                val estimate = QueueEstimate.parse(request(url, "QUEUE_ESTIMATE", client = waitTimeClient))
                                queue = if (estimate != null) queue.copy(estimate = estimate, estimateAtMs = clockMs(), estimateFailed = false)
                                    else queue.copy(estimateFailed = true)
                            } catch (error: IOException) {
                                queue = queue.copy(estimateFailed = true)
                            } catch (error: org.json.JSONException) {
                                queue = queue.copy(estimateFailed = true)
                            }
                            onQueueStatus(queue)
                        }
                    }
                    "Provisioning", "New" -> onProgress("PROVISIONING: Preparando o jogo (${elapsed}s). Voltar cancela.")
                    "Failed", "Error", "Ended", "Cancelled", "Closed" -> {
                        val code = state.optJSONObject("errorDetails")?.optString("code")
                            ?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,80}")) }
                        throw IOException("SESSION_STATE: $name" + (code?.let { " — código $it" } ?: ""))
                    }
                    else -> throw IOException("SESSION_STATE: estado não reconhecido")
                }
                if (connectSentAt != null && clockMs() - connectSentAt >= 60_000) {
                    throw IOException("CONSOLE_CONNECT: o serviço não confirmou Provisioned após autenticar a sessão.")
                }
                if (clockMs() - started >= queueTimeoutMs) throw IOException(
                    "QUEUE_TIMEOUT: o serviço continua em $name após ${elapsed}s. Nenhum vídeo foi iniciado; tente novamente mais tarde.")
                pause(1500)
            }
        } finally {
            onQueueStatus(null)
        }
    }

    private suspend fun exchange(path: String, stage: String): Any {
        val started = clockMs()
        while (clockMs() - started < 30_000) {
            val value = CloudProtocol.exchange(request(path, stage))
            if (value != null) return value
            pause(500)
        }
        throw IOException("$stage: tempo limite aguardando resposta do Xbox")
    }

    suspend fun startAndConnectWebRtcSession(
        titleId: String, peer: StreamingPeer, onQueueStatus: (QueueStatus?) -> Unit = {},
        resolution: StreamResolution = StreamResolution.HD_1080_HQ, onProgress: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        stop()
        activeResolution = resolution
        try {
            val play = JSONObject().put("clientSessionId", java.util.UUID.randomUUID().toString())
                .put("titleId", titleId).put("systemUpdateGroup", "").put("serverId", "")
                .put("fallbackRegionNames", org.json.JSONArray()).put("settings", JSONObject()
                    .put("nanoVersion", "V3;WebrtcTransport.dll").put("enableOptionalDataCollection", false)
                    .put("enableTextToSpeech", false).put("highContrast", 0).put("locale", "pt-BR")
                    .put("useIceConnection", false).put("timezoneOffsetMinutes", -180).put("sdkType", "web").put("osName", activeResolution.osName))
            val response = JSONObject(request("/v5/sessions/cloud/play", "PLAY", "POST", play))
            val path = CloudProtocol.sessionUrl(session.baseUri, response.getString("sessionPath")).encodedPath.trimEnd('/')
            activePath.set(path)
            awaitProvisioned(path, titleId, onQueueStatus, onProgress)

            onProgress("SDP: Sessão provisionada. Negociando vídeo nativo...")
            val (offer, candidates) = peer.createOfferSdp()
            val configuration = JSONObject().put("chatConfiguration", JSONObject().put("bytesPerSample", 2)
                .put("expectedClipDurationMs", 20).put("format", JSONObject().put("codec", "opus").put("container", "webm"))
                .put("numChannels", 1).put("sampleFrequencyHz", 24000))
            listOf("chat" to 1, "control" to 3, "input" to 8, "message" to 1).forEach { (channel, version) ->
                configuration.put(channel, JSONObject().put("minVersion", 1).put("maxVersion", version))
            }
            request("$path/sdp", "SDP_OFFER", "POST", JSONObject().put("messageType", "offer")
                .put("requestId", "1").put("sdp", offer).put("configuration", configuration))
            val answer = (exchange("$path/sdp", "SDP_ANSWER") as? JSONObject)?.optString("sdp")
                ?.takeIf { it.isNotBlank() } ?: throw IOException("SDP_ANSWER: resposta sem SDP")
            peer.setRemoteAnswer(answer)
            onProgress("ICE: Negociando conectividade com o Xbox...")
            request("$path/ice", "ICE_LOCAL", "POST", CloudProtocol.icePayload(candidates))
            val remote = CloudProtocol.remoteCandidates(exchange("$path/ice", "ICE_REMOTE"))
            if (remote.isEmpty()) throw IOException("ICE_REMOTE: o serviço não retornou candidatos")
            peer.addRemoteCandidates(remote)
            onProgress("TRANSPORT: Aguardando ICE e canais de controle...")
            peer.awaitReady()
            Result.success("Transporte conectado. Aguardando o primeiro quadro de vídeo...")
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            stop()
            Result.failure(IOException("TRANSPORT: tempo limite na negociação WebRTC/canais de controle."))
        } catch (cancelled: CancellationException) {
            stop()
            throw cancelled
        } catch (error: Exception) {
            stop()
            Result.failure(error)
        }
    }

    suspend fun keepAlive() {
        activePath.get()?.let { request("$it/keepalive", "KEEPALIVE", "POST") }
    }

    suspend fun stop() {
        val path = activePath.getAndSet(null) ?: return
        withContext(NonCancellable + Dispatchers.IO) {
            withTimeoutOrNull(5000) {
                try { request(path, "STOP", "DELETE") } catch (_: IOException) { /* Server TTL is the fallback. */ }
            }
        }
    }
}
