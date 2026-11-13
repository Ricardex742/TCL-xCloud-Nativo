package com.xcloud.tclnative

import com.xcloud.tclnative.auth.*
import com.xcloud.tclnative.net.CloudHttpException
import com.xcloud.tclnative.settings.RegionBypass
import com.xcloud.tclnative.signaling.XCloudSignalingClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.net.Proxy
import java.util.concurrent.TimeUnit

class RegionBypassAuthTest {
    private lateinit var server: MockWebServer
    private lateinit var tokens: MemoryTokens
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).callTimeout(3, TimeUnit.SECONDS).build()
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        tokens = MemoryTokens(OAuthToken("t=access", "refresh", 3_600_000, XalAuthenticator.XCLOUD_CLIENT_ID))
    }
    @After fun cleanup() { server.shutdown() }
    private fun auth(mode: () -> RegionBypass = { RegionBypass.OFF }) = XalAuthenticator(tokens, client,
        AuthEndpoints(server.url("/device").toString(), server.url("/token").toString(), server.url("/user").toString(),
            server.url("/xsts").toString(), server.url("/cloud").toString(), server.url("/transfer").toString(), server.url("/profile").toString()),
        clockMs = { 100 }, regionBypass = mode)
    private fun enqueue(body: String, status: Int = 200) { server.enqueue(MockResponse().setResponseCode(status).setBody(body)) }
    private fun exchange(status: Int = 200) {
        enqueue("""{"Token":"user","DisplayClaims":{"xui":[{"uhs":"hash"}]}}""")
        enqueue("""{"Token":"xsts"}""")
        enqueue(if (status == 200) """{"gsToken":"cloud","offeringSettings":{"regions":[{"name":"available-region","isDefault":true,"baseUri":"${server.url("/")}"}]}}"""
            else """{"code":"RegionNotSupported","private":"must-not-appear"}""", status)
    }
    @Test fun disabledIsTheDefaultAndDoesNotSendAnyForwardedHeader() = runBlocking {
        exchange()
        assertEquals(RegionBypass.OFF, auth().authenticateToXCloud().getOrThrow().regionBypass)
        repeat(3) { assertNull(server.takeRequest().getHeader("X-Forwarded-For")) }
    }
    @Test fun eachCountryAffectsOnlyCloudLoginAndNeverMicrosoftProfileTransferOrCatalog() = runBlocking {
        for (mode in RegionBypass.entries.filter { it != RegionBypass.OFF }) {
            val login = auth { mode }
            tokens.write(OAuthToken("t=access", "refresh", 0, XalAuthenticator.XCLOUD_CLIENT_ID))
            enqueue("""{"user_code":"USER","device_code":"DEVICE","verification_uri":"https://microsoft.com/link","expires_in":120}""")
            login.requestDeviceCodeQr().getOrThrow()
            enqueue("""{"access_token":"renewed","expires_in":3600}""")
            exchange()
            val session = login.authenticateToXCloud().getOrThrow()
            assertEquals(mode, session.regionBypass)
            enqueue("""{"Token":"profile-grant","DisplayClaims":{"xui":[{"uhs":"hash"}]}}""")
            enqueue("""{"profileUsers":[{"settings":[{"id":"Gamertag","value":"Demo"}]}]}""")
            login.loadXboxProfile().getOrThrow()
            enqueue("""{"access_token":"transfer"}""")
            login.getConsoleTransferToken()
            enqueue("""{"results":[]}""")
            assertTrue(XCloudSignalingClient(session, client).fetchCatalogGames().isEmpty())
            repeat(9) {
                val request = server.takeRequest()
                if (request.path == "/cloud") {
                    assertEquals(mode.forwardedFor, request.getHeader("X-Forwarded-For"))
                    val payload = JSONObject(request.body.readUtf8())
                    assertEquals("xgpuweb", payload.getString("offeringId"))
                    assertEquals("xsts", payload.getString("token"))
                } else assertNull("Header leaked to ${request.path}", request.getHeader("X-Forwarded-For"))
            }
        }
    }
    @Test fun rejectionDoesNotSilentlyRetryAnotherCountryOrEraseTheSavedLogin() = runBlocking {
        val before = tokens.read(); exchange(403)
        val error = auth { RegionBypass.BRAZIL }.authenticateToXCloud().exceptionOrNull() as CloudHttpException
        assertEquals("AUTH_GSSV", error.stage); assertEquals(403, error.status)
        assertFalse(error.message!!.contains("must-not-appear")); assertEquals(before, tokens.read())
        assertEquals(3, server.requestCount)
        repeat(2) { server.takeRequest() }
        assertEquals(RegionBypass.BRAZIL.forwardedFor, server.takeRequest().getHeader("X-Forwarded-For"))
    }
    @Test fun forwardedHeaderCannotFollowARedirectAwayFromTheLoginRequest() = runBlocking {
        enqueue("""{"Token":"user","DisplayClaims":{"xui":[{"uhs":"hash"}]}}""")
        enqueue("""{"Token":"xsts"}""")
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/token")))
        val error = auth { RegionBypass.JAPAN }.authenticateToXCloud().exceptionOrNull() as CloudHttpException
        assertEquals(302, error.status); assertEquals(3, server.requestCount)
        repeat(2) { server.takeRequest() }
        assertEquals("/cloud", server.takeRequest().path)
    }
    @Test fun selectionIsCapturedOncePerLoginAndDiagnosticsDescribeTheActualSessionRequest() = runBlocking {
        var mode = RegionBypass.UNITED_STATES; var reads = 0
        val login = auth { reads++; mode }
        exchange()
        val first = login.authenticateToXCloud(onProgress = { mode = RegionBypass.POLAND }).getOrThrow()
        repeat(2) { server.takeRequest() }
        assertEquals(RegionBypass.UNITED_STATES.forwardedFor, server.takeRequest().getHeader("X-Forwarded-For"))
        assertEquals(1, reads)
        val text = XCloudSignalingClient(first, client).profileDiagnostics()
        assertTrue(text.contains(RegionBypass.UNITED_STATES.label)); assertFalse(text.contains(RegionBypass.UNITED_STATES.forwardedFor!!))
        exchange()
        assertEquals(RegionBypass.POLAND, login.authenticateToXCloud().getOrThrow().regionBypass)
        repeat(2) { server.takeRequest() }
        assertEquals(RegionBypass.POLAND.forwardedFor, server.takeRequest().getHeader("X-Forwarded-For")); assertEquals(2, reads)
    }
}
