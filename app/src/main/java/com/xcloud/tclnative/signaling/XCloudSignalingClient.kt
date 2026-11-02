package com.xcloud.tclnative.signaling

import com.xcloud.tclnative.auth.XCloudSession
import com.xcloud.tclnative.webrtc.XCloudWebRtcClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

    companion object {
        @Volatile private var activeSessionPath: String? = null
    }

    /**
     * Busca todos os jogos reais disponíveis na assinatura xCloud Brasil (/v2/titles)
     */
    suspend fun fetchCatalogGames(): List<GameTitle> = withContext(Dispatchers.IO) {
        val list = mutableListOf<GameTitle>()
        val seenIds = mutableSetOf<String>()

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
                    for (i in 0 until results.length()) {
                        val item = results.getJSONObject(i)
                        val titleId = item.optString("titleId", "").trim()
                        if (titleId.isNotEmpty() && seenIds.add(titleId)) {
                            val detail = item.optJSONObject("details")
                            val productTitle = detail?.optString("productTitle")?.takeIf { it.isNotBlank() }
                                ?: titleId
                            val displayLabel = if (productTitle.equals(titleId, ignoreCase = true)) {
                                titleId
                            } else {
                                "$productTitle ($titleId)"
                            }
                            list.add(GameTitle(titleId, displayLabel))
                        }
                    }
                }
            }
        } catch (ignored: Exception) {}

        if (list.isEmpty()) {
            list.add(GameTitle("FORTNITE", "FORTNITE"))
            list.add(GameTitle("FORZAHORIZON5", "FORZAHORIZON5"))
        } else {
            list.sortBy { it.name.uppercase(Locale.ROOT) }
        }

        list
    }

    /**
     * Limpa qualquer sessão anterior presa na conta para evitar conflito SessionNotConnectable
     */
    private fun closePreviousSessionIfNeeded() {
        val prevPath = activeSessionPath ?: return
        activeSessionPath = null
        try {
            val delReq = Request.Builder()
                .url("${session.baseUri}/$prevPath")
                .addHeader("Authorization", "Bearer ${session.gssvToken}")
                .delete()
                .build()
            httpClient.newCall(delReq).execute().close()
        } catch (ignored: Exception) {}
    }

    suspend fun startAndConnectWebRtcSession(
        titleId: String,
        webrtcClient: XCloudWebRtcClient,
        onProgress: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            closePreviousSessionIfNeeded()

            // 1. Pré-aloca o SDP Offer em paralelo para que esteja pronto no milissegundo do ReadyToConnect!
            val prewarmedOfferDeferred = async {
                webrtcClient.createOfferSdp()
            }

            val deviceInfo = JSONObject().apply {
                put("appInfo", JSONObject().apply {
                    put("env", JSONObject().put("clientAppId", "www.xbox.com").put("clientAppType", "browser").put("clientAppVersion", "26.1.0"))
                })
                put("dev", JSONObject().apply {
                    put("hw", JSONObject().put("make", "Microsoft").put("model", "Surface Pro"))
                    put("os", JSONObject().put("name", "windows").put("ver", "10.0"))
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
                    put("locale", "pt-BR")
                    put("useIceConnection", false)
                    put("timezoneOffsetMinutes", -180)
                    put("sdkType", "web")
                    put("osName", "windows")
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
                    val err = resp.body?.string() ?: ""
                    throw Exception("PLAY HTTP ${resp.code}: $err")
                }
                resp.body?.string() ?: throw Exception("Resposta vazia no /play")
            }

            val sessionPath = JSONObject(respStr).getString("sessionPath")
            activeSessionPath = sessionPath

            // 2. Aguarda estritamente o estado ReadyToConnect (até 90s para suportar fila de provisionamento)
            var state = "Provisioning"
            var attempts = 0
            var stateErrorDetails = ""

            while ((state == "Provisioning" || state == "WaitingForResources") && attempts < 60) {
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
                        stateErrorDetails = stateJson.optJSONObject("errorDetails")?.toString() ?: ""
                        onProgress("Consola Azure [$titleId]: $state (${attempts}x)...")
                    }
                }
            }

            if (state != "ReadyToConnect") {
                throw Exception("Sessão não ficou pronta (Estado: $state | Detalhe: $stateErrorDetails)")
            }

            // 3. Dispara o SDP Offer imediatamente (em <20ms após ReadyToConnect)
            val (cleanOfferSdp, localCandidates) = prewarmedOfferDeferred.await()
            onProgress("ReadyToConnect! Enviando SDP Offer H.264 puro...")

            val sdpPayload = JSONObject().apply {
                put("messageType", "offer")
                put("sdp", cleanOfferSdp)
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

            val sdpPostReq = Request.Builder()
                .url("${session.baseUri}/$sessionPath/sdp")
                .addHeader("Authorization", "Bearer ${session.gssvToken}")
                .addHeader("Content-Type", "application/json")
                .post(sdpPayload.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(sdpPostReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string() ?: "Sem corpo"
                    val cv = resp.headers["MS-CV"] ?: ""
                    // Consulta o /state imediatamente para mostrar o motivo interno exato se o servidor derrubou a sessão
                    val postFailState = fetchCurrentStateSnapshot(sessionPath)
                    throw Exception("SDP_OFFER HTTP ${resp.code} (CV: $cv):\nResposta: $errBody\nEstado Pós-SDP: $postFailState")
                }
            }

            // 4. Aguarda o SDP Answer da consola Xbox Series X
            onProgress("SDP Aceito! Recebendo resposta de vídeo da Xbox Series X...")
            var remoteAnswerSdp = ""
            for (i in 0 until 25) {
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
                val snap = fetchCurrentStateSnapshot(sessionPath)
                throw Exception("Timeout aguardando SDP Answer da Xbox (Estado: $snap)")
            }

            // 5. Envia os candidatos ICE locais
            onProgress("SDP Answer Recebido! Conectando túnel UDP (ICE)...")
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

            httpClient.newCall(icePostReq).execute().close()

            // 6. Recolhe os candidatos ICE da consola Azure
            val remoteCandidates = mutableListOf<IceCandidate>()
            for (i in 0 until 15) {
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

            Result.success("STREAM CONECTADO! [$titleId] ($sessionPath)")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchCurrentStateSnapshot(sessionPath: String): String {
        return try {
            val req = Request.Builder()
                .url("${session.baseUri}/$sessionPath/state")
                .addHeader("Authorization", "Bearer ${session.gssvToken}")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                resp.body?.string() ?: "HTTP ${resp.code}"
            }
        } catch (e: Exception) {
            e.message ?: "erro"
        }
    }
}
