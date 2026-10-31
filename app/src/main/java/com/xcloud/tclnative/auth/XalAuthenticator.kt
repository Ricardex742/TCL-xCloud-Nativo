package com.xcloud.tclnative.auth

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

class XalAuthenticator(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("xcloud_native_auth", Context.MODE_PRIVATE)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val formMediaType = "application/x-www-form-urlencoded".toMediaType()

    companion object {
        // Client ID oficial do Xbox App para autenticação OAuth2
        private const val CLIENT_ID = "000000004c12ae6f"
        private const val REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf"

        // URL de login direto (Abre no navegador do celular, faz login e devolve o código)
        fun getAuthorizationUrl(): String {
            return "https://login.live.com/oauth20_authorize.srf" +
                "?client_id=$CLIENT_ID" +
                "&response_type=token" +
                "&approval_prompt=auto" +
                "&scope=service::user.auth.xboxlive.com::MBI_SSL" +
                "&redirect_uri=$REDIRECT_URI"
        }
    }

    /**
     * Salva o token para você não precisar logar toda vez que abrir o app na TV ou no celular
     */
    fun saveRpsTicket(rpsTicket: String) {
        prefs.edit().putString("rps_ticket", rpsTicket).apply()
    }

    fun getSavedRpsTicket(): String? = prefs.getString("rps_ticket", null)

    /**
     * Executa a cadeia completa: RPS Ticket -> XBL User Token -> XSTS -> xCloud GSSV Token (Servidor Brasil)
     */
    suspend fun authenticateToXCloud(rpsTicket: String): Result<XCloudSession> = withContext(Dispatchers.IO) {
        try {
            // 1. Obter User Token na Xbox Live
            val userTokenJson = JSONObject().apply {
                put("RelyingParty", "http://auth.xboxlive.com")
                put("TokenType", "JWT")
                put("Properties", JSONObject().apply {
                    put("AuthMethod", "RPS")
                    put("SiteName", "user.auth.xboxlive.com")
                    put("RpsTicket", if (rpsTicket.startsWith("t=") || rpsTicket.startsWith("d=")) rpsTicket else "t=$rpsTicket")
                })
            }

            val userReq = Request.Builder()
                .url("https://user.auth.xboxlive.com/user/authenticate")
                .addHeader("x-xbl-contract-version", "1")
                .post(userTokenJson.toString().toRequestBody(jsonMediaType))
                .build()

            val userRespStr = httpClient.newCall(userReq).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("Falha no UserToken HTTP ${resp.code}")
                resp.body?.string() ?: throw Exception("Resposta vazia no UserToken")
            }

            val userRespJson = JSONObject(userRespStr)
            val userToken = userRespJson.getString("Token")
            val uhs = userRespJson.getJSONObject("DisplayClaims")
                .getJSONArray("xui")
                .getJSONObject(0)
                .getString("uhs")

            // 2. Obter XSTS Token específico para a nuvem do Game Pass (rp://xgpuweb.gssv-play-prod.xboxlive.com/)
            val xstsJson = JSONObject().apply {
                put("RelyingParty", " http://gssv.xboxlive.com/".trim())
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

            // 3. Trocar o XSTS pelo Token de Streaming do xCloud (Home/Cloud Gaming)
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
                if (!resp.isSuccessful) throw Exception("Falha no Login xCloud HTTP ${resp.code} (Verifique se o Game Pass Ultimate está ativo)")
                resp.body?.string() ?: throw Exception("Resposta vazia no xCloud Login")
            }

            val xcloudData = JSONObject(xcloudRespStr)
            val gssvToken = xcloudData.getString("gsToken")

            // Localiza a região padrão (ex: BrazilSouth)
            val regions = xcloudData.getJSONObject("offeringSettings").getJSONArray("regions")
            var baseUri = "https://brazilsouth.gssv-play-prod.xboxlive.com"
            for (i in 0 until regions.length()) {
                val region = regions.getJSONObject(i)
                if (region.optBoolean("isDefault", false)) {
                    baseUri = region.getString("baseUri")
                    break
                }
            }

            saveRpsTicket(rpsTicket)
            Result.success(XCloudSession(gssvToken = gssvToken, baseUri = baseUri, userHash = uhs))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
