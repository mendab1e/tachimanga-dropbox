package eu.kanade.tachiyomi.extension.all.dropbox

import java.io.IOException
import java.security.SecureRandom
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.toByteString

/** Owns credentials and PKCE state. The client is the API's isolated private transport. */
internal class DropboxAuth(
    private val client: OkHttpClient,
    private val settings: Settings,
    private val tokenUrl: String,
    private val defaultAppKey: String,
) {
    // Only background token exchanges use tokenLock. UI actions never wait for network I/O.
    private val tokenLock = Any()
    // Source instances can be recreated while the Android callback is in flight.
    private val stateLock = AUTH_STATE_LOCK
    private var session = 0L

    fun disconnect() = synchronized(stateLock) {
        session++
        settings.put(emptyCredentials())
    }

    fun updateSetting(key: String, value: String) = synchronized(stateLock) {
        if (key == SettingKey.APP_KEY && value.trim().ifBlank { defaultAppKey } != appKey()) {
            session++
            settings.put(emptyCredentials() + (key to value))
        } else {
            if (key == SettingKey.AUTH_CODE) session++
            settings.put((if (key == SettingKey.AUTH_CODE) emptyCallback() else emptyMap()) + (key to value))
        }
    }

    private fun emptyCallback() = listOf(
        SettingKey.CALLBACK_STATE, SettingKey.CALLBACK_VERIFIER, SettingKey.CALLBACK_EXPIRES_AT, SettingKey.CALLBACK_CLAIM,
    ).associateWith { "" }
    private fun emptyManual() = listOf(
        SettingKey.AUTH_CODE, SettingKey.VERIFIER, SettingKey.AUTHORIZATION_URL,
    ).associateWith { "" }
    private fun emptyCredentials() = listOf(
        SettingKey.ACCESS_TOKEN, SettingKey.REFRESH_TOKEN, SettingKey.EXPIRES_AT,
    ).associateWith { "" } + emptyManual() + emptyCallback()
    private fun appKey() = settings.get(SettingKey.APP_KEY).trim().ifBlank { defaultAppKey }
    fun currentSession() = synchronized(stateLock) { session }
    fun checkSession(expected: Long) = synchronized(stateLock) {
        if (session != expected) connectionChanged()
    }
    private fun connectionChanged(): Nothing = throw IOException("Dropbox connection changed. Browse again after connecting.")

    /** Reuse the pending PKCE link across settings/source-list reloads. */
    fun signInLink(): String = synchronized(stateLock) {
        if (appKey().isBlank()) return@synchronized ""
        val prepared = settings.get(SettingKey.AUTHORIZATION_URL)
        if (prepared.isNotBlank() && settings.get(SettingKey.VERIFIER).isNotBlank()) return@synchronized prepared
        if (settings.get(SettingKey.REFRESH_TOKEN).isNotBlank() && settings.get(SettingKey.AUTH_CODE).isBlank()) return@synchronized ""
        authorizationUrl()
    }

    fun authorizationUrl(): String = synchronized(stateLock) {
        val key = appKey()
        if (key.isEmpty()) throw IOException("Set your Dropbox app key in the source settings first.")
        val verifier = randomValue()
        session++
        settings.put(emptyCallback() + mapOf(SettingKey.VERIFIER to verifier, SettingKey.AUTH_CODE to ""))
        authorizationBuilder(key, verifier).build().toString().also { settings.put(mapOf(SettingKey.AUTHORIZATION_URL to it)) }
    }

    private fun randomValue() = ByteArray(32).apply { SecureRandom().nextBytes(this) }
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun authorizationBuilder(key: String, verifier: String) =
        "https://www.dropbox.com/oauth2/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", key)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("token_access_type", "offline")
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", verifier.toByteArray(Charsets.US_ASCII).toByteString().sha256().base64Url().trimEnd('='))
            .addQueryParameter("scope", "files.metadata.read files.content.read")

    fun isConnected(): Boolean = synchronized(stateLock) { settings.get(SettingKey.REFRESH_TOKEN).isNotBlank() }

    /** Separate PKCE state leaves the no-redirect manual link usable as a fallback. */
    fun beginAndroidSignIn(): String = synchronized(stateLock) {
        val key = appKey()
        if (key.isBlank()) throw IOException("Set your Dropbox app key in source settings first.")
        if (isConnected()) throw IOException("Disconnect Dropbox before connecting another account.")
        val state = randomValue()
        val verifier = randomValue()
        session++
        settings.put(mapOf(SettingKey.CALLBACK_STATE to state, SettingKey.CALLBACK_VERIFIER to verifier,
            SettingKey.CALLBACK_EXPIRES_AT to (System.currentTimeMillis() + OAuthContract.SESSION_LIFETIME_MS).toString(), SettingKey.CALLBACK_CLAIM to "", SettingKey.AUTH_CODE to ""))
        authorizationBuilder(key, verifier).addQueryParameter("redirect_uri", OAuthContract.REDIRECT_URI)
            .addQueryParameter("state", state).build().toString()
    }

    fun cancelAndroidSignIn(state: String) = synchronized(stateLock) {
        if (state.isNotBlank() && state == settings.get(SettingKey.CALLBACK_STATE)) {
            session++
            settings.put(emptyCallback())
        }
    }

    // Plain classes deliberately avoid generated toString() methods containing credentials.
    private class CallbackState(val state: String, val verifier: String, val expiresAt: Long, val claim: String) {
        fun validFor(expectedState: String) = expectedState.isNotBlank() && state == expectedState &&
            verifier.isNotBlank() && expiresAt > System.currentTimeMillis()
    }

    private fun callbackState() = CallbackState(
        settings.get(SettingKey.CALLBACK_STATE), settings.get(SettingKey.CALLBACK_VERIFIER),
        settings.get(SettingKey.CALLBACK_EXPIRES_AT).toLongOrNull() ?: 0, settings.get(SettingKey.CALLBACK_CLAIM),
    )

    private class PendingCallback(val appKey: String, val verifier: String, val session: Long)

    /** Runs in the reader's process. Neither verifier nor tokens cross the activity bridge. */
    fun completeAndroidSignIn(state: String, code: String) = synchronized(tokenLock) {
        val pending = synchronized(stateLock) {
            val callback = callbackState()
            if (!OAuthContract.validCode(code) || !callback.validFor(state) || callback.claim.isNotBlank()) {
                throw IOException("Dropbox sign-in session expired or changed. Start sign-in again in source settings.")
            }
            settings.put(mapOf(SettingKey.CALLBACK_CLAIM to CALLBACK_EXCHANGING))
            PendingCallback(appKey(), callback.verifier, session)
        }
        try {
            val result = exchangeCode(pending.appKey, code, pending.verifier, OAuthContract.REDIRECT_URI)
            synchronized(stateLock) {
                // Check persisted state too: a different source instance may have disconnected,
                // changed apps, entered a manual code, or started another authorization.
                val callback = callbackState()
                if (!callback.validFor(state) || callback.claim != CALLBACK_EXCHANGING || pending.verifier != callback.verifier ||
                    pending.appKey != appKey()) connectionChanged()
                saveToken(result, pending.session, clearCode = true)
            }
        } catch (error: IOException) {
            cancelAndroidSignIn(state)
            throw error
        }
    }

    private class Credentials(
        val appKey: String, val code: String, val verifier: String,
        val access: String, val expiresAt: Long, val refresh: String,
    )

    fun accessToken(expected: Long, force: Boolean = false, rejected: String = ""): String = synchronized(tokenLock) {
        val state = synchronized(stateLock) {
            checkSession(expected)
            Credentials(appKey(), settings.get(SettingKey.AUTH_CODE).trim(), settings.get(SettingKey.VERIFIER),
                settings.get(SettingKey.ACCESS_TOKEN), settings.get(SettingKey.EXPIRES_AT).toLongOrNull() ?: 0,
                settings.get(SettingKey.REFRESH_TOKEN).trim())
        }
        val key = state.appKey.trim()
        val code = state.code
        if (code.isNotEmpty()) {
            val verifier = state.verifier
            if (verifier.isBlank()) throw IOException("Open source settings and use the Dropbox sign-in link before pasting an authorization code.")
            val result = exchangeCode(key, code, verifier)
            return@synchronized synchronized(stateLock) {
                // A callback can supersede a manual exchange from a recreated source.
                if (appKey() != key || settings.get(SettingKey.AUTH_CODE).trim() != code || settings.get(SettingKey.VERIFIER) != verifier) connectionChanged()
                saveToken(result, expected, clearCode = true)
            }
        }
        val current = state.access
        val expires = state.expiresAt
        // Another request may already have renewed the rejected token.
        if (current.isNotBlank() && ((!force && expires > System.currentTimeMillis() + 60000) ||
                (force && current != rejected && expires > System.currentTimeMillis() + 60000))) return current
        val refresh = state.refresh
        if (refresh.isBlank() || key.isBlank()) throw IOException("Use the Dropbox sign-in link in source settings, paste the authorization code there, then browse again.")
        saveToken(token(FormBody.Builder().add("grant_type", "refresh_token")
            .add("client_id", key).add("refresh_token", refresh).build()), expected)
    }

    private fun exchangeCode(key: String, code: String, verifier: String, redirectUri: String? = null): Token {
        val form = FormBody.Builder().add("grant_type", "authorization_code")
            .add("client_id", key).add("code", code).add("code_verifier", verifier)
        redirectUri?.let { form.add("redirect_uri", it) }
        return token(form.build())
    }

    private class Token(val access: String, val refresh: String, val expires: Long)

    private fun token(body: FormBody): Token = client.newCall(Request.Builder().url(tokenUrl).post(body).build()).execute().use {
        if (!it.isSuccessful) throw IOException("Dropbox sign-in failed (HTTP ${it.code}). Reconnect Dropbox; check your app key and code.")
        try {
            val value = parseDropboxJson(it, 64L * 1024)
            fun credential(key: String): String {
                val field = value[key] ?: return ""
                require(field is JsonPrimitive && field.isString)
                return field.content
            }
            val access = credential("access_token")
            val refresh = credential("refresh_token")
            val expiry = value["expires_in"]?.jsonPrimitive?.long ?: 14400L
            require(access.isNotBlank() && access.all { char -> char.code in 33..126 })
            require(expiry in 1..31536000L)
            Token(access, refresh, expiry)
        } catch (_: Exception) {
            // Parser messages and causes can contain the complete token response.
            throw IOException("Invalid Dropbox sign-in response. Reconnect Dropbox.")
        }
    }
    private fun saveToken(value: Token, expected: Long, clearCode: Boolean = false): String = synchronized(stateLock) {
        checkSession(expected)
        if (clearCode && value.refresh.isBlank()) throw IOException("Dropbox did not return offline access. Reconnect Dropbox.")
        val fields = mutableMapOf(SettingKey.ACCESS_TOKEN to value.access, SettingKey.EXPIRES_AT to (System.currentTimeMillis() + value.expires * 1000).toString())
        value.refresh.takeIf { it.isNotEmpty() }?.let { fields[SettingKey.REFRESH_TOKEN] = it }
        if (clearCode) fields.putAll(emptyManual() + emptyCallback())
        settings.put(fields)
        value.access
    }

    companion object {
        private val AUTH_STATE_LOCK = Any()
        private const val CALLBACK_EXCHANGING = "exchanging"
    }
}
