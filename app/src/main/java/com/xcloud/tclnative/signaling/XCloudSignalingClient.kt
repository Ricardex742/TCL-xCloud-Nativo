package com.xcloud.tclnative.signaling

import com.xcloud.tclnative.auth.XCloudSession
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
import java.util.concurrent.TimeUnit

data class GameTitle(
    val titleId: String,
    val name: String
)

class XCloudSignalingClient(private val session: XCloudSession) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchCatalogGames(): List<GameTitle> = withContext(Dispatchers.IO) {
        val list = mutableListOf<GameTitle>()
        list.add(GameTitle("FORTNITE", "Fortnite (Verificado ReadyToConnect)"))
        list.add(GameTitle("FORZAHORIZON5", "Forza Horizon 5 (Verificado ReadyToConnect)"))

        try {
            val req = Request.Builder()
                .url("${session.baseUri}/v2/titles")
                .addHeader("Authorization", "Bearer ${session.gssvToken}")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string() ?: "{}"
                    val results = JSONObject(bodyStr).optJSONArray("results") ?: JSONArray()
                    val limit = minOf(results.length(), 40)
                    for (i in 0 until limit) {
                        val item = results.getJSONObject(i)
                        val titleId = item.optString("titleId", "")
                        val detail = item.optJSONObject("details")
                        val productTitle = detail?.optString("productTitle")
                            ?: item.optString("productId", titleId)
                        if (titleId.isNotEmpty() && titleId != "FORTNITE" && titleId != "FORZAHORIZON5") {
                            list.add(GameTitle(titleId, "$productTitle [$titleId]"))
                        }
                    }
                }
            }
        } catch (ignored: Exception) {}

        list
    }

    suspend fun startAndConnectWebRtcSession(
        titleId: String,
        webrtcClient: XCloudWebRtcClient,
        onProgress: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val deviceInfo = JSONObject().apply {
                put("appInfo", JSONObject().apply {
                    put("env", JSONObject().put("clientAppId", "www.xbox.com").put("clientAppType", "browser").put("clientAppVersion", "26.1.0"))
                })
                put("dev", JSONObject().apply {
                    put("hw", JSONObject().put("make", "Microsoft").put("model", "Surface Pro"))
                    put("os", JSONObject().put("name", "android").put("ver", "12.0"))
                    put("displayInfo", JSONObject().apply {
                        put("dimensions", JSONObject().put("widthInPixels", 1920).put("heightInPixels", 1080))
                        put("pixelDensity", JSONObject().put("dpiX", 1).put("dpiY", 1))
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
                    put("osName", "android")
                })
                put("serverId", "")
                put("fallbackRegionNames", JSONArray())
            }

            val req = Request.Builder()
                .url("${session.baseUri}/v5/sessions/cloud/play")
                .addHeader("Authorization", "Bearer ${session.gssvToken}")
                .addHeader("Content-Type", "application/json")
                .addHeader("x-ms-device-info", deviceInfo.toString())
                .post(playPayload.toString().toRequestBody(jsonMediaType))
                .build()

            val respStr = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw Exception("Play recusado (HTTP ${resp.code})")
                }
                resp.body?.string() ?: throw Exception("Resposta vazia")
            }

            val sessionPath = JSONObject(respStr).getString("sessionPath")

            // 1. Aguarda ReadyToConnect
            var state = "Provisioning"
            var attempts = 0
            while ((state == "Provisioning" || state == "WaitingForResources") && attempts < 30) {
                delay(1500)
                attempts++
                val stateReq = Request.Builder()
                    .url("${session.baseUri}/$sessionPath/state")
                    .addHeader("Authorization", "Bearer ${session.gssvToken}")
                    .get()
                    .build()

                httpClient.newCall(stateReq).execute().use { stateResp ->
                    if (stateResp.isSuccessful) {
                        val stateJson = JSONObject(stateResp.body?.string() ?: "{}")
                        state = stateJson.optString("state", "Failed")
                        onProgress("Consola Azure ($state) [Tentativa $attempts]...")
                    }
                }
            }

            if (state != "ReadyToConnect") {
                throw Exception("Estado inesperado da consola: $state")
            }

            // 2. Gera o SDP Offer com a ordem corrigida (Audio -> Video -> Data)
            onProgress("ReadyToConnect! Enviando SDP Offer H.264...")
            val (localOfferSdp, localCandidates) = webrtcClient.createOfferSdp()

            // Payload 1: Configuração Padrão xCloud Web
            val sdpPayloadPrimary = JSONObject().apply {
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

            // Payload 2 (Fallback se o servidor exigir input maxVersion 3 sem chatConfiguration)
            val sdpPayloadFallback = JSONObject().apply {
                put("messageType", "offer")
                put("sdp", localOfferSdp)
                put("configuration", JSONObject().apply {
                    put("chat", JSONObject().put("minVersion", 1).put("maxVersion", 1))
                    put("control", JSONObject().put("minVersion", 1).put("maxVersion", 1))
                    put("input", JSONObject().put("minVersion", 1).put("maxVersion", 3))
                    put("message", JSONObject().put("minVersion", 1).put("maxVersion", 1))
                })
            }

            var sdpSentOk = false
            var lastSdpError = ""

            for (payload in listOf(sdpPayloadPrimary, sdpPayloadFallback)) {
                val sdpPostReq = Request.Builder()
                    .url("${session.baseUri}/$sessionPath/sdp")
                    .addHeader("Authorization", "Bearer ${session.gssvToken}")
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()

                httpClient.newCall(sdpPostReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        sdpSentOk = true
                    } else {
                        val errBody = resp.body?.string()?.take(120) ?: ""
                        lastSdpError = "HTTP ${resp.code} ($errBody)"
                    }
                }
                if (sdpSentOk) break
            }

            if (!sdpSentOk) {
                throw Exception("POST /sdp rejeitado: $lastSdpError")
            }

            // 3. Aguarda o SDP Answer da consola Xbox Series X
            onProgress("SDP Aceite! Aguardando resposta de vídeo da Xbox Series X...")
            var remoteAnswerSdp = ""
            for (i in 0 until 20) {
                delay(1000)
                val sdpGetReq = Request.Builder()
                    .url("${session.baseUri}/$sessionPath/sdp")
                    .addHeader("Authorization", "Bearer ${session.gssvToken}")
                    .get()
                    .build()

                httpClient.newCall(sdpGetReq).execute().use { resp ->
                    if (resp.isSuccessful && resp.code == 200) {
                        val body = resp.body?.string() ?: ""
                        if (body.isNotEmpty()) {
                            val root = JSONObject(body)
                            val exchObj = root.optJSONObject("exchangeResponse")
                            if (exchObj != null) {
                                remoteAnswerSdp = exchObj.optString("sdp", "")
                            } else {
                                val exchStr = root.optString("exchangeResponse", "")
                                if (exchStr.isNotEmpty()) {
                                    remoteAnswerSdp = JSONObject(exchStr).optString("sdp", "")
                                }
                            }
                        }
                    }
                }
                if (remoteAnswerSdp.isNotEmpty()) break
            }

            if (remoteAnswerSdp.isEmpty()) {
                throw Exception("Timeout a aguardar SDP Answer da Xbox")
            }

            // 4. Envia os candidatos ICE locais (Suporta formato array e individual)
            onProgress("SDP Answer Recebido! Abrindo túnel UDP (ICE)...")
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

            val iceExchangePayload = JSONObject().apply {
                put("messageType", "iceExchange")
                put("candidates", candsArray)
            }

            val icePostReq = Request.Builder()
                .url("${session.baseUri}/$sessionPath/ice")
                .addHeader("Authorization", "Bearer ${session.gssvToken}")
                .addHeader("Content-Type", "application/json")
                .post(iceExchangePayload.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(icePostReq).execute().use { resp ->
                if (!resp.isSuccessful && localCandidates.isNotEmpty()) {
                    // Fallback para envio unitário caso o endpoint use schema single candidate
                    val firstCand = localCandidates.first()
                    val singlePayload = JSONObject().apply {
                        put("messageType", "iceCandidate")
                        put("candidate", firstCand.sdp)
                        put("sdpMLineIndex", firstCand.sdpMLineIndex)
                        put("sdpMid", firstCand.sdpMid)
                    }
                    val retryReq = Request.Builder()
                        .url("${session.baseUri}/$sessionPath/ice")
                        .addHeader("Authorization", "Bearer ${session.gssvToken}")
                        .addHeader("Content-Type", "application/json")
                        .post(singlePayload.toString().toRequestBody(jsonMediaType))
                        .build()
                    httpClient.newCall(retryReq).execute().close()
                }
            }

            // 5. Recolhe os candidatos ICE da consola Azure
            val remoteCandidates = mutableListOf<IceCandidate>()
            for (i in 0 until 12) {
                delay(800)
                val iceGetReq = Request.Builder()
                    .url("${session.baseUri}/$sessionPath/ice")
                    .addHeader("Authorization", "Bearer ${session.gssvToken}")
                    .get()
                    .build()

                httpClient.newCall(iceGetReq).execute().use { resp ->
                    if (resp.isSuccessful && resp.code == 200) {
                        val body = resp.body?.string() ?: ""
                        if (body.isNotEmpty()) {
                            val root = JSONObject(body)
                            val exchArr = root.optJSONArray("exchangeResponse")
                                ?: root.optJSONObject("exchangeResponse")?.optJSONArray("candidates")
                                ?: run {
                                    val str = root.optString("exchangeResponse", "")
                                    if (str.trim().startsWith("[")) JSONArray(str)
                                    else if (str.trim().startsWith("{")) JSONObject(str).optJSONArray("candidates")
                                    else null
                                }

                            if (exchArr != null) {
                                for (j in 0 until exchArr.length()) {
                                    val cObj = exchArr.getJSONObject(j)
                                    val candStr = cObj.optString("candidate", "").trim()
                                    if (candStr.isNotEmpty() && !candStr.contains("end-of-candidates")) {
                                        val sdpMid = cObj.optString("sdpMid", "0")
                                        val mLine = cObj.optInt("sdpMLineIndex", 0)
                                        remoteCandidates.add(IceCandidate(sdpMid, mLine, candStr))
                                    }
                                }
                            }
                        }
                    }
                }
                if (remoteCandidates.isNotEmpty()) break
            }

            webrtcClient.applyRemoteAnswerAndCandidates(remoteAnswerSdp, remoteCandidates)

            Result.success("STREAM CONECTADO! ($sessionPath)")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
