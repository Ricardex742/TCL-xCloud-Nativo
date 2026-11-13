package com.xcloud.tclnative.auth

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.toByteString
import java.security.MessageDigest
import java.security.SecureRandom

/** One short-lived authorization-code grant. Credentials stay on Microsoft's own sign-in page. */
class BrowserAuthorization private constructor(private val verifier: String,private val state: String,
                                               private val createdAt: Long,authorizeUrl: String) {
    companion object {
        const val REDIRECT="https://login.microsoftonline.com/common/oauth2/nativeclient"
        private const val LIFETIME=10*60_000L
        fun create(now: Long,authorizeUrl: String="https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize"): BrowserAuthorization {
            val random=SecureRandom()
            fun secret()=ByteArray(32).also(random::nextBytes).toByteString().base64Url().trimEnd('=')
            return BrowserAuthorization(secret(),secret(),now,authorizeUrl)
        }
    }
    sealed interface Redirect {
        data object Continue: Redirect
        data class Authorized(val code: String): Redirect
        data object Denied: Redirect
        data object Invalid: Redirect
    }
    private var acceptedCode: String?=null
    private var consumed=false
    private var exchanged=false
    val url: String=authorizeUrl.toHttpUrl().newBuilder()
        .addQueryParameter("client_id",XalAuthenticator.XCLOUD_CLIENT_ID)
        .addQueryParameter("response_type","code").addQueryParameter("redirect_uri",REDIRECT)
        .addQueryParameter("scope",XalAuthenticator.MSAL_SCOPE).addQueryParameter("state",state)
        .addQueryParameter("code_challenge",MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            .toByteString().base64Url().trimEnd('='))
        .addQueryParameter("code_challenge_method","S256").addQueryParameter("response_mode","query")
        .addQueryParameter("prompt","select_account").build().toString()

    @Synchronized fun redirect(value: String,now: Long): Redirect {
        val uri=value.toHttpUrlOrNull() ?: return Redirect.Continue
        val expected=REDIRECT.toHttpUrl()
        if(uri.scheme!=expected.scheme || uri.host!=expected.host || uri.port!=expected.port ||
            uri.encodedPath!=expected.encodedPath) return Redirect.Continue
        if(consumed || now-createdAt !in 0 until LIFETIME || uri.username.isNotEmpty() || uri.password.isNotEmpty() ||
            uri.fragment!=null || uri.queryParameterValues("state")!=listOf(state)) return Redirect.Invalid
        consumed=true
        val code=uri.queryParameterValues("code").singleOrNull()?.takeIf { it.isNotBlank() && it.length<=8192 }
        if(uri.queryParameter("error")!=null) return if(code==null) Redirect.Denied else Redirect.Invalid
        if(code==null) return Redirect.Invalid
        acceptedCode=code
        return Redirect.Authorized(code)
    }
    @Synchronized internal fun exchangeForm(code: String,now: Long): FormBody {
        check(consumed && !exchanged && acceptedCode==code && now-createdAt in 0 until LIFETIME) { "AUTH_BROWSER: invalid or expired authorization" }
        exchanged=true;acceptedCode=null
        return FormBody.Builder().add("client_id",XalAuthenticator.XCLOUD_CLIENT_ID)
            .add("grant_type","authorization_code").add("code",code).add("redirect_uri",REDIRECT)
            .add("code_verifier",verifier).add("scope",XalAuthenticator.MSAL_SCOPE).build()
    }
}
