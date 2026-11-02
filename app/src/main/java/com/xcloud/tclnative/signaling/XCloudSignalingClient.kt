package com.xcloud.tclnative.signaling

import com.xcloud.tclnative.auth.XCloudSession
import com.xcloud.tclnative.net.CloudHttp
import com.xcloud.tclnative.webrtc.XCloudWebRtcClient
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

class XCloudSignalingClient(
    private val session: XCloudSession,
    private val httpClient: OkHttpClient = CloudHttp.client
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchCatalogGames(): Result<List<GameTitle>> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${session.baseUri.trimEnd('/')}/v2/titles")
                .addHeader("Authorization", "Bearer ${session.gssvToken}")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            val list = httpClient.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw buildSignalingException("CATALOG", resp.code, bodyStr, resp.headers["MS-CV"])
                }

                val results = JSONObject(bodyStr).optJSONArray("results") ?: JSONArray()
                val parsed = mutableListOf<GameTitle>()
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
                parsed.sortedBy { it.name.uppercase(Locale.ROOT) }
            }

            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun startAndConnectWebRtcSession(
        titleId: String,
        webrtcClient: XCloudWebRtcClient,
        onProgress: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
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

            // 1. Máquina de Estados Oficial xCloud:
            // WaitingForResources -> ReadyToConnect -> [POST /connect] -> Provisioning -> Provisioned
            var state = "Provisioning"
            var connectCalled = false
            var readyToConnectTicks = 0
            var attempts = 0
            val maxAttempts = 240 // Até 8 minutos de suporte para fila de espera no Fortnite

            while (state != "Provisioned" && attempts < maxAttempts) {
                delay(1500)
                attempts++

                val stateReq = Request.Builder()
                    .url("$baseUri/$sessionPath/state")
                    .addHeader("Authorization", "Bearer ${session.gssvToken}")
                    .addHeader("Accept", "application/json")
                    .get()
                    .build()

                httpClient.newCall(stateReq).execute().use { stateResp ->
                    val body = stateResp.body?.string().orEmpty()
                    if (!stateResp.isSuccessful) {
                        throw buildSignalingException("SESSION_STATE", stateResp.code, body, stateResp.headers["MS-CV"])
                    }
                    val stateJson = JSONObject(body)
                    state = stateJson.optString("state", "Failed")
                    val waitSec = stateJson.optInt("queueWaitTimeSeconds", -1)

                    when (state) {
                        "WaitingForResources" -> {
                            val elapsedSec = (attempts * 1500) / 1000
                            val queueText = if (waitSec > 0) {
                                "Fila estimada: ${waitSec}s (Decorrido: ${elapsedSec}s)"
                            } else {
                                "Na fila do servidor Brasil... (${elapsedSec}s)"
                            }
                            onProgress("SESSION_STATE: WaitingForResources — $queueText")
                        }
                        "ReadyToConnect" -> {
                            readyToConnectTicks++
                            if (!connectCalled) {
                                connectCalled = true
                                onProgress("SESSION_STATE: ReadyToConnect -> Enviando POST /connect...")
                                sendSessionConnect(baseUri, sessionPath)
                            } else {
                                onProgress("SESSION_STATE: ReadyToConnect -> Aguardando Provisioned ($readyToConnectTicks)...")
                                // Se o servidor permanecer em ReadyToConnect após 3 ciclos (ou em Mock de teste),
                                // avançamos para tentar o SDP com Auto-Retry!
                                if (readyToConnectTicks >= 3) {
                                    break
                                }
                            }
                        }
                        "Provisioning" -> {
                            onProgress("SESSION_STATE: Provisioning (Preparando Xbox Series X...)")
                        }
                        "Provisioned" -> {
                            onProgress("SESSION_STATE: Provisioned! Consola pronta.")
                        }
                        "Failed", "Cancelled" -> {
                            val errDetails = stateJson.optJSONObject("errorDetails")?.optString("message")
                                ?: body
                            throw buildSignalingException("SESSION_STATE", 400, errDetails, stateResp.headers["MS-CV"])
                        }
                        else -> {
                            onProgress("SESSION_STATE: $state")
                        }
                    }
                }
            }

            if (state != "Provisioned" && state != "ReadyToConnect") {
                throw SignalingStageException(
                    stage = "SESSION_STATE",
                    message = "SESSION_STATE: Timeout ao aguardar consola (Último estado: $state)"
                )
            }

            // 2. Gera o SDP Offer H.264 e envia para /{sessionPath}/sdp (Com Auto-Retry se SessionNotConnectable)
            onProgress("SDP_OFFER: Gerando e enviando oferta de vídeo H.264...")
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

                httpClient.newCall(sdpPostReq).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        sdpPosted = true
                    } else {
                        lastSdpException = buildSignalingException("SDP_OFFER", resp.code, body, resp.headers["MS-CV"])
                        // Se o servidor disser SessionNotConnectable, dispara /connect novamente e aguarda a transição!
                        if (body.contains("SessionNotConnectable", ignoreCase = true)) {
                            onProgress("SDP_OFFER: Aguardando destravamento da consola (Tentativa $sdpTry/6)...")
                            sendSessionConnect(baseUri, sessionPath)
                        }
                    }
                }
                if (sdpPosted) break
                delay(2000)
            }

            if (!sdpPosted) {
                throw lastSdpException ?: SignalingStageException("SDP_OFFER", message = "Falha ao enviar SDP Offer")
            }

            // 3. Polling do SDP Answer
            onProgress("SDP_ANSWER: Aguardando resposta de vídeo da Xbox Series X...")
            var remoteAnswerSdp: String? = null
            for (i in 0 until 25) {
                delay(1000)
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
                        remoteAnswerSdp = CloudProtocol.extractSdpAnswer(body)
                    }
                }
                if (!remoteAnswerSdp.isNullOrBlank()) break
            }

            val finalAnswerSdp = remoteAnswerSdp
                ?: throw SignalingStageException("SDP_ANSWER", message = "SDP_ANSWER: Timeout a aguardar resposta SDP")

            // 4. Envia os candidatos ICE locais (/ice)
            onProgress("ICE_POST: Enviando candidatos de rede UDP...")
            val icePayload = CloudProtocol.buildIceCandidatesPayload(localCandidates)
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

            // 5. Recolhe os candidatos ICE remotos da Xbox Series X
            onProgress("ICE_GET: Sincronizando rota de vídeo com o datacenter...")
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
                        remoteCandidates = CloudProtocol.extractIceCandidates(body)
                    }
                }
                if (remoteCandidates.isNotEmpty()) break
            }

            webrtcClient.applyRemoteAnswerAndCandidates(finalAnswerSdp, remoteCandidates)
            Result.success("STREAM CONECTADO! ($sessionPath)")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Envia o POST /{sessionPath}/connect obrigatório para a consola sair de ReadyToConnect
     * e entrar em Provisioned (evitando o erro HTTP 400 SessionNotConnectable no /sdp).
     */
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
