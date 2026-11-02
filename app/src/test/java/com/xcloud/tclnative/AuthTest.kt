package com.xcloud.tclnative

import com.xcloud.tclnative.auth.*
import com.xcloud.tclnative.net.CloudHttpException
import com.xcloud.tclnative.net.awaitResponse
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.Proxy
import java.util.concurrent.TimeUnit

class MemoryTokens(var token: OAuthToken? = null) : OAuthTokenStore {
    override fun read() = token
    override fun write(token: OAuthToken) { this.token = token }
    override fun clear() { token = null }
}

class AuthTest {
    private lateinit var server: MockWebServer
    private lateinit var store: MemoryTokens
    private var now = 0L
    private val waits = mutableListOf<Long>()
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).callTimeout(3, TimeUnit.SECONDS).build()
    @Before fun setup() { server = MockWebServer().apply { start() }; store = MemoryTokens(); now = 0; waits.clear() }
    @After fun cleanup() { server.shutdown() }
    private fun auth(pause: suspend (Long) -> Unit = { waits.add(it); now += it }): XalAuthenticator =
        XalAuthenticator(store, client, AuthEndpoints(server.url("/device").toString(), server.url("/token").toString(),
            server.url("/user").toString(), server.url("/xsts").toString(), server.url("/cloud").toString(), server.url("/transfer").toString()), { now }, pause)
    private fun enqueue(body: String, code: Int = 200) { server.enqueue(MockResponse().setResponseCode(code).setBody(body)) }
    private fun exchange() {
        enqueue("""{"Token":"user-token","DisplayClaims":{"xui":[{"uhs":"hash"}]}}""")
        enqueue("""{"Token":"xsts-token"}""")
        enqueue("""{"gsToken":"cloud-token","offeringSettings":{"regions":[{"isDefault":true,"baseUri":"https://region.example"}]}}""")
    }
    private fun fresh() = OAuthToken("t=access", "refresh", 3_600_000, XalAuthenticator.DEVICE_CLIENT_ID)
    private fun info(expires: Long = 100_000) = DeviceCodeInfo("USER", "DEVICE", "https://microsoft.com/link", null, 1, expires)

    @Test fun exchangePreservesRefreshTokenAndSendsRawXsts() = runBlocking {
        store.token = fresh(); exchange()
        val progress = mutableListOf<String>()
        val session = auth().authenticateToXCloud("t=access") { progress.add(it) }.getOrThrow()
        assertEquals("cloud-token", session.gssvToken)
        assertEquals(fresh(), store.token)
        assertEquals("/user", server.takeRequest().path)
        assertEquals("/xsts", server.takeRequest().path)
        val request = server.takeRequest()
        assertEquals("xsts-token", JSONObject(request.body.readUtf8()).getString("token"))
        assertTrue(progress.first().startsWith("AUTH_XBL")); assertTrue(progress.last().startsWith("AUTH_GSSV"))
    }
    @Test fun savingTheSameTicketDoesNotEraseRefreshOrExpiry() {
        store.token = fresh()
        auth().saveTokens("access")
        assertEquals(fresh(), store.token)
    }
    @Test fun expiredTokenIsRefreshedWithItsOriginalClientAndPreservesRefreshIfNotRotated() = runBlocking {
        store.token = fresh().copy(expiresAtMs = 1); now = 100
        enqueue("""{"access_token":"new","expires_in":3600}"""); exchange()
        auth().authenticateToXCloud("t=access").getOrThrow()
        val refresh = server.takeRequest()
        assertEquals("/transfer", refresh.path)
        val form = refresh.body.readUtf8()
        assertTrue(form.contains("grant_type=refresh_token"))
        assertTrue(form.contains("client_id=${XalAuthenticator.DEVICE_CLIENT_ID}"))
        assertTrue(form.contains("refresh_token=refresh"))
        assertEquals("t=new", store.token?.ticket); assertEquals("refresh", store.token?.refreshToken)
        assertEquals(3_600_100L, store.token?.expiresAtMs)
    }
    @Test fun unauthorizedUserTokenRefreshesAndRetriesOnce() = runBlocking {
        store.token = fresh()
        enqueue("{}", 401); enqueue("""{"access_token":"new","refresh_token":"rotated","expires_in":3600}"""); exchange()
        assertTrue(auth().authenticateToXCloud("t=access").isSuccess)
        assertEquals("rotated", store.token?.refreshToken)
        assertEquals(5, server.requestCount)
    }
    @Test fun revokedRefreshRequiresANewLoginWithoutContinuingToXbox() = runBlocking {
        store.token = fresh().copy(expiresAtMs = 1); now = 100
        enqueue("""{"error":"invalid_grant","refresh_token":"do-not-expose"}""", 400)
        val failure = auth().authenticateToXCloud("t=access").exceptionOrNull()
        assertTrue(failure is AuthenticationRequiredException)
        assertFalse(failure!!.message!!.contains("do-not-expose"))
        assertEquals(1, server.requestCount); assertEquals("/transfer", server.takeRequest().path)
    }
    @Test fun expiredLegacyTicketWithoutRefreshRequiresANewLogin() = runBlocking {
        store.token = fresh().copy(refreshToken = null)
        enqueue("{}", 401)
        assertTrue(auth().authenticateToXCloud("t=access").exceptionOrNull() is AuthenticationRequiredException)
        assertEquals(1, server.requestCount)
    }
    @Test fun temporaryHttpFailureRetainsStoredCredentials() = runBlocking {
        store.token = fresh(); enqueue("{}", 503)
        val failure = auth().authenticateToXCloud("t=access").exceptionOrNull() as CloudHttpException
        assertEquals("AUTH_XBL", failure.stage); assertEquals(503, failure.status)
        assertEquals(fresh(), store.token)
    }
    @Test fun xstsErrorExposesCodeAndStageWithoutLeakingTokens() = runBlocking {
        store.token = fresh()
        enqueue("""{"Token":"user-token","DisplayClaims":{"xui":[{"uhs":"hash"}]}}""")
        enqueue("""{"XErr":2148916233,"Token":"do-not-expose","Message":"sensitive body"}""", 401)
        val failure = auth().authenticateToXCloud("t=access").exceptionOrNull()!!
        assertTrue(failure.message!!.contains("AUTH_XSTS")); assertTrue(failure.message!!.contains("2148916233"))
        assertFalse(failure.message!!.contains("do-not-expose")); assertFalse(failure.message!!.contains("sensitive body"))
        assertEquals(fresh(), store.token)
    }
    @Test fun qrUsesProviderUriAndDeadlineWithoutInventingAnApprovalUrl() = runBlocking {
        enqueue("""{"user_code":"U","device_code":"D","verification_uri":"https://microsoft.com/link","interval":4,"expires_in":90}""")
        val info = auth().requestDeviceCodeQr().getOrThrow()
        assertEquals("https://microsoft.com/link", info.verificationUri)
        assertNull(info.verificationUriComplete); assertEquals(90_000L, info.expiresAtMs); assertEquals(4, info.intervalSeconds)
    }
    @Test fun slowDownIncreasesEverySubsequentPollingInterval() = runBlocking {
        enqueue("""{"error":"slow_down"}""", 400)
        enqueue("""{"error":"authorization_pending"}""", 400)
        enqueue("""{"access_token":"qr-access","refresh_token":"qr-refresh","expires_in":3600}""")
        assertEquals("t=qr-access", auth().pollForDeviceCodeLogin(info()).getOrThrow())
        assertEquals(listOf(1000L, 6000L, 6000L), waits)
        assertEquals("qr-refresh", store.token?.refreshToken)
        assertEquals(XalAuthenticator.XCLOUD_CLIENT_ID, store.token?.clientId)
    }
    @Test fun deviceExpiryStopsPollingAtTheServerDeadline() = runBlocking {
        repeat(2) { enqueue("""{"error":"authorization_pending"}""", 400) }
        assertTrue(auth().pollForDeviceCodeLogin(info(2500)).exceptionOrNull() is AuthenticationRequiredException)
        assertEquals(listOf(1000L, 1000L, 500L), waits); assertEquals(2, server.requestCount)
    }
    @Test fun deniedApprovalStopsImmediatelyAndKeepsAnExistingLogin() = runBlocking {
        store.token = fresh(); enqueue("""{"error":"authorization_declined"}""", 400)
        assertTrue(auth().pollForDeviceCodeLogin(info()).exceptionOrNull() is AuthenticationRequiredException)
        assertEquals(1, server.requestCount); assertEquals(fresh(), store.token)
    }
    @Test fun cancellationIsNotConvertedIntoALoginFailure() = runBlocking {
        try {
            auth { throw CancellationException("cancelled") }.pollForDeviceCodeLogin(info())
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
        assertEquals(0, server.requestCount)
    }
    @Test fun cancellingACoroutineCancelsTheInflightSocketRequest() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val call = client.newCall(Request.Builder().url(server.url("/pending")).build())
        val job = launch { call.awaitResponse().close() }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        withTimeout(1_000) { job.cancelAndJoin() }
        assertTrue(call.isCanceled())
    }

    @Test fun consoleTokenUsesTransferScopeAndXcloudClientAndPreservesLogin() = runBlocking {
        store.token = fresh().copy(clientId = XalAuthenticator.XCLOUD_CLIENT_ID)
        enqueue("""{"access_token":"console-token","refresh_token":"rotated"}""")
        assertEquals("console-token", auth().getConsoleTransferToken())
        val request = server.takeRequest()
        assertEquals("/transfer", request.path)
        val form = request.body.readUtf8()
        assertTrue(form.contains("client_id=${XalAuthenticator.XCLOUD_CLIENT_ID}"))
        assertTrue(form.contains("grant_type=refresh_token"))
        assertTrue(form.contains("PURPOSE_XBOX_CLOUD_CONSOLE_TRANSFER_TOKEN"))
        assertEquals(fresh().copy(refreshToken = "rotated", clientId = XalAuthenticator.XCLOUD_CLIENT_ID), store.token)
    }
    @Test fun legacyBrowserLoginWithoutRefreshCannotInventAConsoleToken() = runBlocking {
        store.token = fresh().copy(refreshToken = null)
        try { auth().getConsoleTransferToken(); fail("Missing transfer credentials were ignored") }
        catch (error: AuthenticationRequiredException) { assertTrue(error.message!!.contains("QR Code")) }
        assertEquals(0, server.requestCount)
    }
    @Test fun revokedConsoleCredentialRequiresLoginAndDoesNotExposeResponse() = runBlocking {
        store.token = fresh().copy(clientId = XalAuthenticator.XCLOUD_CLIENT_ID)
        enqueue("""{"error":"invalid_grant","access_token":"do-not-expose"}""", 400)
        try { auth().getConsoleTransferToken(); fail("Revoked credentials were accepted") }
        catch (error: AuthenticationRequiredException) {
            assertTrue(error.message!!.contains("CONSOLE_TOKEN"))
            assertFalse(error.message!!.contains("do-not-expose"))
        }
    }

    @Test fun savedLegacyBrowserTicketIsNormalizedBeforeXboxAuthentication() = runBlocking {
        store.token = OAuthToken("legacy-access", null, null, XalAuthenticator.BROWSER_CLIENT_ID)
        exchange()
        auth().authenticateToXCloud("legacy-access").getOrThrow()
        val properties = JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("Properties")
        assertEquals("t=legacy-access", properties.getString("RpsTicket"))
    }

    @Test fun qrStartsTheXcloudMsalFlowWithOfflineAccess() = runBlocking {
        enqueue("""{"user_code":"U","device_code":"D","verification_uri":"https://microsoft.com/link","interval":1,"expires_in":90}""")
        auth().requestDeviceCodeQr().getOrThrow()
        val request = server.takeRequest()
        val form = request.body.readUtf8()
        assertEquals("/device", request.path)
        assertTrue(form.contains("client_id=${XalAuthenticator.XCLOUD_CLIENT_ID}"))
        assertTrue(form.contains("xboxlive.signin"))
        assertTrue(form.contains("offline_access"))
        assertFalse(form.contains("MBI_SSL"))
        val endpoints = AuthEndpoints()
        assertEquals("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode", endpoints.deviceCodeUrl)
        assertEquals("https://login.microsoftonline.com/consumers/oauth2/v2.0/token", endpoints.tokenUrl)
        assertEquals("https://login.live.com/oauth20_token.srf", endpoints.transferTokenUrl)
    }
    @Test fun oldDeviceLoginCannotRepeatTheInvalidScopeRequest() = runBlocking {
        store.token = fresh()
        assertFalse(auth().supportsConsoleTransfer())
        try { auth().getConsoleTransferToken(); fail("Legacy OAuth client was reused for transfer scope") }
        catch (error: AuthenticationRequiredException) { assertTrue(error.message!!.contains("QR Code desta versão")) }
        assertEquals(0, server.requestCount)
        assertEquals(fresh(), store.token)
    }
    @Test fun msalRefreshUsesV2EndpointAndMsalScope() = runBlocking {
        store.token = fresh().copy(clientId = XalAuthenticator.XCLOUD_CLIENT_ID, expiresAtMs = 1); now = 100
        enqueue("""{"access_token":"new","expires_in":3600}"""); exchange()
        auth().authenticateToXCloud("t=access").getOrThrow()
        val request = server.takeRequest()
        assertEquals("/token", request.path)
        val form = request.body.readUtf8()
        assertTrue(form.contains("offline_access")); assertFalse(form.contains("MBI_SSL"))
        assertTrue(form.contains("client_id=${XalAuthenticator.XCLOUD_CLIENT_ID}"))
    }
    @Test fun invalidTransferScopeIsActionableAndNeverTriggersClientGuessing() = runBlocking {
        store.token = fresh().copy(clientId = XalAuthenticator.XCLOUD_CLIENT_ID)
        enqueue("""{"error":"invalid_scope","access_token":"do-not-expose"}""", 400)
        try { auth().getConsoleTransferToken(); fail("Invalid scope was accepted") }
        catch (error: AuthenticationRequiredException) {
            assertTrue(error.message!!.contains("QR Code"))
            assertFalse(error.message!!.contains("do-not-expose"))
        }
        assertEquals(1, server.requestCount)
        assertEquals("/transfer", server.takeRequest().path)
    }
    @Test fun newQrLoginCanAuthenticateAndRequestConsoleTransferWithoutMixingClients() = runBlocking {
        enqueue("""{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}""")
        val login = auth()
        val ticket = login.pollForDeviceCodeLogin(info()).getOrThrow()
        val poll = server.takeRequest()
        assertEquals("/token", poll.path)
        assertTrue(poll.body.readUtf8().contains("client_id=${XalAuthenticator.XCLOUD_CLIENT_ID}"))
        exchange()
        login.authenticateToXCloud(ticket).getOrThrow()
        val user = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("d=new-access", user.getJSONObject("Properties").getString("RpsTicket"))
        server.takeRequest(); server.takeRequest()
        enqueue("""{"access_token":"transfer","refresh_token":"rotated"}""")
        assertEquals("transfer", login.getConsoleTransferToken())
        val transfer = server.takeRequest()
        assertEquals("/transfer", transfer.path)
        val form = transfer.body.readUtf8()
        assertTrue(form.contains("client_id=${XalAuthenticator.XCLOUD_CLIENT_ID}"))
        assertTrue(form.contains("PURPOSE_XBOX_CLOUD_CONSOLE_TRANSFER_TOKEN"))
        assertTrue(form.contains("refresh_token=new-refresh"))
        assertTrue(login.supportsConsoleTransfer())
        assertEquals("rotated", store.token?.refreshToken)
    }

    @Test fun completedMsalLoginReplacesTheLegacyClientIdentityEvenIfTheTicketMatches() = runBlocking {
        store.token = fresh()
        enqueue("""{"access_token":"access","refresh_token":"msal-refresh","expires_in":3600}""")
        auth().pollForDeviceCodeLogin(info()).getOrThrow()
        assertEquals(XalAuthenticator.XCLOUD_CLIENT_ID, store.token?.clientId)
        assertEquals("msal-refresh", store.token?.refreshToken)
        assertTrue(auth().supportsConsoleTransfer())
    }
}
