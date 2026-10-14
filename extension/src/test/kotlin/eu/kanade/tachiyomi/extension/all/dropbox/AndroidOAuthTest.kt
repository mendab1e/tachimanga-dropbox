package eu.kanade.tachiyomi.extension.all.dropbox

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AndroidOAuthTest {
    private lateinit var server: MockWebServer
    private val values = ConcurrentHashMap<String, String>()
    private val settings = object : Settings {
        override fun get(key: String) = values[key].orEmpty()
        override fun put(values: Map<String, String>) { this@AndroidOAuthTest.values.putAll(values) }
    }
    private lateinit var api: DropboxApi
    private fun newApi() = DropboxApi(OkHttpClient(), settings, server.url("/2/").toString(),
        server.url("/2/").toString(), server.url("/oauth2/token").toString(), "bundled-key")
    @Before fun start() {
        server = MockWebServer().apply { start() }
        api = newApi()
    }
    @After fun stop() { server.shutdown() }
    private fun state() = values.getValue("callback_state")
    private fun token() = MockResponse().setBody("""{"access_token":"new-token","refresh_token":"new-refresh","expires_in":14400}""")
    private fun failure(block: () -> Unit) {
        try { block(); fail("Expected IOException") } catch (_: IOException) { }
    }
    private fun assertCallbackCleared() {
        listOf("callback_state", "callback_verifier", "callback_expires_at", "callback_claim").forEach {
            assertEquals("", settings.get(it))
        }
    }
    @Test fun separateCallbackPkcePreservesTheManualIosLink() {
        val manual = api.signInLink()
        val manualVerifier = settings.get("verifier")
        val link = api.beginAndroidSignIn().toHttpUrl()
        assertEquals(OAuthContract.REDIRECT_URI, link.queryParameter("redirect_uri"))
        assertEquals("bundled-key", link.queryParameter("client_id"))
        assertEquals("S256", link.queryParameter("code_challenge_method"))
        assertEquals("offline", link.queryParameter("token_access_type"))
        assertEquals("files.metadata.read files.content.read", link.queryParameter("scope"))
        assertEquals(state(), link.queryParameter("state"))
        assertEquals(64, state().length)
        val verifier = settings.get("callback_verifier")
        assertNotEquals(manualVerifier, verifier)
        assertEquals(verifier.toByteArray().toByteString().sha256().base64Url().trimEnd('='), link.queryParameter("code_challenge"))
        assertFalse(link.toString().contains(verifier))
        assertFalse(link.toString().contains("client_secret"))
        assertEquals(manual, newApi().signInLink())
        assertEquals(manualVerifier, settings.get("verifier"))
        assertNull(manual.toHttpUrl().queryParameter("redirect_uri"))
        assertEquals(0, server.requestCount)
    }
    @Test fun callbackExchangesCodeWithMatchingRedirectAndClearsBothFlows() {
        api.signInLink()
        api.beginAndroidSignIn()
        val state = state()
        val verifier = settings.get("callback_verifier")
        server.enqueue(token())
        api.completeAndroidSignIn(state, "user-code")
        val form = ("https://example.com/?" + server.takeRequest().body.readUtf8()).toHttpUrl()
        assertEquals("authorization_code", form.queryParameter("grant_type"))
        assertEquals(OAuthContract.REDIRECT_URI, form.queryParameter("redirect_uri"))
        assertEquals("bundled-key", form.queryParameter("client_id"))
        assertEquals(verifier, form.queryParameter("code_verifier"))
        assertEquals("user-code", form.queryParameter("code"))
        assertNull(form.queryParameter("client_secret"))
        assertTrue(api.isConnected())
        assertEquals("new-refresh", settings.get("refresh_token"))
        assertEquals("", settings.get("authorization_url"))
        assertEquals("", settings.get("verifier"))
        assertEquals("", settings.get("auth_code"))
        assertCallbackCleared()
        failure { api.completeAndroidSignIn(state, "user-code") }
        assertEquals(1, server.requestCount)
    }
    @Test fun rejectsWrongStateAndInvalidCodeWithoutChangingValidPendingFlow() {
        api.beginAndroidSignIn()
        val state = state()
        failure { api.completeAndroidSignIn("wrong", "user-code") }
        failure { api.completeAndroidSignIn(state, "code\nsecret") }
        assertEquals(state, state())
        assertEquals("", settings.get("callback_claim"))
        assertEquals(0, server.requestCount)
    }
    @Test fun expiredCallbackMakesNoTokenRequest() {
        api.beginAndroidSignIn()
        values["callback_expires_at"] = "0"
        failure { api.completeAndroidSignIn(state(), "user-code") }
        assertEquals(0, server.requestCount)
    }
    @Test fun startingAgainRejectsOlderCallback() {
        api.beginAndroidSignIn()
        val old = state()
        api.beginAndroidSignIn()
        assertNotEquals(old, state())
        failure { api.completeAndroidSignIn(old, "user-code") }
        api.cancelAndroidSignIn(old)
        assertTrue(state().isNotBlank())
        assertEquals(0, server.requestCount)
    }
    @Test fun cancellationKeepsManualFallback() {
        val manual = api.signInLink()
        api.beginAndroidSignIn()
        api.cancelAndroidSignIn(state())
        assertCallbackCleared()
        assertEquals(manual, api.signInLink())
    }
    @Test fun disconnectAndAppChangeAndManualEntryRejectPendingCallbacks() {
        val changes: List<(DropboxApi) -> Unit> = listOf(
            { it.disconnect() }, { it.updateSetting("app_key", "replacement-key") },
            { it.updateSetting("auth_code", "manual-code") },
        )
        for (change in changes) {
            api.beginAndroidSignIn()
            val state = state()
            change(newApi())
            failure { api.completeAndroidSignIn(state, "user-code") }
            assertCallbackCleared()
        }
        assertEquals(0, server.requestCount)
    }
    @Test fun failureClearsCallbackButKeepsManualLinkAndHidesResponse() {
        val manual = api.signInLink()
        api.beginAndroidSignIn()
        server.enqueue(MockResponse().setResponseCode(400).setBody("secret-token user-code"))
        try { api.completeAndroidSignIn(state(), "user-code"); fail() } catch (error: IOException) {
            assertFalse(error.message!!.contains("secret-token"))
            assertFalse(error.message!!.contains("user-code"))
            assertNull(error.cause)
        }
        assertFalse(api.isConnected())
        assertCallbackCleared()
        assertEquals(manual, api.signInLink())
    }
    @Test fun callbackRequiresOfflineAccessAndDoesNotSavePartialTokens() {
        api.beginAndroidSignIn()
        server.enqueue(MockResponse().setBody("""{"access_token":"new-token","expires_in":14400}"""))
        failure { api.completeAndroidSignIn(state(), "user-code") }
        assertEquals("", settings.get("access_token"))
        assertFalse(api.isConnected())
        assertCallbackCleared()
    }
    @Test fun connectedAccountCannotStartAnotherCallback() {
        values["refresh_token"] = "existing-refresh"
        failure { api.beginAndroidSignIn() }
        assertEquals("existing-refresh", settings.get("refresh_token"))
    }
    @Test fun pendingCallbackCannotRestoreCredentialsAfterAnotherSourceDisconnects() {
        pendingExchange { it.disconnect() }
        assertEquals("", settings.get("refresh_token"))
        assertCallbackCleared()
    }
    @Test fun pendingCallbackCannotRestoreCredentialsAfterAppChanges() {
        pendingExchange { it.updateSetting("app_key", "replacement-key") }
        assertEquals("replacement-key", settings.get("app_key"))
        assertEquals("", settings.get("refresh_token"))
    }
    @Test fun pendingCallbackCannotEraseNewAuthorization() {
        var replacement = ""
        pendingExchange { it.beginAndroidSignIn(); replacement = state() }
        assertEquals(replacement, state())
        assertEquals("", settings.get("callback_claim"))
        assertEquals("", settings.get("refresh_token"))
    }
    @Test fun newCallbackCannotBeOverwrittenByAnOlderManualExchange() {
        var replacement = ""
        pendingExchange(callback = false) { it.beginAndroidSignIn(); replacement = state() }
        assertEquals(replacement, state())
        assertEquals("", settings.get("refresh_token"))
        assertEquals("", settings.get("auth_code"))
    }
    private fun pendingExchange(callback: Boolean = true, change: (DropboxApi) -> Unit) {
        if (callback) api.beginAndroidSignIn() else {
            api.signInLink()
            api.updateSetting("auth_code", "manual-code")
        }
        val state = if (callback) state() else ""
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                started.countDown()
                if (!release.await(5, TimeUnit.SECONDS)) return MockResponse().setResponseCode(500)
                return token()
            }
        }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val future = workers.submit {
                if (callback) api.completeAndroidSignIn(state, "user-code") else api.metadata("id:book")
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            // A recreated source shares preferences, but has a different API instance/session.
            workers.submit { change(newApi()) }.get(2, TimeUnit.SECONDS)
            release.countDown()
            try { future.get(5, TimeUnit.SECONDS); fail("Stale callback restored credentials") } catch (error: ExecutionException) {
                assertTrue(error.cause is IOException)
            }
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }
    @Test fun callbackParserAcceptsOnlyTheExactRouteAndSingleMatchingState() {
        val base = OAuthContract.REDIRECT_URI
        assertEquals("code_123", OAuthContract.callbackCode("$base?state=expected&code=code_123", "expected"))
        listOf(
            "$base?state=wrong&code=code", "$base?state=expected&state=expected&code=code",
            "$base?state=expected&code=code&code=other", "$base?state=expected&code=code#fragment",
            "dropbox-manga://attacker/callback?state=expected&code=code",
            "dropbox-manga://oauth/other?state=expected&code=code",
            "dropbox-manga://user@oauth/callback?state=expected&code=code",
            "dropbox-manga://oauth:123/callback?state=expected&code=code",
            "$base?state=expected&code=%0Asecret", "$base?state=expected&code=%XX",
            "$base?state=expected&code=", "$base?state=expected&code=code&error=access_denied",
            "$base?state=expected&error=unknown", "$base?state=expected&code=" + "x".repeat(4097),
        ).forEach { assertNull(it.take(120), OAuthContract.callbackCode(it, "expected")) }
        assertNull(OAuthContract.callbackCode("$base?state=expected&code=code", ""))
        assertEquals("", OAuthContract.callbackCode("$base?state=expected&error=access_denied", "expected"))
    }
}
