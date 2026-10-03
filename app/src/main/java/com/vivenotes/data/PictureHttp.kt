package com.vivenotes.data

import com.vivenotes.BuildConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * The network half of the online picture search: one identity, bounded reads, HTTPS only.
 *
 * HTTPS only because the addresses come from third parties' indexes, and the network security
 * config permits cleartext app-wide for the sync server's sake.
 */
internal class PictureHttp(
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val openConnection: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
) {
    sealed interface Text {
        data class Ok(val body: String) : Text
        data object RateLimited : Text
        data object Failed : Text
    }

    /** A JSON API answer. */
    suspend fun text(address: String): Text = withContext(io) {
        val connection = connect(address) ?: return@withContext Text.Failed
        try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK ->
                    Text.Ok(connection.inputStream.use { it.readBytes().decodeToString() })
                HTTP_TOO_MANY_REQUESTS -> Text.RateLimited
                else -> Text.Failed
            }
        } catch (e: IOException) {
            Text.Failed
        } finally {
            connection.disconnect()
        }
    }

    /**
     * One picture, or null — including for a picture [android.graphics.ImageDecoder] cannot read,
     * so a caller's fallback gets its turn instead of the import failing silently afterwards.
     */
    suspend fun image(address: String, maxBytes: Int): ByteArray? = withContext(io) {
        val connection = connect(address) ?: return@withContext null
        try {
            if (connection.responseCode !in 200..299) return@withContext null
            val type = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
            if (type !in DECODABLE_TYPES) return@withContext null
            if (connection.contentLengthLong > maxBytes) return@withContext null
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                    // Content-Length is optional, so the cap is enforced on what actually arrives.
                    if (out.size() > maxBytes) return@withContext null
                }
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        } catch (e: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun connect(address: String): HttpURLConnection? {
        val url = runCatching { URL(address) }.getOrNull() ?: return null
        if (url.protocol != "https") return null
        return openConnection(url).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            // Both services ask clients to identify themselves — Wikimedia's policy requires a
            // name and a way to make contact — and a default Java agent is what scrapers send.
            setRequestProperty("User-Agent", USER_AGENT)
        }
    }

    companion object {
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val COPY_BUFFER_BYTES = 16 * 1024

        /** What ImageDecoder reads. Not SVG or TIFF, which both sources host plenty of. */
        private val DECODABLE_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
            "image/heic",
            "image/heif",
            "image/avif",
            "image/bmp",
        )

        private val USER_AGENT =
            "viveNotes/${BuildConfig.VERSION_NAME} (+https://github.com/AquilaIgnis/viveNotes)"
    }
}
