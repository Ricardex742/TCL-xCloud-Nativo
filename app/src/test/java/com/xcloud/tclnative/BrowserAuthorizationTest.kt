package com.xcloud.tclnative

import com.xcloud.tclnative.auth.*
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class BrowserAuthorizationTest {
    private fun callback(request: BrowserAuthorization,code: String="code",state: String?=null)=BrowserAuthorization.REDIRECT.toHttpUrl().newBuilder()
        .addQueryParameter("state",state ?: request.url.toHttpUrl().queryParameter("state"))
        .addQueryParameter("code",code).build().toString()
    @Test fun requestsUseFreshStateAndPkceAndTheCloudClientThatSupportsConsoleTransfer() {
        val a=BrowserAuthorization.create(1000);val b=BrowserAuthorization.create(1000)
        val url=a.url.toHttpUrl()
        assertNotEquals(url.queryParameter("state"),b.url.toHttpUrl().queryParameter("state"))
        assertEquals(XalAuthenticator.XCLOUD_CLIENT_ID,url.queryParameter("client_id"))
        assertEquals("S256",url.queryParameter("code_challenge_method"));assertEquals("code",url.queryParameter("response_type"))
        assertEquals(BrowserAuthorization.Redirect.Authorized("code"),a.redirect(callback(a),1100))
        val form=a.exchangeForm("code",1200)
        val fields=(0 until form.size).associate { form.name(it) to form.value(it) }
        val challenge=MessageDigest.getInstance("SHA-256").digest(fields.getValue("code_verifier").toByteArray(Charsets.US_ASCII))
            .toByteString().base64Url().trimEnd('=')
        assertEquals(challenge,url.queryParameter("code_challenge"))
        assertEquals(BrowserAuthorization.REDIRECT,fields["redirect_uri"]);assertEquals("authorization_code",fields["grant_type"])
        assertTrue(runCatching { a.exchangeForm("code",1300) }.isFailure)
    }
    @Test fun foreignRedirectsWrongStateAndDuplicateParametersNeverProduceAGrant() {
        val a=BrowserAuthorization.create(0);val valid=callback(a)
        assertEquals(BrowserAuthorization.Redirect.Continue,a.redirect(valid.replace("login.microsoftonline.com","login.microsoftonline.com.evil.example"),1))
        assertEquals(BrowserAuthorization.Redirect.Continue,a.redirect(valid.replace("https:","http:"),1))
        assertEquals(BrowserAuthorization.Redirect.Invalid,a.redirect(callback(a,state="wrong"),1))
        assertEquals(BrowserAuthorization.Redirect.Invalid,a.redirect("$valid&state=extra",1))
        assertEquals(BrowserAuthorization.Redirect.Invalid,a.redirect("$valid#code=other",1))
        assertEquals(BrowserAuthorization.Redirect.Authorized("code"),a.redirect(valid,1))
        assertEquals(BrowserAuthorization.Redirect.Invalid,a.redirect(valid,2))
    }
    @Test fun denialExpirationAndUnacceptedCodesCannotBeExchanged() {
        val a=BrowserAuthorization.create(0)
        assertTrue(runCatching { a.exchangeForm("injected",1) }.isFailure)
        assertEquals(BrowserAuthorization.Redirect.Invalid,a.redirect(callback(a),600_001))
        val denied=BrowserAuthorization.create(0)
        val url=BrowserAuthorization.REDIRECT.toHttpUrl().newBuilder().addQueryParameter("state",denied.url.toHttpUrl().queryParameter("state"))
            .addQueryParameter("error","access_denied").addQueryParameter("error_description","private details").build().toString()
        assertEquals(BrowserAuthorization.Redirect.Denied,denied.redirect(url,1))
        assertTrue(runCatching { denied.exchangeForm("code",2) }.isFailure)
    }
    @Test fun authorizationExchangeStoresRenewableCloudCredentialsAndDoesNotRepeatTheCode() = runBlocking {
        MockWebServer().use { server ->
            server.start();val store=MemoryTokens()
            val auth=XalAuthenticator(store,OkHttpClient.Builder().proxy(Proxy.NO_PROXY).callTimeout(3,TimeUnit.SECONDS).build(),
                AuthEndpoints(tokenUrl=server.url("/token").toString()),clockMs={1000})
            val request=auth.beginBrowserLogin();request.redirect(callback(request),1000)
            server.enqueue(MockResponse().setBody("""{"access_token":"new-access","refresh_token":"refresh","expires_in":3600}"""))
            assertEquals("t=new-access",auth.completeBrowserLogin(request,"code").getOrThrow())
            assertTrue(auth.supportsConsoleTransfer());assertEquals(XalAuthenticator.XCLOUD_CLIENT_ID,store.token!!.clientId)
            assertEquals("refresh",store.token!!.refreshToken)
            val body=server.takeRequest().body.readUtf8();assertTrue(body.contains("code_verifier="));assertFalse(body.contains("client_secret"))
            assertTrue(auth.completeBrowserLogin(request,"code").isFailure);assertEquals(1,server.requestCount)
        }
    }
    @Test fun providerFailureAndIncompleteTokenResponsesPreserveTheSavedAccount() = runBlocking {
        MockWebServer().use { server ->
            server.start();val old=OAuthToken("t=old","old-refresh",999999,XalAuthenticator.XCLOUD_CLIENT_ID)
            val store=MemoryTokens(old)
            val auth=XalAuthenticator(store,OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build(),
                AuthEndpoints(tokenUrl=server.url("/token").toString()),clockMs={1000})
            for(response in listOf(MockResponse().setResponseCode(502).setBody("private body"),
                MockResponse().setBody("""{"access_token":"partial","expires_in":3600}"""))) {
                val request=auth.beginBrowserLogin();request.redirect(callback(request),1000);server.enqueue(response)
                val result=auth.completeBrowserLogin(request,"code")
                assertTrue(result.isFailure);assertEquals(old,store.token)
                assertFalse(result.exceptionOrNull()!!.message.orEmpty().contains("private body"))
            }
        }
    }
}
