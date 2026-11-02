package com.xcloud.tclnative.auth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.xcloud.tclnative.net.CloudHttpException
import com.xcloud.tclnative.net.awaitResponse
import com.xcloud.tclnative.net.checkedBody
import com.xcloud.tclnative.catalog.XboxProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class XCloudSession(
    val gssvToken: String, val baseUri: String, val userHash: String,
    val expiresAtMs: Long = System.currentTimeMillis() + 3600_000L, val transferToken: String = ""
)
data class AuthEndpoints(
    val deviceCodeUrl: String = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
    val tokenUrl: String = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
    val userAuthUrl: String = "https://user.auth.xboxlive.com/user/authenticate",
    val xstsAuthorizeUrl: String = "https://xsts.auth.xboxlive.com/xsts/authorize",
    val xcloudLoginUrl: String = "https://xgpuweb.gssv-play-prod.xboxlive.com/v2/login/user",
    val transferTokenUrl: String = "https://login.live.com/oauth20_token.srf",
    val profileUrl: String = "https://profile.xboxlive.com/users/me/profile/settings?settings=GameDisplayName,GameDisplayPicRaw,Gamertag"
)
data class DeviceCodeInfo(
    val userCode: String, val deviceCode: String, val verificationUri: String,
    val verificationUriComplete: String?, val intervalSeconds: Long, val expiresAtMs: Long,
    val qrBitmap: Bitmap? = null
)
class AuthenticationRequiredException(message: String = "Autenticação necessária. Entre novamente pelo QR Code.") : Exception(message)

