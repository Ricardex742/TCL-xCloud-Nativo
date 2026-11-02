package com.xcloud.tclnative.signaling

import com.xcloud.tclnative.auth.XCloudSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.IceCandidate
import java.util.Locale
import java.util.concurrent.TimeUnit

data class GameTitle(
    val titleId: String,
    val name: String
)

class SignalingStageException(
    val stage: String,
    val statusCode: Int? = null,
    val errorCode: String? = null,
    val correlationId: String? = null,
    override val message: String
) : Exception(message)

class ActiveStreamSession(
    val sessionPath: String,
    private val baseUri: String,
    private val gssvToken: String,
    private val httpClient: OkHttpClient
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun keepAlive(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("${baseUri.trimEnd('/')}/${sessionPath.trimStart('/')}/keepalive")
                .addHeader("Authorization", "Bearer $gssvToken")
                .addHeader("Content-Type", "application/json")
                .post("{}".toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("${baseUri.trimEnd('/')}/${sessionPath.trimStart('/')}")
                .addHeader("Authorization", "Bearer $gssvToken")
                .delete()
                .build()
            httpClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    override fun toString(): String = "STREAM ATIVO ($sessionPath)"
}

class XCloudSignalingClient(
    private val session: XCloudSession,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchCatalogGames(): List<GameTitle> = withContext(Dispatchers.IO) {
        val parsed = mutableListOf<GameTitle>()
        try {
            val req = Request.Builder()
                .url("${session.baseUri.trimEnd('/')}/v2/titles")
                .addHeader("Authorization", "Bearer ${session.gssvToken}")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string().orEmpty()
                    val results = JSONObject(bodyStr).optJSONArray("results") ?: JSONArray()
                    for (i in 0 until results.length()) {
                        val item = results.getJSONObject(i)
                        val titleId = item.optString("titleId", "").trim()
                        if (titleId.isNotEmpty()) {
                            val details = item.optJSONObject("details")
                            val displayName = details?.optString("productTitle")?.takeIf { it.isNotBlank() }
                                ?: titleId
                            parsed.add(GameTitle(titleId = titleId, name = displayName))
                        }
                    }
                }
            }
        } catch (ignored: Exception) {
        }

        if (parsed.isEmpty()) {
            parsed.add(GameTitle("FORTNITE", "FORTNITE"))
            parsed.add(GameTitle("FORZAHORIZON5", "FORZAHORIZON5"))
        }

        parsed.sortedBy { it.name.uppercase(Locale.ROOT) }
    }

    suspend fun startAndConnectWebRtcSession(
        titleId: String,
        webrtcClient: Any,
        onProgress: (String) -> Unit = {}
    ): Result<ActiveStreamSession> = withContext(Dispatchers.IO) {
        try {
            val baseUri = session.baseUri.trimEnd('/')

            val deviceInfo = JSONObject().apply {
                put("appInfo", JSONObject().apply {
                    put("env", JSONObject().apply {
                        put("clientAppId", "www.xbox.com")
                        put("clientAppType", "browser")
                        put("clientAppVersion", "26.1.0")
                    })
                })
                put("dev", JSONObject().apply {
                    put("hw", JSONObject().apply {
                        put("make", "Microsoft")
                        put("model", "Surface Pro")
                        put("sdktype", "web")
                    })
                    put("os", JSONObject().apply {
                        put("name", "windows")
                        put("ver", "22631.2715")
                        put("platform", "desktop")
                    })
                    put("displayInfo", JSONObject().apply {
                        put("dimensions", JSONObject().apply {
                            put("widthInPixels", 1920)
                            put("heightInPixels", 1080)
                        })
                        put("pixelDensity", JSONObject().apply {
                            put("dpiX", 1)
                            put("dpiY", 1)
                        })
                    })
                    put("browser", JSONObject().apply {
                        put("browserName", "chrome")
                        put("browserVersion", "130.0")
                    })
                })
            }

            val playPayload = JSONObject().apply {
                put("titleId", titleId)
                put("systemUpdateGroup", "")
                put("settings", JSONObject().apply {
                    put("nanoVersion", "V3;WebrtcTransport.dll")
                    put("enableTextToSpeech", false)
                    put("highContrast", 0)
                    put("locale", Locale.getDefault().toLanguageTag().ifEmpty { "pt-BR" })
                    put("useIceConnection", false)
                    put("timezoneOffsetMinutes", -180)
                    put("sdkType", "web")
                    put("osName", "windows")
                })
                put("serverId", "")
                put("fallbackRegionNames", JSONArray())
            }

            onProgress("PLAY_SESSION: Alocando consola para $titleId...")
            val playReq = Request.Builder()
                .url("$baseUri/v5/sessions/cloud/play")
                .addHeader("Authorization", "Bearer ${session.gssvToken}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("x-ms-device-info", deviceInfo.toString())
                .post(playPayload.toString().toRequestBody(jsonMediaType))
                .build()

            val sessionPath = httpClient.newCall(playReq).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw buildSignalingException("PLAY_SESSION", resp.code, body, resp.headers["MS-CV"])
                }
                JSONObject(body).getString("sessionPath").trimStart('/')
            }

            // 1. Ciclo da Máquina de Estados: WaitingForResources -> ReadyToConnect -> [POST /connect] -> Provisioned
            var state = "Provisioning"
            var connectCalled = false
            var readyToConnectTicks = 0
            var attempts = 0
            val maxAttempts = 240

            while (state != "Provisioned" && attempts < maxAttempts) {
                delay(1500)
                attempts++

                val stateReq = Request.Builder()
                    .url("$baseUri/$sessionPath/state")
                    .addHeader("Authorization", "Bearer ${session.gssvToken}")
                    .addHeader("Accept", "application/json")
                    .get()
                    .build()

                val currentState = httpClient.newCall(stateReq).execute().use { stateResp ->
                    val body = stateResp.body?.string().orEmpty()
                    if (!stateResp.isSuccessful) {
                        throw buildSignalingException("SESSION_STATE", stateResp.code, body, stateResp.headers["MS-CV"])
                    }
                    val stateJson = JSONObject(body)
                    val st = stateJson.optString("state", "Failed")
                    val waitSec = stateJson.optInt("queueWaitTimeSeconds", -1)

                    when (st) {
                        "WaitingForResources" -> {
                            val elapsedSec = (attempts * 1500) / 1000
                            val queueText = if (waitSec > 0) "Fila: ${waitSec}s" else "Aguardando vaga... (${elapsedSec}s)"
                            onProgress("SESSION_STATE: WaitingForResources — $queueText")
                        }
                        "ReadyToConnect" -> {
                            readyToConnectTicks++
                            if (!connectCalled) {
                                connectCalled = true
                                onProgress("SESSION_STATE: ReadyToConnect -> POST /connect...")
                                sendSessionConnect(baseUri, sessionPath)
                            } else {
                                onProgress("SESSION_STATE: ReadyToConnect -> Aguardando Provisioned...")
                                if (readyToConnectTicks >= 2) {
                                    return@use "Provisioned"
                                }
                            }
                        }
                        "Provisioning" -> onProgress("SESSION_STATE: Provisioning...")
                        "Provisioned" -> onProgress("SESSION_STATE: Provisioned!")
                        "Failed", "Cancelled" -> throw buildSignalingException("SESSION_STATE", 400, body, stateResp.headers["MS-CV"])
                    }
                    st
                }
                if (currentState == "Provisioned") {
                    state = "Provisioned"
                }
            }

            // 2. Invoca createOfferSdp() no cliente WebRTC
            onProgress("SDP_OFFER: Enviando oferta H.264...")
            val (localOfferSdp, localCandidates) = invokeCreateOffer(webrtcClient)
            val sdpOfferPayload = buildSdpOfferJson(localOfferSdp)

            var sdpPosted = false
            var lastSdpException: SignalingStageException? = null

            for (sdpTry in 1..6) {
                val sdpPostReq = Request.Builder()
                    .url("$baseUri/$sessionPath/sdp")
                    .addHeader("Authorization", "Bearer ${session.gssvToken}")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(sdpOfferPayload.toString().toRequestBody(jsonMediaType))
                    .build()

                val success = httpClient.newCall(sdpPostReq).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        true
                    } else {
                        lastSdpException = buildSignalingException("SDP_OFFER", resp.code, body, resp.headers["MS-CV"])
                        if (body.contains("SessionNotConnectable", ignoreCase = true)) {
                            onProgress("SDP_OFFER: Destravando consola (Tentativa $sdpTry/6)...")
                            sendSessionConnect(baseUri, sessionPath)
                            false
                        } else {
                            throw lastSdpException!!
                        }
                    }
                }
                if (success) {
                    sdpPosted = true
                    break
                }
                delay(2000)
            }

            if (!sdpPosted) {
                throw lastSdpException ?: SignalingStageException("SDP_OFFER", message = "Falha no SDP Offer")
            }

            // 3. SDP Answer
            onProgress("SDP_ANSWER: Aguardando resposta SDP...")
            var remoteAnswerSdp: String? = null
            for (i in 0 until 25) {
                delay(800)
                val sdpGetReq = Request.Builder()
                    .url("$baseUri/$sessionPath/sdp")
                    .addHeader("Authorization", "Bearer ${session.gssvToken}")
                    .addHeader("Accept", "application/json")
                    .get()
                    .build()

                httpClient.newCall(sdpGetReq).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful && resp.code != 204) {
                        throw buildSignalingException("SDP_ANSWER", resp.code, body, resp.headers["MS-CV"])
                    }
                    if (resp.code == 200 && body.isNotBlank()) {
                        remoteAnswerSdp = parseSdpAnswerJson(body)
                    }
                }
                if (!remoteAnswerSdp.isNullOrBlank()) break
            }

            val finalAnswerSdp = remoteAnswerSdp
                ?: throw SignalingStageException("SDP_ANSWER", message = "Timeout SDP Answer")

            // 4. ICE Candidates
            onProgress("ICE_POST: Enviando candidatos ICE...")
            val icePayload = buildIceCandidatesJson(localCandidates)
            val icePostReq = Request.Builder()
                .url("$baseUri/$sessionPath/ice")
                .addHeader("Authorization", "Bearer ${session.gssvToken}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .post(icePayload.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(icePostReq).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw buildSignalingException("ICE_POST", resp.code, body, resp.headers["MS-CV"])
                }
            }

            onProgress("ICE_GET: Sincronizando vídeo...")
            var remoteCandidates = emptyList<IceCandidate>()
            for (i in 0 until 20) {
                delay(800)
                val iceGetReq = Request.Builder()
                    .url("$baseUri/$sessionPath/ice")
                    .addHeader("Authorization", "Bearer ${session.gssvToken}")
                    .addHeader("Accept", "application/json")
                    .get()
                    .build()

                httpClient.newCall(iceGetReq).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful && resp.code != 204) {
                        throw buildSignalingException("ICE_GET", resp.code, body, resp.headers["MS-CV"])
                    }
                    if (resp.code == 200 && body.isNotBlank()) {
                        remoteCandidates = parseIceCandidatesJson(body)
                    }
                }
                if (remoteCandidates.isNotEmpty()) break
            }

            invokeApplyRemote(webrtcClient, finalAnswerSdp, remoteCandidates)

            Result.success(
                ActiveStreamSession(
                    sessionPath = sessionPath,
                    baseUri = baseUri,
                    gssvToken = session.gssvToken,
                    httpClient = httpClient
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun invokeCreateOffer(peer: Any): Pair<String, List<IceCandidate>> {
        if (peer is com.xcloud.tclnative.webrtc.XCloudWebRtcClient) {
            return peer.createOfferSdp()
        }
        val method = peer.javaClass.methods.firstOrNull { it.name.contains("createOffer") }
            ?: throw IllegalStateException("Método createOfferSdp não encontrado em ${peer.javaClass.name}")
        val res = if (method.parameterCount == 1) {
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { cont ->
                method.invoke(peer, cont)
            }
        } else {
            method.invoke(peer)
        }
        return res as Pair<String, List<IceCandidate>>
    }

    private suspend fun invokeApplyRemote(peer: Any, answerSdp: String, candidates: List<IceCandidate>) {
        if (peer is com.xcloud.tclnative.webrtc.XCloudWebRtcClient) {
            peer.applyRemoteAnswerAndCandidates(answerSdp, candidates)
            return
        }
        val method = peer.javaClass.methods.firstOrNull {
            it.name.contains("applyRemote") || it.name.contains("setRemote")
        } ?: return
        if (method.parameterCount == 3) {
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { cont ->
                method.invoke(peer, answerSdp, candidates, cont)
            }
        } else if (method.parameterCount == 2) {
            method.invoke(peer, answerSdp, candidates)
        }
    }

    private fun buildSdpOfferJson(localOfferSdp: String): JSONObject {
        return JSONObject().apply {
            put("messageType", "offer")
            put("sdp", localOfferSdp)
            put("configuration", JSONObject().apply {
                put("chatConfiguration", JSONObject().apply {
                    put("bytesPerSample", 2)
                    put("expectedClipDurationMs", 20)
                    put("format", JSONObject().put("codec", "opus").put("container", "webm"))
                    put("numChannels", 1)
                    put("sampleFrequencyHz", 24000)
                })
                put("chat", JSONObject().put("minVersion", 1).put("maxVersion", 1))
                put("control", JSONObject().put("minVersion", 1).put("maxVersion", 3))
                put("input", JSONObject().put("minVersion", 1).put("maxVersion", 8))
                put("message", JSONObject().put("minVersion", 1).put("maxVersion", 1))
            })
        }
    }

    private fun parseSdpAnswerJson(body: String): String? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        root.optJSONObject("exchangeResponse")?.optString("sdp")?.takeIf { it.isNotBlank() }?.let { return it }
        val exchStr = root.optString("exchangeResponse", "")
        if (exchStr.isNotBlank()) {
            runCatching { JSONObject(exchStr).optString("sdp") }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return root.optString("sdp").takeIf { it.isNotBlank() }
    }

    private fun buildIceCandidatesJson(localCandidates: List<IceCandidate>): JSONObject {
        val candsArray = JSONArray()
        for (c in localCandidates) {
            candsArray.put(JSONObject().apply {
                put("candidate", c.sdp)
                put("sdpMLineIndex", c.sdpMLineIndex)
                put("sdpMid", c.sdpMid)
            })
        }
        candsArray.put(JSONObject().apply {
            put("candidate", "a=end-of-candidates")
            put("sdpMLineIndex", 0)
            put("sdpMid", "0")
        })
        return JSONObject().apply {
            put("messageType", "iceExchange")
            put("candidates", candsArray)
        }
    }

    private fun parseIceCandidatesJson(body: String): List<IceCandidate> {
        val out = mutableListOf<IceCandidate>()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return out
        val arr = root.optJSONArray("exchangeResponse")
            ?: root.optJSONArray("candidates")
            ?: root.optJSONObject("exchangeResponse")?.optJSONArray("candidates")
            ?: run {
                val str = root.optString("exchangeResponse", "").trim()
                when {
                    str.startsWith("[") -> runCatching { JSONArray(str) }.getOrNull()
                    str.startsWith("{") -> runCatching { JSONObject(str).optJSONArray("candidates") }.getOrNull()
                    else -> null
                }
            }

        if (arr != null) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val candStr = obj.optString("candidate", "").trim()
                if (candStr.isNotEmpty() && !candStr.contains("end-of-candidates")) {
                    out.add(
                        IceCandidate(
                            obj.optString("sdpMid", "0"),
                            obj.optInt("sdpMLineIndex", 0),
                            candStr
                        )
                    )
                }
            }
        }
        return out
    }

    private fun sendSessionConnect(baseUri: String, sessionPath: String) {
        val tokenToSend = session.transferToken.takeIf { it.isNotBlank() } ?: session.gssvToken
        val connectPayload = JSONObject().apply {
            put("userToken", tokenToSend)
        }

        val connectReq = Request.Builder()
            .url("$baseUri/$sessionPath/connect")
            .addHeader("Authorization", "Bearer ${session.gssvToken}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(connectPayload.toString().toRequestBody(jsonMediaType))
            .build()

        runCatching {
            httpClient.newCall(connectReq).execute().close()
        }
    }

    private fun buildSignalingException(
        stage: String,
        statusCode: Int,
        body: String,
        correlationIdHeader: String?
    ): SignalingStageException {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val errorCode = json?.optString("code")?.takeIf { it.isNotEmpty() }
            ?: json?.optString("error")?.takeIf { it.isNotEmpty() }
            ?: json?.optJSONObject("errorDetails")?.optString("code")?.takeIf { it.isNotEmpty() }
        val messageDetail = json?.optString("message")?.takeIf { it.isNotEmpty() }
            ?: json?.optJSONObject("errorDetails")?.optString("message")?.takeIf { it.isNotEmpty() }
        val corrId = correlationIdHeader
            ?: json?.optString("correlationId")?.takeIf { it.isNotEmpty() }
            ?: json?.optString("cv")?.takeIf { it.isNotEmpty() }

        val msg = buildString {
            append("$stage: HTTP $statusCode")
            if (!errorCode.isNullOrBlank()) append(" — código $errorCode")
            if (!messageDetail.isNullOrBlank()) append(" ($messageDetail)")
            if (!corrId.isNullOrBlank()) append(" (requisição $corrId)")
        }
        return SignalingStageException(stage, statusCode, errorCode, corrId, msg)
    }
}
