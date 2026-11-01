package com.xcloud.tclnative.auth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.xcloud.tclnative.net.CloudHttpException
import com.xcloud.tclnative.net.awaitResponse
import com.xcloud.tclnative.net.checkedBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class XCloudSession(val gssvToken: String, val baseUri: String, val userHash: String)
data class DeviceCodeInfo(
    val userCode: String, val deviceCode: String, val verificationUri: String,
    val verificationUriComplete: String?, val intervalSeconds: Int, val expiresAtMs: Long
)
data class AuthEndpoints(
    val deviceCode: String = "https://login.live.com/oauth20_connect.srf",
    val token: String = "https://login.live.com/oauth20_token.srf",
    val user: String = "https://user.auth.xboxlive.com/user/authenticate",
    val xsts: String = "https://xsts.auth.xboxlive.com/xsts/authorize",
    val cloud: String = "https://xgpuweb.gssv-play-prod.xboxlive.com/v2/login/user"
)
class AuthenticationRequiredException(message: String) : IOException(message)

class XalAuthenticator(
    private val store: OAuthTokenStore,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).build(),
    private val endpoints: AuthEndpoints = AuthEndpoints(),
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) }
) {
    constructor(context: Context) : this(PreferencesTokenStore(context.applicationContext))
    companion object {
        const val DEVICE_CLIENT_ID = "0000000048183522"
        const val BROWSER_CLIENT_ID = "000000004c12ae6f"
        private const val SCOPE = "service::user.auth.xboxlive.com::MBI_SSL"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    fun getSavedRpsTicket(): String? = store.read()?.ticket
    fun clearSession() = store.clear()

    fun saveTokens(ticket: String, refreshToken: String? = null,
                   expiresInSeconds: Long? = null, clientId: String = BROWSER_CLIENT_ID) {
        val normalized = normalizeTicket(ticket)
        val previous = store.read()?.takeIf { it.ticket == normalized }
        store.write(OAuthToken(normalized, refreshToken?.takeIf { it.isNotBlank() } ?: previous?.refreshToken,
            expiresInSeconds?.let { now() + it * 1000 } ?: previous?.expiresAtMs,
            previous?.clientId ?: clientId))
    }

    suspend fun requestDeviceCodeQr(): Result<DeviceCodeInfo> = result {
        val json = JSONObject(postForm(endpoints.deviceCode, "AUTH_DEVICE_CODE", FormBody.Builder()
            .add("client_id", DEVICE_CLIENT_ID).add("scope", SCOPE)
            .add("response_type", "device_code").build()))
        val uri = json.getString("verification_uri")
        require(uri.toHttpUrl().isHttps) { "AUTH_DEVICE_CODE: URI de verificação insegura" }
        val complete = json.optString("verification_uri_complete").takeIf { it.isNotBlank() }
        complete?.let { require(it.toHttpUrl().isHttps) }
        DeviceCodeInfo(json.getString("user_code"), json.getString("device_code"), uri, complete,
            json.optInt("interval", 5).coerceAtLeast(1),
            now() + json.getLong("expires_in").coerceAtLeast(1) * 1000)
    }

    suspend fun pollForDeviceCodeLogin(info: DeviceCodeInfo): Result<String> = result {
        var intervalMs = info.intervalSeconds * 1000L
        while (now() < info.expiresAtMs) {
            pause(minOf(intervalMs, info.expiresAtMs - now()))
            if (now() >= info.expiresAtMs) break
            val request = Request.Builder().url(endpoints.token).post(FormBody.Builder()
                .add("client_id", DEVICE_CLIENT_ID)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .add("device_code", info.deviceCode).build()).build()
            httpClient.newCall(request).awaitResponse().use { response ->
                val json = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrElse {
                    throw IOException("AUTH_TOKEN: resposta inválida (HTTP ${response.code})")
                }
                when (json.optString("error")) {
                    "authorization_pending" -> Unit
                    "slow_down" -> intervalMs += 5_000
                    "expired_token", "code_expired" -> throw AuthenticationRequiredException("AUTH_TOKEN: código expirado; gere outro QR Code")
                    "access_denied", "authorization_declined" -> throw AuthenticationRequiredException("AUTH_TOKEN: aprovação recusada")
                    "" -> {
                        if (!response.isSuccessful || !json.has("access_token"))
                            throw IOException("AUTH_TOKEN: resposta inválida (HTTP ${response.code})")
                        saveTokens(json.getString("access_token"), json.optString("refresh_token"),
                            json.getLong("expires_in"), DEVICE_CLIENT_ID)
                        return@result store.read()!!.ticket
                    }
                    else -> throw IOException("AUTH_TOKEN: HTTP ${response.code} — código " +
                        json.optString("error").replace(Regex("[^A-Za-z0-9_.-]"), "").take(80))
                }
            }
        }
        throw AuthenticationRequiredException("AUTH_TOKEN: prazo do QR Code esgotado; gere outro código")
    }

    suspend fun authenticateToXCloud(ticket: String, onProgress: (String) -> Unit = {}): Result<XCloudSession> = result {
        val normalized = normalizeTicket(ticket)
        var token = store.read()?.takeIf { it.ticket == normalized }
            ?: OAuthToken(normalized, null, null, BROWSER_CLIENT_ID).also(store::write)
        if (token.expiresAtMs?.let { it <= now() + 60_000 } == true) {
            onProgress("AUTH_REFRESH: renovando login...")
            token = refresh(token)
        }
        try { exchange(token.ticket, onProgress) }
        catch (e: CloudHttpException) {
            if (e.stage == "AUTH_XBL" && e.status == 401 && token.refreshToken != null) {
                onProgress("AUTH_REFRESH: renovando login...")
                exchange(refresh(token).ticket, onProgress)
            } else if (e.stage == "AUTH_XBL" && e.status == 401) {
                throw AuthenticationRequiredException("AUTH_XBL: login expirou; entre novamente")
            } else throw e
        }
    }

    private suspend fun refresh(token: OAuthToken): OAuthToken {
        val refresh = token.refreshToken ?: throw AuthenticationRequiredException("AUTH_REFRESH: login expirou; entre novamente")
        val body = try {
            postForm(endpoints.token, "AUTH_REFRESH", FormBody.Builder()
                .add("client_id", token.clientId).add("grant_type", "refresh_token")
                .add("refresh_token", refresh).add("scope", SCOPE).build())
        } catch (e: CloudHttpException) {
            if (e.serviceCode == "invalid_grant") throw AuthenticationRequiredException("AUTH_REFRESH: login revogado; entre novamente")
            throw e
        }
        val json = JSONObject(body)
        return OAuthToken(normalizeTicket(json.getString("access_token")),
            json.optString("refresh_token").takeIf { it.isNotBlank() } ?: refresh,
            now() + json.getLong("expires_in") * 1000, token.clientId).also(store::write)
    }

    private suspend fun exchange(ticket: String, onProgress: (String) -> Unit): XCloudSession {
        onProgress("AUTH_XBL: autenticando na Xbox Live...")
        val user = JSONObject(postJson(endpoints.user, "AUTH_XBL", JSONObject()
            .put("RelyingParty", "http://auth.xboxlive.com").put("TokenType", "JWT")
            .put("Properties", JSONObject().put("AuthMethod", "RPS").put("SiteName", "user.auth.xboxlive.com").put("RpsTicket", ticket))))
        val userHash = user.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).getString("uhs")
        onProgress("AUTH_XSTS: autorizando acesso à nuvem...")
        val xsts = JSONObject(postJson(endpoints.xsts, "AUTH_XSTS", JSONObject()
            .put("RelyingParty", "http://gssv.xboxlive.com/").put("TokenType", "JWT")
            .put("Properties", JSONObject().put("SandboxId", "RETAIL").put("UserTokens", JSONArray().put(user.getString("Token"))))))
        onProgress("AUTH_GSSV: obtendo sessão xCloud...")
        val cloud = JSONObject(postJson(endpoints.cloud, "AUTH_GSSV", JSONObject()
            .put("token", xsts.getString("Token")).put("offeringId", "xgpuweb")))
        val regions = cloud.getJSONObject("offeringSettings").getJSONArray("regions")
        val entries = (0 until regions.length()).map { regions.getJSONObject(it) }
        val region = entries.firstOrNull { it.optBoolean("isDefault") } ?: entries.firstOrNull()
            ?: throw IOException("AUTH_GSSV: nenhuma região disponível para esta conta")
        val base = region.getString("baseUri").toHttpUrl()
        require(base.isHttps) { "AUTH_GSSV: endereço de região inválido" }
        return XCloudSession(cloud.getString("gsToken"), base.toString().trimEnd('/'), userHash)
    }

    private suspend fun postForm(url: String, stage: String, body: FormBody): String =
        httpClient.newCall(Request.Builder().url(url).post(body).build()).awaitResponse().use { it.checkedBody(stage) }
    private suspend fun postJson(url: String, stage: String, body: JSONObject): String =
        httpClient.newCall(Request.Builder().url(url).header("x-xbl-contract-version", "1")
            .header("x-gssv-client", "XboxComBrowser").post(body.toString().toRequestBody(JSON_TYPE)).build())
            .awaitResponse().use { it.checkedBody(stage) }
    private suspend fun <T> result(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try { Result.success(block()) } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }
    private fun normalizeTicket(ticket: String): String =
        if (ticket.startsWith("t=") || ticket.startsWith("d=")) ticket else "t=$ticket"

    fun generateQrCodeBitmap(content: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
            val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }
}
