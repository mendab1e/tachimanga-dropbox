package eu.kanade.tachiyomi.extension.all.dropbox

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response

private val dropboxJson = Json { ignoreUnknownKeys = true }

internal fun parseDropboxJson(response: Response, limit: Long = 8L * 1024 * 1024): JsonObject = try {
    dropboxJson.parseToJsonElement(boundedDropboxBody(response, limit, exact = false).toString(Charsets.UTF_8)).jsonObject
} catch (_: RuntimeException) {
    throw IOException("Invalid Dropbox response")
}

internal fun boundedDropboxBody(response: Response, limit: Long, exact: Boolean): ByteArray {
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
