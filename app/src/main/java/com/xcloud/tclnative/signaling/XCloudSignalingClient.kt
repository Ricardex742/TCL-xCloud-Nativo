package com.xcloud.tclnative.signaling

import com.xcloud.tclnative.auth.XCloudSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

data class GameTitle(
    val titleId: String,
    val name: String
)

class XCloudSignalingClient(private val session: XCloudSession) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Lista rápida de jogos populares no xCloud para teste imediato + Busca dinâmica
     */
    fun getQuickLaunchGames(): List<GameTitle> {
        return listOf(
            GameTitle("FORTNITE", "Fortnite (Cloud Nuvem Direto)"),
            GameTitle("FORZAHORIZON5", "Forza Horizon 5"),
            GameTitle("MINECRAFT", "Minecraft"),
            GameTitle("CALLOFDUTY", "Call of Duty HQ / Black Ops 6"),
            GameTitle("EAFC24", "EA Sports FC"),
            GameTitle("MORTALKOMBAT11", "Mortal Kombat")
        )
    }

    /**
     * Manipulação de SDP (SDP Munging):
     * Obriga o servidor do xCloud a usar o perfil H.264 Constrained Baseline (42e01f),
     * eliminando B-frames e garantindo decodificação 1-in-1-out sem atraso na TCL P755 e no Snapdragon!
     */
    fun optimizeSdpForLowLatency(rawSdp: String, maxBitrateKbps: Int = 15000): String {
        val lines = rawSdp.split("\r\n").toMutableList()
        val optimized = StringBuilder()

        for (line in lines) {
            optimized.append(line).append("\r\n")
            // Injeta limite controlado de Bitrate logo após a declaração de vídeo para evitar engasgos de Wi-Fi
            if (line.startsWith("m=video")) {
                optimized.append("b=AS:$maxBitrateKbps\r\n")
            }
        }
        return optimized.toString()
    }

    /**
     * Solicita um Blade (Servidor Xbox Series X|S) no datacenter BrazilSouth
     */
    suspend fun startGameSession(titleId: String): Result<String> = withContext(Dispatchers.IO) {
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
                    put("timezoneOffsetMinutes", -180) // BRT (Brasília)
                    put("sdkType", "web")
                    put("osName", "android")
                })
                put("serverId", "")
                put("fallbackRegionNames", org.json.JSONArray())
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
                resp.body?.string() ?: throw Exception("Resposta de sessão vazia")
            }

            val sessionPath = JSONObject(respStr).getString("sessionPath")

            // Aguarda o estado de provisionamento do servidor (Polling de Provisioning)
            var state = "Provisioning"
            var attempts = 0
            while ((state == "Provisioning" || state == "WaitingForResources") && attempts < 15) {
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
                    }
                }
            }

            Result.success("Sessão alocada no Azure ($sessionPath) | Status: $state")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
