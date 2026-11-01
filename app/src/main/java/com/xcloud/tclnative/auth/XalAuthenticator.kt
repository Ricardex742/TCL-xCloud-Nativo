package com.xcloud.tclnative.auth

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class XCloudSession(
    val gssvToken: String,
    val baseUri: String,
    val userHash: String
)

data class DeviceCodeInfo(
    val userCode: String,
    val deviceCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val intervalSeconds: Int,
    val qrBitmap: Bitmap
)

class XalAuthenticator(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("xcloud_native_auth", Context.MODE_PRIVATE)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        // Client ID oficial para fluxo Device Code / QR Code Xbox Live
        private const val DEVICE_CLIENT_ID = "0000000048183522"
        private const val SCOPE = "service::user.auth.xboxlive.com::MBI_SSL"
    }

    fun saveTokens(rpsTicket: String, refreshToken: String = "") {
        prefs.edit()
            .putString("rps_ticket", rpsTicket)
            .putString("refresh_token", refreshToken)
            .apply()
    }

    fun getSavedRpsTicket(): String? = prefs.getString("rps_ticket", null)

    fun clearSession() {
        prefs.edit().clear().apply()
    }

    /**
     * 1. Solicita o Código de Pareamento e gera o QR Code nativo na hora
     */
    suspend fun requestDeviceCodeQr(): Result<DeviceCodeInfo> = withContext(Dispatchers.IO) {
        try {
            val formBody = FormBody.Builder()
                .add("client_id", DEVICE_CLIENT_ID)
                .add("scope", SCOPE)
                .add("response_type", "device_code")
                .build()

            val req = Request.Builder()
                .url("https://login.live.com/oauth20_connect.srf")
                .post(formBody)
                .build()

            val respStr = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("Erro ao gerar QR Code (HTTP ${resp.code})")
                resp.body?.string() ?: throw Exception("Resposta vazia")
            }

            val json = JSONObject(respStr)
            val userCode = json.getString("user_code")
            val deviceCode = json.getString("device_code")
            val verificationUri = json.optString("verification_uri", "https://login.live.com/oauth20_remoteconnect.srf")
            val completeUri = "https://login.live.com/oauth20_remoteconnect.srf?otc=$userCode"
            val interval = json.optInt("interval", 5)

            val qrBitmap = generateQrCodeBitmap(completeUri, 420)

            Result.success(
                DeviceCodeInfo(
                    userCode = userCode,
                    deviceCode = deviceCode,
                    verificationUri = verificationUri,
                    verificationUriComplete = completeUri,
                    intervalSeconds = interval,
                    qrBitmap = qrBitmap
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 2. Fica aguardando o usuário aprovar o QR Code no celular ou navegador
     */
    suspend fun pollForDeviceCodeLogin(deviceInfo: DeviceCodeInfo): Result<String> = withContext(Dispatchers.IO) {
        try {
            val maxAttempts = 90 // Até 7.5 minutos de espera
            for (attempt in 0 until maxAttempts) {
                delay(deviceInfo.intervalSeconds * 1000L)

                val formBody = FormBody.Builder()
                    .add("client_id", DEVICE_CLIENT_ID)
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .add("device_code", deviceInfo.deviceCode)
                    .build()

                val req = Request.Builder()
                    .url("https://login.live.com/oauth20_token.srf")
                    .post(formBody)
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string() ?: "{}"
                    val json = JSONObject(bodyStr)

                    if (resp.isSuccessful && json.has("access_token")) {
                        val accessToken = json.getString("access_token")
                        val refreshToken = json.optString("refresh_token", "")
                        saveTokens("t=$accessToken", refreshToken)
                        return@withContext Result.success("t=$accessToken")
                    } else {
                        val error = json.optString("error", "")
                        if (error != "authorization_pending" && error != "slow_down" && error.isNotEmpty()) {
                            throw Exception("Login cancelado ou expirado ($error)")
                        }
                    }
                }
            }
            throw Exception("Tempo limite do QR Code esgotado.")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun generateQrCodeBitmap(content: String, size: Int): Bitmap {
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    /**
     * 3. Converte o Token do QR Code na sessão de streaming xCloud Brasil
     */
    suspend fun authenticateToXCloud(rpsTicket: String): Result<XCloudSession> = withContext(Dispatchers.IO) {
        try {
            val formattedTicket = if (rpsTicket.startsWith("t=") || rpsTicket.startsWith("d=")) rpsTicket else "t=$rpsTicket"

            val userTokenJson = JSONObject().apply {
                put("RelyingParty", "http://auth.xboxlive.com")
                put("TokenType", "JWT")
                put("Properties", JSONObject().apply {
                    put("AuthMethod", "RPS")
                    put("SiteName", "user.auth.xboxlive.com")
                    put("RpsTicket", formattedTicket)
                })
            }

            val userReq = Request.Builder()
                .url("https://user.auth.xboxlive.com/user/authenticate")
                .addHeader("x-xbl-contract-version", "1")
                .post(userTokenJson.toString().toRequestBody(jsonMediaType))
                .build()

            val userRespStr = httpClient.newCall(userReq).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("Sessão expirada (HTTP ${resp.code}). Faça login via QR Code novamente.")
                resp.body?.string() ?: throw Exception("Resposta vazia no UserToken")
            }

            val userRespJson = JSONObject(userRespStr)
            val userToken = userRespJson.getString("Token")
            val uhs = userRespJson.getJSONObject("DisplayClaims")
                .getJSONArray("xui")
                .getJSONObject(0)
                .getString("uhs")

            val xstsJson = JSONObject().apply {
                put("RelyingParty", "http://gssv.xboxlive.com/")
                put("TokenType", "JWT")
                put("Properties", JSONObject().apply {
                    put("SandboxId", "RETAIL")
                    put("UserTokens", JSONArray().put(userToken))
                })
            }

            val xstsReq = Request.Builder()
                .url("https://xsts.auth.xboxlive.com/xsts/authorize")
                .addHeader("x-xbl-contract-version", "1")
                .post(xstsJson.toString().toRequestBody(jsonMediaType))
                .build()

            val xstsRespStr = httpClient.newCall(xstsReq).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("Falha no XSTS HTTP ${resp.code}")
                resp.body?.string() ?: throw Exception("Resposta vazia no XSTS")
            }

            val xstsToken = JSONObject(xstsRespStr).getString("Token")

            val xcloudLoginJson = JSONObject().apply {
                put("token", xstsToken)
                put("offeringId", "xgpuweb")
            }

            val xcloudReq = Request.Builder()
                .url("https://xgpuweb.gssv-play-prod.xboxlive.com/v2/login/user")
                .addHeader("Content-Type", "application/json")
                .addHeader("x-gssv-client", "XboxComBrowser")
                .post(xcloudLoginJson.toString().toRequestBody(jsonMediaType))
                .build()

            val xcloudRespStr = httpClient.newCall(xcloudReq).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("Erro Login xCloud HTTP ${resp.code}")
                resp.body?.string() ?: throw Exception("Resposta vazia xCloud")
            }

            val xcloudData = JSONObject(xcloudRespStr)
            val gssvToken = xcloudData.getString("gsToken")

            val regions = xcloudData.getJSONObject("offeringSettings").getJSONArray("regions")
            var baseUri = "https://bra.gssv-play-prod.xboxlive.com"
            for (i in 0 until regions.length()) {
                val region = regions.getJSONObject(i)
                if (region.optBoolean("isDefault", false)) {
                    baseUri = region.getString("baseUri")
                    break
                }
            }

            saveTokens(formattedTicket)
            Result.success(XCloudSession(gssvToken = gssvToken, baseUri = baseUri, userHash = uhs))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