class XalAuthenticator(
    private val store: OAuthTokenStore,
    private val httpClient: OkHttpClient,
    private val endpoints: AuthEndpoints = AuthEndpoints(),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val pause: suspend (Long) -> Unit = { delay(it) }
) {
    private var profileUserToken: String? = null
    private var profileUserHash: String? = null
    constructor(context: Context) : this(PreferencesTokenStore(context),
        OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build())

    companion object {
        const val BROWSER_CLIENT_ID = "000000004c12ae6f"
        const val DEVICE_CLIENT_ID = "0000000048183522"
        const val XCLOUD_CLIENT_ID = "1f907974-e22b-4810-a9de-d9647380c97e"
        const val LIVE_SCOPE = "service::user.auth.xboxlive.com::MBI_SSL"
        const val MSAL_SCOPE = "xboxlive.signin openid profile offline_access"
        const val TRANSFER_TOKEN_SCOPE = "service::http://Passport.NET/purpose::PURPOSE_XBOX_CLOUD_CONSOLE_TRANSFER_TOKEN"
        fun normalizeRpsTicket(raw: String): String = raw.trim().let {
            if (it.startsWith("t=") || it.startsWith("d=")) it else "t=$it"
        }
    }
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private fun json(value: JSONObject) = value.toString().toRequestBody(jsonType)
    private suspend fun post(url: String, stage: String, body: RequestBody): String =
        httpClient.newCall(Request.Builder().url(url).header("Accept", "application/json")
            .header("x-xbl-contract-version", "1").header("X-Gssv-Client", "XboxComBrowser")
            .post(body).build()).awaitResponse().use { it.checkedBody(stage) }

    private suspend fun <T> result(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try { Result.success(block()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }

    fun saveTokens(accessToken: String, refreshToken: String? = null, expiresInSeconds: Long? = null,
                   clientId: String? = null) {
        val ticket = normalizeRpsTicket(accessToken)
        val old = store.read()?.takeIf { normalizeRpsTicket(it.ticket) == ticket }
        store.write(OAuthToken(ticket, refreshToken ?: old?.refreshToken,
            expiresInSeconds?.let { clockMs() + it * 1000 } ?: old?.expiresAtMs,
            clientId ?: old?.clientId ?: BROWSER_CLIENT_ID))
    }
    fun saveRpsTicket(ticket: String) = saveTokens(ticket)
    fun getSavedRpsTicket(): String? = store.read()?.ticket
    fun clearSession() { profileUserToken = null; profileUserHash = null; store.clear() }
    fun supportsConsoleTransfer(): Boolean = store.read()?.let {
        it.clientId == XCLOUD_CLIENT_ID && !it.refreshToken.isNullOrBlank()
    } == true

    suspend fun requestDeviceCodeQr(): Result<DeviceCodeInfo> = result {
        val data = JSONObject(post(endpoints.deviceCodeUrl, "AUTH_DEVICE_CODE", FormBody.Builder()
            .add("client_id", XCLOUD_CLIENT_ID).add("scope", MSAL_SCOPE).build()))
        val uri = data.getString("verification_uri")
        val complete = data.optString("verification_uri_complete").takeIf { it.isNotBlank() }
        // The provider's URI is authoritative; display the separate user code when no complete URI exists.
        val bitmap = runCatching { generateQrCodeBitmap(complete ?: uri) }.getOrNull()
        DeviceCodeInfo(data.getString("user_code"), data.getString("device_code"), uri, complete,
            data.optLong("interval", 5).coerceAtLeast(1), clockMs() + data.getLong("expires_in") * 1000, bitmap)
    }

    suspend fun pollForDeviceCodeLogin(info: DeviceCodeInfo): Result<String> = result {
        var interval = info.intervalSeconds.coerceAtLeast(1) * 1000
        while (clockMs() < info.expiresAtMs) {
            pause(minOf(interval, info.expiresAtMs - clockMs()))
            if (clockMs() >= info.expiresAtMs) break
            try {
                val data = JSONObject(post(endpoints.tokenUrl, "AUTH_TOKEN", FormBody.Builder()
                    .add("client_id", XCLOUD_CLIENT_ID).add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .add("device_code", info.deviceCode).build()))
                val refresh = data.optString("refresh_token").takeIf { it.isNotBlank() }
                    ?: throw AuthenticationRequiredException("AUTH_TOKEN: login sem renovação. Solicite outro QR Code.")
                saveTokens(data.getString("access_token"), refresh, data.getLong("expires_in"), XCLOUD_CLIENT_ID)
                return@result store.read()!!.ticket
            } catch (error: CloudHttpException) {
                when (error.serviceCode) {
                    "authorization_pending" -> Unit
                    "slow_down" -> interval += 5000
                    "authorization_declined", "access_denied", "expired_token" -> throw AuthenticationRequiredException("AUTH_TOKEN: aprovação negada ou expirada.")
                    else -> throw error
                }
            }
        }
        throw AuthenticationRequiredException("AUTH_TOKEN: o código expirou; solicite outro QR Code.")
    }

    private suspend fun refresh(token: OAuthToken): OAuthToken {
        val refreshToken = token.refreshToken ?: throw AuthenticationRequiredException()
        val data = try {
            val msal = token.clientId == XCLOUD_CLIENT_ID
            JSONObject(post(if (msal) endpoints.tokenUrl else endpoints.transferTokenUrl, "AUTH_REFRESH",
                FormBody.Builder().add("grant_type", "refresh_token").add("client_id", token.clientId)
                    .add("refresh_token", refreshToken).add("scope", if (msal) MSAL_SCOPE else LIVE_SCOPE).build()))
        } catch (error: CloudHttpException) {
            if (error.serviceCode == "invalid_grant") throw AuthenticationRequiredException("AUTH_REFRESH: login expirado ou revogado. Entre pelo QR Code.")
            throw error
        }
        return OAuthToken(normalizeRpsTicket(data.getString("access_token")),
            data.optString("refresh_token").takeIf { it.isNotBlank() } ?: refreshToken,
            clockMs() + data.getLong("expires_in") * 1000, token.clientId).also(store::write)
    }

    suspend fun authenticateToXCloud(rpsTicketInput: String? = null, onProgress: (String) -> Unit = {}): Result<XCloudSession> = result {
        profileUserToken = null
        profileUserHash = null
        var token = store.read()
        if (!rpsTicketInput.isNullOrBlank() && (token == null || normalizeRpsTicket(token.ticket) != normalizeRpsTicket(rpsTicketInput))) {
            saveTokens(rpsTicketInput)
            token = store.read()
        }
        var current = token ?: throw AuthenticationRequiredException()
        if (current.expiresAtMs?.let { it <= clockMs() } == true) current = refresh(current)
        onProgress("AUTH_XBL: Autenticando na Xbox Live...")
        suspend fun userTicket(token: OAuthToken): JSONObject {
            val ticket = if (token.clientId == DEVICE_CLIENT_ID || token.clientId == XCLOUD_CLIENT_ID)
                "d=" + token.ticket.removePrefix("t=").removePrefix("d=")
                else normalizeRpsTicket(token.ticket)
            return JSONObject(post(endpoints.userAuthUrl, "AUTH_XBL", json(JSONObject()
                .put("RelyingParty", "http://auth.xboxlive.com").put("TokenType", "JWT").put("Properties", JSONObject()
                    .put("AuthMethod", "RPS").put("SiteName", "user.auth.xboxlive.com").put("RpsTicket", ticket)))))
        }
        val user = try { userTicket(current) } catch (error: CloudHttpException) {
            if (error.status != 401) throw error
            current = refresh(current)
            userTicket(current)
        }
        val hash = user.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).getString("uhs")
        onProgress("AUTH_XSTS: Autorizando o acesso à nuvem...")
        val xsts = JSONObject(post(endpoints.xstsAuthorizeUrl, "AUTH_XSTS", json(JSONObject()
            .put("RelyingParty", "http://gssv.xboxlive.com/").put("TokenType", "JWT").put("Properties", JSONObject()
                .put("SandboxId", "RETAIL").put("UserTokens", JSONArray().put(user.getString("Token")))))))
        onProgress("AUTH_GSSV: Conectando ao xCloud...")
        val cloud = JSONObject(post(endpoints.xcloudLoginUrl, "AUTH_GSSV", json(JSONObject()
            .put("token", xsts.getString("Token")).put("offeringId", "xgpuweb"))))
        val regions = cloud.getJSONObject("offeringSettings").getJSONArray("regions")
        val region = (0 until regions.length()).map { regions.getJSONObject(it) }.firstOrNull { it.optBoolean("isDefault") }
            ?: throw java.io.IOException("AUTH_GSSV: o serviço não informou uma região padrão")
        profileUserToken = user.getString("Token")
        profileUserHash = hash
        XCloudSession(cloud.getString("gsToken"), region.getString("baseUri").trimEnd('/'), hash,
            clockMs() + cloud.optLong("durationInSeconds", 3600) * 1000)
    }

    /** Optional Xbox profile grant is separate from GSSV/console transfer and never blocks playing. */
    suspend fun loadXboxProfile(): Result<XboxProfile> = result {
        val token = profileUserToken ?: throw AuthenticationRequiredException()
        val hash = profileUserHash ?: throw AuthenticationRequiredException()
        val web = JSONObject(post(endpoints.xstsAuthorizeUrl, "PROFILE_XSTS", json(JSONObject()
            .put("RelyingParty", "http://xboxlive.com").put("TokenType", "JWT").put("Properties", JSONObject()
                .put("SandboxId", "RETAIL").put("UserTokens", JSONArray().put(token))))))
        val webHash = web.optJSONObject("DisplayClaims")?.optJSONArray("xui")?.optJSONObject(0)
            ?.optString("uhs")?.takeIf { it.isNotBlank() } ?: hash
        val request = Request.Builder().url(endpoints.profileUrl)
            .header("Authorization", "XBL3.0 x=$webHash;${web.getString("Token")}")
            .header("x-xbl-contract-version", "2").header("Accept-Language", "pt-BR")
            .header("Accept", "application/json").build()
        httpClient.newCall(request).awaitResponse().use { XboxProfile.parse(JSONObject(it.checkedBody("PROFILE"))) }
    }

    /** Requested only when a ReadyToConnect session needs /connect; never use the GSSV token here. */
    suspend fun getConsoleTransferToken(): String = withContext(Dispatchers.IO) {
        val token = store.read() ?: throw AuthenticationRequiredException()
        if (token.clientId != XCLOUD_CLIENT_ID) throw AuthenticationRequiredException(
            "CONSOLE_TOKEN: atualize o login pelo QR Code desta versão para autorizar a conexão Xbox.")
        val refresh = token.refreshToken ?: throw AuthenticationRequiredException(
            "CONSOLE_CONNECT: este login não possui refresh token. Entre novamente pelo QR Code.")
        val data = try {
            JSONObject(post(endpoints.transferTokenUrl, "CONSOLE_TOKEN", FormBody.Builder()
                .add("grant_type", "refresh_token").add("client_id", token.clientId)
                .add("refresh_token", refresh).add("scope", TRANSFER_TOKEN_SCOPE).build()))
        } catch (error: CloudHttpException) {
            if (error.serviceCode in setOf("invalid_grant", "invalid_scope", "consent_required")) throw AuthenticationRequiredException(
                "CONSOLE_TOKEN: login sem permissão de conexão (${error.serviceCode}). Entre novamente pelo QR Code desta versão.")
            throw error
        }
        data.optString("refresh_token").takeIf { it.isNotBlank() }?.let { store.write(token.copy(refreshToken = it)) }
        data.getString("access_token").takeIf { it.isNotBlank() }
            ?: throw AuthenticationRequiredException("CONSOLE_TOKEN: o serviço não retornou credencial de transferência.")
    }

    fun generateQrCodeBitmap(content: String, size: Int = 420): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).also { bitmap ->
            for (x in 0 until size) for (y in 0 until size) bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }
}
