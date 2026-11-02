package com.xcloud.tclnative.auth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.xcloud.tclnative.net.CloudHttp
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

data class XCloudSession(
    val gssvToken: String,
    val baseUri: String,
    val userHash: String,
    val transferToken: String = ""
)

data class DeviceCodeInfo(
    val userCode: String,
    val deviceCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val intervalSeconds: Int,
    val expiresInSeconds: Int = 600,
    val qrBitmap: Bitmap
)

class AuthStageException(
    val stage: String,
    val statusCode: Int? = null,
    val errorCode: String? = null,
    val correlationId: String? = null,
    override val message: String
) : Exception(message)

class XalAuthenticator(
    context: Context? = null,
    private val httpClient: OkHttpClient = CloudHttp.client,
    private val tokenStore: OAuthTokenStore = if (context != null) {
        SharedPrefsOAuthTokenStore(context)
    } else {
        InMemoryOAuthTokenStore()
    }
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        const val BROWSER_CLIENT_ID = "000000004c12ae6f"
        const val DEVICE_CLIENT_ID = "0000000048183522"
        const val XCLOUD_CLIENT_ID = "1f907974-e22b-4810-a9de-d9647380c97e"
        const val REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf"
        const val LIVE_SCOPE = "service::user.auth.xboxlive.com::MBI_SSL"
        const val TRANSFER_TOKEN_SCOPE =
            "service::http://Passport.NET/purpose::PURPOSE_XBOX_CLOUD_CONSOLE_TRANSFER_TOKEN"

        fun getAuthorizationUrl(clientId: String = BROWSER_CLIENT_ID): String {
            return "https://login.live.com/oauth20_authorize.srf" +
                "?client_id=$clientId" +
                "&response_type=token" +
                "&approval_prompt=auto" +
                "&scope=$LIVE_SCOPE" +
                "&redirect_uri=$REDIRECT_URI"
        }

        fun normalizeRpsTicket(rawTicket: String): String {
            val trimmed = rawTicket.trim()
            if (trimmed.startsWith("t=") || trimmed.startsWith("d=")) {
                return trimmed
            }
            return "d=$trimmed"
        }
    }

    /**
     * Suporta todas as sobrecargas de saveTokens chamadas pela MainActivity e pelos Testes
     */
    fun saveTokens(
        accessToken: String,
        refreshToken: String = "",
        clientId: String = BROWSER_CLIENT_ID,
        expiresInSeconds: Long = 3600L
    ) {
        val cleanAccess = accessToken.removePrefix("d=").removePrefix("t=").trim()
        val stored = StoredOAuthTokens(
            accessToken = cleanAccess,
            refreshToken = refreshToken,
            expiresAtEpochMillis = System.currentTimeMillis() + (expiresInSeconds * 1000L)
        )
        tokenStore.save(stored)
    }

    fun saveRpsTicket(rpsTicket: String) {
        saveTokens(accessToken = rpsTicket)
    }

    fun getSavedTokens(): StoredOAuthTokens? = tokenStore.load()

    fun getSavedRpsTicket(): String? = tokenStore.load()?.accessToken?.takeIf { it.isNotBlank() }

    fun clearSession() {
        tokenStore.clear()
    }

    suspend fun requestDeviceCodeQr(): Result<DeviceCodeInfo> = withContext(Dispatchers.IO) {
        try {
            val formBody = FormBody.Builder()
                .add("client_id", DEVICE_CLIENT_ID)
                .add("scope", LIVE_SCOPE)
                .add("response_type", "device_code")
                .build()

            val req = Request.Builder()
                .url("https://login.live.com/oauth20_connect.srf")
                .post(formBody)
                .build()

            val respStr = httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw buildStageException("DEVICE_CODE", resp.code, body, resp.headers["MS-CV"])
                }
                body
            }

            val json = JSONObject(respStr)
            val userCode = json.getString("user_code")
            val deviceCode = json.getString("device_code")
            val verificationUri = json.optString(
                "verification_uri",
                "https://login.live.com/oauth20_remoteconnect.srf"
            )
            val completeUri = "$verificationUri?otc=$userCode"
            val interval = json.optInt("interval", 5).coerceAtLeast(1)
            val expiresIn = json.optInt("expires_in", 600)

            val qrBitmap = generateQrCodeBitmap(completeUri, 420)

            Result.success(
                DeviceCodeInfo(
                    userCode = userCode,
                    deviceCode = deviceCode,
                    verificationUri = verificationUri,
                    verificationUriComplete = completeUri,
                    intervalSeconds = interval,
                    expiresInSeconds = expiresIn,
                    qrBitmap = qrBitmap
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun pollForDeviceCodeLogin(deviceInfo: DeviceCodeInfo): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val maxAttempts = (deviceInfo.expiresInSeconds / deviceInfo.intervalSeconds).coerceAtLeast(30)
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
                        val bodyStr = resp.body?.string().orEmpty()
                        val json = runCatching { JSONObject(bodyStr) }.getOrNull() ?: JSONObject()

                        if (resp.isSuccessful && json.has("access_token")) {
                            val accessToken = json.getString("access_token")
                            val refreshToken = json.optString("refresh_token", "")
                            val expiresIn = json.optLong("expires_in", 3600L)
                            saveTokens(
                                accessToken = accessToken,
                                refreshToken = refreshToken,
                                clientId = DEVICE_CLIENT_ID,
                                expiresInSeconds = expiresIn
                            )
                            return@withContext Result.success(accessToken)
                        } else {
                            val err = json.optString("error", "")
                            if (err != "authorization_pending" && err != "slow_down" && err.isNotEmpty()) {
                                throw buildStageException("DEVICE_TOKEN_POLL", resp.code, bodyStr, resp.headers["MS-CV"])
                            }
                        }
                    }
                }
                throw AuthStageException(
                    stage = "DEVICE_TOKEN_POLL",
                    message = "DEVICE_TOKEN_POLL: Tempo limite do QR Code esgotado"
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun refreshAccessTokenIfNeeded(clientId: String = DEVICE_CLIENT_ID): Result<StoredOAuthTokens> =
        withContext(Dispatchers.IO) {
            val current = tokenStore.load()
                ?: return@withContext Result.failure(
                    AuthStageException("OAUTH_REFRESH", message = "OAUTH_REFRESH: Sem sessão guardada")
                )

            val now = System.currentTimeMillis()
            if (current.accessToken.isNotBlank() && current.expiresAtEpochMillis > now + 60_000L) {
                return@withContext Result.success(current)
            }

            if (current.refreshToken.isBlank()) {
                return@withContext Result.success(current)
            }

            try {
                val formBody = FormBody.Builder()
                    .add("client_id", clientId)
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", current.refreshToken)
                    .add("scope", LIVE_SCOPE)
                    .build()

                val req = Request.Builder()
                    .url("https://login.live.com/oauth20_token.srf")
                    .post(formBody)
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw buildStageException("OAUTH_REFRESH", resp.code, body, resp.headers["MS-CV"])
                    }
                    val json = JSONObject(body)
                    val newAccess = json.getString("access_token")
                    val newRefresh = json.optString("refresh_token", current.refreshToken)
                    val expiresIn = json.optLong("expires_in", 3600L)
                    val updated = StoredOAuthTokens(
                        accessToken = newAccess,
                        refreshToken = newRefresh,
                        expiresAtEpochMillis = System.currentTimeMillis() + (expiresIn * 1000L)
                    )
                    tokenStore.save(updated)
                    Result.success(updated)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun fetchConsoleTransferToken(refreshToken: String): String = withContext(Dispatchers.IO) {
        if (refreshToken.isBlank()) return@withContext ""

        for (clientId in listOf(DEVICE_CLIENT_ID, BROWSER_CLIENT_ID, XCLOUD_CLIENT_ID)) {
            try {
                val formBody = FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .add("client_id", clientId)
                    .add("scope", TRANSFER_TOKEN_SCOPE)
                    .build()

                val req = Request.Builder()
                    .url("https://login.live.com/oauth20_token.srf")
                    .post(formBody)
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        val token = JSONObject(body).optString("access_token", "")
                        if (token.isNotBlank()) return@withContext token
                    }
                }
            } catch (ignored: Exception) {
            }
        }
        ""
    }

    suspend fun authenticateToXCloud(rpsTicketInput: String? = null): Result<XCloudSession> =
        withContext(Dispatchers.IO) {
            try {
                val stored = refreshAccessTokenIfNeeded().getOrNull() ?: tokenStore.load()
                val rawTicket = rpsTicketInput?.takeIf { it.isNotBlank() }
                    ?: stored?.accessToken
                    ?: throw AuthStageException("XBL_USER_TOKEN", message = "XBL_USER_TOKEN: Token OAuth ausente")

                val candidateTickets = listOf(
                    normalizeRpsTicket(rawTicket),
                    "t=${rawTicket.removePrefix("d=").removePrefix("t=").trim()}"
                ).distinct()

                var userToken = ""
                var uhs = ""
                var lastUserError: Exception? = null

                for (ticket in candidateTickets) {
                    val userTokenJson = JSONObject().apply {
                        put("RelyingParty", "http://auth.xboxlive.com")
                        put("TokenType", "JWT")
                        put("Properties", JSONObject().apply {
                            put("AuthMethod", "RPS")
                            put("SiteName", "user.auth.xboxlive.com")
                            put("RpsTicket", ticket)
                        })
                    }

                    val userReq = Request.Builder()
                        .url("https://user.auth.xboxlive.com/user/authenticate")
                        .addHeader("x-xbl-contract-version", "1")
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Accept", "application/json")
                        .post(userTokenJson.toString().toRequestBody(jsonMediaType))
                        .build()

                    val attemptResult = runCatching {
                        httpClient.newCall(userReq).execute().use { resp ->
                            val body = resp.body?.string().orEmpty()
                            if (!resp.isSuccessful) {
                                throw buildStageException("XBL_USER_TOKEN", resp.code, body, resp.headers["MS-CV"])
                            }
                            val json = JSONObject(body)
                            val tok = json.getString("Token")
                            val hash = json.getJSONObject("DisplayClaims")
                                .getJSONArray("xui")
                                .getJSONObject(0)
                                .getString("uhs")
                            Pair(tok, hash)
                        }
                    }

                    if (attemptResult.isSuccess) {
                        val pair = attemptResult.getOrThrow()
                        userToken = pair.first
                        uhs = pair.second
                        break
                    } else {
                        lastUserError = attemptResult.exceptionOrNull() as? Exception
                    }
                }

                if (userToken.isEmpty()) {
                    throw lastUserError ?: AuthStageException("XBL_USER_TOKEN", message = "Falha no UserToken")
                }

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
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(xstsJson.toString().toRequestBody(jsonMediaType))
                    .build()

                val xstsToken = httpClient.newCall(xstsReq).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw buildStageException("XSTS_AUTHORIZE", resp.code, body, resp.headers["MS-CV"])
                    }
                    JSONObject(body).getString("Token")
                }

                val xcloudLoginJson = JSONObject().apply {
                    put("token", xstsToken)
                    put("offeringId", "xgpuweb")
                }

                val xcloudReq = Request.Builder()
                    .url("https://xgpuweb.gssv-play-prod.xboxlive.com/v2/login/user")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .addHeader("x-gssv-client", "XboxComBrowser")
                    .post(xcloudLoginJson.toString().toRequestBody(jsonMediaType))
                    .build()

                val xcloudData = httpClient.newCall(xcloudReq).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw buildStageException("GSSV_LOGIN", resp.code, body, resp.headers["MS-CV"])
                    }
                    JSONObject(body)
                }

                val gssvToken = xcloudData.getString("gsToken")
                val regions = xcloudData.optJSONObject("offeringSettings")?.optJSONArray("regions") ?: JSONArray()
                var baseUri = "https://bra.gssv-play-prod.xboxlive.com"
                for (i in 0 until regions.length()) {
                    val region = regions.getJSONObject(i)
                    if (region.optBoolean("isDefault", false)) {
                        baseUri = region.getString("baseUri").trimEnd('/')
                        break
                    }
                }

                val refreshToken = stored?.refreshToken.orEmpty()
                val transferToken = fetchConsoleTransferToken(refreshToken)

                Result.success(
                    XCloudSession(
                        gssvToken = gssvToken,
                        baseUri = baseUri,
                        userHash = uhs,
                        transferToken = transferToken
                    )
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun buildStageException(
        stage: String,
        statusCode: Int,
        body: String,
        correlationIdHeader: String?
    ): AuthStageException {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val errorCode = json?.optString("error")?.takeIf { it.isNotEmpty() }
            ?: json?.optString("XErr")?.takeIf { it.isNotEmpty() }
            ?: json?.optString("code")?.takeIf { it.isNotEmpty() }
        val desc = json?.optString("error_description")?.takeIf { it.isNotEmpty() }
            ?: json?.optString("message")?.takeIf { it.isNotEmpty() }
        val corrId = correlationIdHeader ?: json?.optString("correlationId")?.takeIf { it.isNotEmpty() }

        val msg = buildString {
            append("$stage: HTTP $statusCode")
            if (!errorCode.isNullOrBlank()) append(" — código $errorCode")
            if (!desc.isNullOrBlank()) append(" ($desc)")
            if (!corrId.isNullOrBlank()) append(" (requisição $corrId)")
        }
        return AuthStageException(stage, statusCode, errorCode, corrId, msg)
    }

    private fun generateQrCodeBitmap(content: String, size: Int): Bitmap {
        return try {
            val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (e: Throwable) {
            runCatching { Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565) }.getOrNull()
                ?: throw RuntimeException("QR Bitmap fallback", e)
        }
    }
}
