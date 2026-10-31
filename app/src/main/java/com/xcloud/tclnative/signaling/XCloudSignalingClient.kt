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

    /**
     * Busca o Catálogo Oficial diretamente do servidor xCloud Brasil (/v2/titles)
     * evitando qualquer erro HTTP 400 de IDs inválidos!
     */
    suspend fun fetchCatalogGames(): List<GameTitle> = withContext(Dispatchers.IO) {
        val list = mutableListOf<GameTitle>()
        // Inclui primeiro os 2 títulos que já validaste com sucesso no teu vídeo!
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
                    val limit = minOf(results.length(), 40) // Carrega os 40 principais jogos do catálogo
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

    /**
     * Inicia a sessão na consola Xbox Series X E conecta o tubo de vídeo WebRTC (SDP + ICE)
     */
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
                    throw Exception("Servidor xCloud recusou início (HTTP ${resp.code})")
                }
                resp.body?.string() ?: throw Exception("Resposta vazia")
            }

            val sessionPath = JSONObject(respStr).getString("sessionPath")

            // 1. Aguarda o estado ReadyToConnect
            var state = "Provisioning"
            var attempts = 0
            while ((state == "Provisioning" || state == "WaitingForResources") && attempts < 25) {
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

            // 2. Gera o SDP Offer Nativo e envia para a Xbox Series X (/sdp)
            onProgress("ReadyToConnect! Negociando vídeo H.264 (SDP Offer)...")
            val (localOfferSdp, localCandidates) = webrtcClient.createOfferSdp()

            val sdpPayload = JSONObject().apply {
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

            val sdpPostReq = Request.Builder()
                .url("${session.baseUri}/$sessionPath/sdp")
                .addHeader("Authorization", "Bearer ${session.gssvToken}")
                .addHeader("Content-Type", "application/json")
                .post(sdpPayload.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(sdpPostReq).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("Falha no POST /sdp (HTTP ${resp.code})")
            }

            // 3. Aguarda o SDP Answer da consola Xbox Series X
            var remoteAnswerSdp = ""
            for (i in 0 until 15) {
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
                            val exch = root.optString("exchangeResponse", "")
                            if (exch.isNotEmpty()) {
                                val exchJson = JSONObject(exch)
                                remoteAnswerSdp = exchJson.optString("sdp", "")
                            }
                        }
                    }
                }
                if (remoteAnswerSdp.isNotEmpty()) break
            }

            if (remoteAnswerSdp.isEmpty()) {
                throw Exception("Timeout a aguardar SDP Answer da Xbox Series X")
            }

            // 4. Envia os candidatos ICE locais e recolhe os candidatos ICE do Azure (/ice)
            onProgress("SDP Recebido! Conectando túnel UDP de baixa latência (ICE)...")
            if (localCandidates.isNotEmpty()) {
                val firstCand = localCandidates.first()
                val icePostPayload = JSONObject().apply {
                    put("messageType", "iceCandidate")
                    put("candidate", firstCand.sdp)
                    put("sdpMLineIndex", firstCand.sdpMLineIndex)
                    put("sdpMid", firstCand.sdpMid)
                }
                val icePostReq = Request.Builder()
                    .url("${session.baseUri}/$sessionPath/ice")
                    .addHeader("Authorization", "Bearer ${session.gssvToken}")
                    .addHeader("Content-Type", "application/json")
                    .post(icePostPayload.toString().toRequestBody(jsonMediaType))
                    .build()
                httpClient.newCall(icePostReq).execute().close()
            }

            val remoteCandidates = mutableListOf<IceCandidate>()
            for (i in 0 until 10) {
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
                            val exch = root.optString("exchangeResponse", "")
                            if (exch.startsWith("[")) {
                                val arr = JSONArray(exch)
                                for (j in 0 until arr.length()) {
                                    val cObj = arr.getJSONObject(j)
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

            Result.success("STREAM DE VÍDEO CONECTADO! ($sessionPath)")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
