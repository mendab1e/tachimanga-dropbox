package eu.kanade.tachiyomi.extension.all.dropbox

import java.io.IOException
import java.security.SecureRandom
import java.util.Locale
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.ByteString.Companion.toByteString

internal interface Settings {
    fun get(key: String): String
    fun put(values: Map<String, String>)
}

internal data class DropboxFile(
    val id: String,
    val name: String,
    val path: String,
    val folder: Boolean,
    val size: Long = 0,
    val rev: String = "",
    val modified: String = "",
    val pathLower: String = path.lowercase(Locale.ROOT),
) {
    companion object {
        fun parse(value: JsonObject) = DropboxFile(
            value.text("id"), value.text("name"), value.text("path_display"),
            value.text(".tag") == "folder", value["size"]?.jsonPrimitive?.long ?: 0,
            value.text("rev"), value.text("server_modified"),
            value.text("path_lower").ifBlank { value.text("path_display").lowercase(Locale.ROOT) },
        )
    }
}
internal fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

/** Read-only user API. Tokens appear only in headers/form bodies, never page URLs. */
internal class DropboxApi(
    client: OkHttpClient,
    private val settings: Settings,
    private val rpcBase: String = "https://api.dropboxapi.com/2/",
    private val contentBase: String = "https://content.dropboxapi.com/2/",
    private val tokenUrl: String = "https://api.dropboxapi.com/oauth2/token",
    private val defaultAppKey: String = "",
) {
    private val client = privateDropboxClient(client)
    // Only background token exchanges use tokenLock. UI actions never wait for network I/O.
    private val tokenLock = Any()
    private val stateLock = Any()
    private var session = 0L
    private val json = Json { ignoreUnknownKeys = true }

    fun disconnect() = synchronized(stateLock) {
        session++
        settings.put(emptyCredentials())
    }

    fun updateSetting(key: String, value: String) = synchronized(stateLock) {
        if (key == "app_key" && value.trim().ifBlank { defaultAppKey } != appKey()) {
            session++
            settings.put(emptyCredentials() + (key to value))
        } else {
            if (key == "auth_code") session++
            settings.put(mapOf(key to value))
        }
    }

    private fun emptyCredentials() = listOf("access_token", "refresh_token", "expires_at", "auth_code", "verifier", "authorization_url").associateWith { "" }
    private fun appKey() = settings.get("app_key").trim().ifBlank { defaultAppKey }
    private fun currentSession() = synchronized(stateLock) { session }
    private fun checkSession(expected: Long) {
        if (session != expected) connectionChanged()
    }
    private fun connectionChanged(): Nothing = throw IOException("Dropbox connection changed. Browse again after connecting.")

    /** Reuse the pending PKCE link across settings/source-list reloads. */
    fun signInLink(): String = synchronized(stateLock) {
        if (appKey().isBlank()) return@synchronized ""
        val prepared = settings.get("authorization_url")
        if (prepared.isNotBlank() && settings.get("verifier").isNotBlank()) return@synchronized prepared
        if (settings.get("refresh_token").isNotBlank() && settings.get("auth_code").isBlank()) return@synchronized ""
        authorizationUrl()
    }

    fun authorizationUrl(): String = synchronized(stateLock) {
        val key = appKey()
        if (key.isEmpty()) throw IOException("Set your Dropbox app key in the source settings first.")
        val verifier = ByteArray(32).apply { SecureRandom().nextBytes(this) }.joinToString("") { "%02x".format(it.toInt() and 255) }
        val challenge = verifier.toByteArray(Charsets.US_ASCII).toByteString().sha256().base64Url().trimEnd('=')
        session++
        settings.put(mapOf("verifier" to verifier, "auth_code" to ""))
        "https://www.dropbox.com/oauth2/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", key)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("token_access_type", "offline")
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("scope", "files.metadata.read files.content.read")
            .build().toString().also { settings.put(mapOf("authorization_url" to it)) }
    }

    private fun accessToken(expected: Long, force: Boolean = false, rejected: String = ""): String = synchronized(tokenLock) {
        val state = synchronized(stateLock) {
            checkSession(expected)
            listOf("app_key", "auth_code", "verifier", "access_token", "expires_at", "refresh_token")
                .associateWith { if (it == "app_key") appKey() else settings.get(it) }
        }
        val key = state.getValue("app_key").trim()
        val code = state.getValue("auth_code").trim()
        if (code.isNotEmpty()) {
            val verifier = state.getValue("verifier")
            if (verifier.isBlank()) throw IOException("Open source settings and use the Dropbox sign-in link before pasting an authorization code.")
            val result = token(FormBody.Builder().add("grant_type", "authorization_code")
                .add("client_id", key).add("code", code).add("code_verifier", verifier).build())
            return@synchronized saveToken(result, expected, clearCode = true)
        }
        val current = state.getValue("access_token")
        val expires = state.getValue("expires_at").toLongOrNull() ?: 0
        // Another request may already have renewed the rejected token.
        if (current.isNotBlank() && ((!force && expires > System.currentTimeMillis() + 60000) ||
                (force && current != rejected && expires > System.currentTimeMillis() + 60000))) return current
        val refresh = state.getValue("refresh_token").trim()
        if (refresh.isBlank() || key.isBlank()) throw IOException("Use the Dropbox sign-in link in source settings, paste the authorization code there, then browse again.")
        saveToken(token(FormBody.Builder().add("grant_type", "refresh_token")
            .add("client_id", key).add("refresh_token", refresh).build()), expected)
    }

    private data class Token(val access: String, val refresh: String, val expires: Long)

    private fun token(body: FormBody): Token = client.newCall(Request.Builder().url(tokenUrl).post(body).build()).execute().use {
        if (!it.isSuccessful) throw IOException("Dropbox sign-in failed (HTTP ${it.code}). Reconnect Dropbox; check your app key and code.")
        try {
            val value = parse(it, 64L * 1024)
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
        val fields = mutableMapOf("access_token" to value.access, "expires_at" to (System.currentTimeMillis() + value.expires * 1000).toString())
        value.refresh.takeIf { it.isNotEmpty() }?.let { fields["refresh_token"] = it }
        if (clearCode) fields.putAll(mapOf("auth_code" to "", "verifier" to "", "authorization_url" to ""))
        settings.put(fields)
        value.access
    }

    private fun execute(request: Request, expected: Long = currentSession()): Response {
        val token = accessToken(expected)
        fun send(value: String): Response {
            synchronized(stateLock) { checkSession(expected) }
            val response = client.newCall(request.newBuilder().header("Authorization", "Bearer $value").build()).execute()
            if (synchronized(stateLock) { session != expected }) {
                response.close()
                connectionChanged()
            }
            return response
        }
        var response = send(token)
        if (response.code == 401) {
            response.close()
            response = send(accessToken(expected, force = true, rejected = token))
        }
        if (!response.isSuccessful) {
            val code = response.code
            val retry = response.header("Retry-After")?.toLongOrNull()
            response.close()
            throw IOException(when (code) {
                401 -> "Dropbox access was revoked. Reconnect Dropbox in source settings."
                403 -> "Dropbox denied access. Enable files.metadata.read and files.content.read, then reconnect."
                409 -> "Dropbox file/folder was not found or cannot be read. Check the library path and refresh."
                429 -> "Dropbox rate limit reached. Try again ${retry?.let { "in $it seconds" } ?: "later"}."
                else -> "Dropbox request failed (HTTP $code). Try again later."
            })
        }
        return response
    }
    private fun parse(response: Response, limit: Long = 8L * 1024 * 1024): JsonObject = try {
        json.parseToJsonElement(boundedBody(response, limit, exact = false).toString(Charsets.UTF_8)).jsonObject
    } catch (_: RuntimeException) {
        throw IOException("Invalid Dropbox response")
    }
    private fun rpc(route: String, payload: JsonObject, expected: Long = currentSession()): JsonObject {
        val request = Request.Builder().url(rpcBase + route)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return execute(request, expected).use { parse(it) }
    }
    fun metadata(path: String): DropboxFile = DropboxFile.parse(rpc("files/get_metadata", buildJsonObject { put("path", path) }))

    fun list(path: String, recursive: Boolean): List<DropboxFile> {
        val expected = currentSession()
        var result = rpc("files/list_folder", buildJsonObject {
            put("path", path); put("recursive", recursive); put("limit", 2000)
            put("include_deleted", false); put("include_non_downloadable_files", false)
        }, expected)
        val entries = linkedMapOf<String, DropboxFile>()
        val seenCursors = mutableSetOf<String>()
        while (true) {
            result["entries"]!!.jsonArray.forEach {
                val obj = it.jsonObject
                if (obj.text(".tag") != "deleted") {
                    val file = DropboxFile.parse(obj)
                    entries[file.id] = file
                }
            }
            if (entries.size > 100000) throw IOException("Library exceeds 100,000 items. Select a smaller Dropbox folder.")
            if (result["has_more"]?.jsonPrimitive?.boolean != true) break
            val cursor = result.text("cursor")
            if (cursor.isEmpty() || !seenCursors.add(cursor)) throw IOException("Invalid Dropbox pagination cursor")
            result = rpc("files/list_folder/continue", buildJsonObject { put("cursor", cursor) }, expected)
        }
        return entries.values.toList()
    }

    fun reader(file: DropboxFile): RangeReader = object : RangeReader {
        private val expected = currentSession()
        override val size = file.size
        override fun read(start: Long, end: Long): ByteArray {
            if (start < 0 || end < start || end >= size || end - start + 1 > RemoteZip.MAX_PAGE) throw IOException("Invalid Dropbox byte range")
            val request = downloadRequest("rev:${file.rev}").header("Range", "bytes=$start-$end").build()
            return execute(request, expected).use { response ->
                val expected = "bytes $start-$end/$size"
                if (response.code != 206 || response.header("Content-Range") != expected) throw IOException("Dropbox did not honor the byte range; refusing a full archive download.")
                boundedBody(response, end - start + 1, exact = true)
            }
        }
    }
    fun image(file: DropboxFile): ByteArray {
        if (file.size > RemoteZip.MAX_PAGE) throw IOException("Cover exceeds the 32 MiB limit")
        return execute(downloadRequest("rev:${file.rev}").build()).use { boundedBody(it, file.size, exact = true) }
    }
    private fun downloadRequest(path: String) = Request.Builder().url(contentBase + "files/download")
        .get().header("Accept-Encoding", "identity").header("Dropbox-API-Arg", asciiJson(buildJsonObject { put("path", path) }.toString()))

    private fun boundedBody(response: Response, limit: Long, exact: Boolean): ByteArray {
        val input = response.body?.source() ?: throw IOException("Empty Dropbox content response")
        val buffer = okio.Buffer()
        var remaining = limit + 1
        while (remaining > 0) {
            val read = input.read(buffer, minOf(remaining, 8192))
            if (read == -1L) break
            remaining -= read
        }
        if (buffer.size > limit || (exact && buffer.size != limit)) throw IOException("Dropbox content length mismatch")
        return buffer.readByteArray()
    }
    companion object {
        fun asciiJson(value: String) = buildString {
            value.forEach { if (it.code >= 127) append("\\u%04x".format(it.code)) else append(it) }
        }
    }
}

/** Keep host transport settings, but remove hooks that can record credentials or replay them. */
internal fun privateDropboxClient(client: OkHttpClient): OkHttpClient = client.newBuilder().apply {
    interceptors().clear()
    networkInterceptors().clear()
    eventListener(EventListener.NONE)
    authenticator(Authenticator.NONE)
    proxyAuthenticator(Authenticator.NONE)
    cookieJar(CookieJar.NO_COOKIES)
    cache(null)
    followRedirects(false)
    followSslRedirects(false)
}.build()
