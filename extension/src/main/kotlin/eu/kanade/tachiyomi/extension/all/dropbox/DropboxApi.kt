package eu.kanade.tachiyomi.extension.all.dropbox

import java.io.IOException
import java.util.Locale
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

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
    settings: Settings,
    private val rpcBase: String = "https://api.dropboxapi.com/2/",
    private val contentBase: String = "https://content.dropboxapi.com/2/",
    tokenUrl: String = "https://api.dropboxapi.com/oauth2/token",
    defaultAppKey: String = "",
) {
    private val client = privateDropboxClient(client)
    private val auth = DropboxAuth(this.client, settings, tokenUrl, defaultAppKey)

    // Keep the source-facing API stable while authentication owns its state transitions.
    fun disconnect() = auth.disconnect()
    fun updateSetting(key: String, value: String) = auth.updateSetting(key, value)
    fun signInLink() = auth.signInLink()
    fun authorizationUrl() = auth.authorizationUrl()
    fun isConnected() = auth.isConnected()
    fun beginAndroidSignIn() = auth.beginAndroidSignIn()
    fun cancelAndroidSignIn(state: String) = auth.cancelAndroidSignIn(state)
    fun completeAndroidSignIn(state: String, code: String) = auth.completeAndroidSignIn(state, code)

    private fun execute(request: Request, expected: Long = auth.currentSession()): Response {
        val token = auth.accessToken(expected)
        fun send(value: String): Response {
            auth.checkSession(expected)
            val response = client.newCall(request.newBuilder().header("Authorization", "Bearer $value").build()).execute()
            try {
                auth.checkSession(expected)
            } catch (error: IOException) {
                response.close()
                throw error
            }
            return response
        }
        var response = send(token)
        if (response.code == 401) {
            response.close()
            response = send(auth.accessToken(expected, force = true, rejected = token))
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
    private fun rpc(route: String, payload: JsonObject, expected: Long = auth.currentSession()): JsonObject {
        val request = Request.Builder().url(rpcBase + route)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return execute(request, expected).use { parseDropboxJson(it) }
    }
    fun metadata(path: String): DropboxFile = DropboxFile.parse(rpc("files/get_metadata", buildJsonObject { put("path", path) }))

    fun list(path: String, recursive: Boolean): List<DropboxFile> {
        val expected = auth.currentSession()
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
        private val expected = auth.currentSession()
        override val size = file.size
        override fun read(start: Long, end: Long): ByteArray {
            if (start < 0 || end < start || end >= size || end - start + 1 > RemoteZip.MAX_PAGE) throw IOException("Invalid Dropbox byte range")
            val request = downloadRequest("rev:${file.rev}").header("Range", "bytes=$start-$end").build()
            return execute(request, expected).use { response ->
                val expected = "bytes $start-$end/$size"
                if (response.code != 206 || response.header("Content-Range") != expected) throw IOException("Dropbox did not honor the byte range; refusing a full archive download.")
                boundedDropboxBody(response, end - start + 1, exact = true)
            }
        }
    }
    fun image(file: DropboxFile): ByteArray {
        if (file.size > RemoteZip.MAX_PAGE) throw IOException("Cover exceeds the 32 MiB limit")
        return execute(downloadRequest("rev:${file.rev}").build()).use { boundedDropboxBody(it, file.size, exact = true) }
    }
    private fun downloadRequest(path: String) = Request.Builder().url(contentBase + "files/download")
        .get().header("Accept-Encoding", "identity").header("Dropbox-API-Arg", asciiJson(buildJsonObject { put("path", path) }.toString()))

    companion object {
        fun asciiJson(value: String) = buildString {
            value.forEach { if (it.code >= 127) append("\\u%04x".format(it.code)) else append(it) }
        }
    }
}
