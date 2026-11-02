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
    val expiresInSeconds: Int,
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
    context: Context,
    private val httpClient: OkHttpClient = CloudHttp.client,
    private val tokenStore: OAuthTokenStore = SharedPrefsOAuthTokenStore(context)
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        internal const val DEVICE_CLIENT_ID = "0000000048183522"
        internal const val XCLOUD_CLIENT_ID = "1f907974-e22b-4810-a9de-d9647380c97e"
        internal const val LIVE_SCOPE = "service::user.auth.xboxlive.com::MBI_SSL"
        internal const val TRANSFER_TOKEN_SCOPE =
            "service::http://Passport.NET/purpose::PURPOSE_XBOX_CLOUD_CONSOLE_TRANSFER_TOKEN"

        internal fun normalizeRpsTicket(rawTicket: String): String {
            val trimmed = rawTicket.trim()
            if (trimmed.startsWith("t=") || trimmed.startsWith("d=")) {
                return trimmed
            }
            return "d=$trimmed"
        }
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
            val interval = json.optInt("interval", 5).coerceAtLeast(3)
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

    suspend fun pollForDeviceCodeLogin(deviceInfo: DeviceCodeInfo): Result<StoredOAuthTokens> =
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

                    val req = Request.
