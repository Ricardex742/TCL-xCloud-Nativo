package com.xcloud.tclnative.signaling

import com.xcloud.tclnative.auth.XCloudSession
import com.xcloud.tclnative.net.CloudHttp
import com.xcloud.tclnative.webrtc.StreamingPeer
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
    private val httpClient: OkHttpClient = CloudHttp.client
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
        webrtcClient: StreamingPeer,
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
                            val queueText = if (waitSec > 0) "Fila estimada: ${waitSec}s" else "Aguardando vaga... (${elapsedSec}s)"
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
                                    return@use "Provisioned" // Sai do loop com segurança sem usar break inválido
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

            // 2. SDP Offer H.264
            onProgress("SDP_OFFER: Enviando oferta H.264...")
            val (localOfferSdp, localCandidates) = webrtcClient.createOfferSdp()
            val sdpOfferPayload = CloudProtocol.buildSdpOfferPayload(localOfferSdp)

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
                            onProgress("SDP_OFFER: Destravando sessão (Tentativa $sdpTry/6)...")
                            sendSessionConnect(baseUri, sessionPath)
                        } else {
                            throw lastSdpException!!
                        }
                        false
                    }
                }
                if (success) {
                    sdpPosted = true
                    break
                }
                delay(2000)
            }

            if (!sdpPosted) {
                throw lastSdpException ?: SignalingStageException("SDP_OFFER", message = "Falha ao enviar SDP Offer")
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
                    if (resp.isSuccessful && resp.code == 200 && body.isNotBlank()) {
                        remoteAnswerSdp = CloudProtocol.extractSdpAnswer(body)
                    }
                }
                if (!remoteAnswerSdp.isNullOrBlank()) break
            }

            val finalAnswerSdp = remoteAnswerSdp
                ?: throw SignalingStageException("SDP_ANSWER", message = "Timeout SDP Answer")

            // 4. ICE Candidates
            onProgress("ICE_POST: Enviando candidatos ICE...")
            val icePayload = CloudProtocol.buildIceCandidatesPayload(localCandidates)
            val icePostReq = Request.Builder()
                .url("$baseUri/$sessionPath/ice")
                .addHeader("Authorization", "Bearer ${session.gssvToken}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .post(icePayload.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(icePostReq).execute().close()

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
                    if (resp.isSuccessful && resp.code == 200 && body.isNotBlank()) {
                        remoteCandidates = CloudProtocol.extractIceCandidates(body)
                    }
                }
                if (remoteCandidates.isNotEmpty()) break
            }

            webrtcClient.applyRemoteAnswerAndCandidates(finalAnswerSdp, remoteCandidates)

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
