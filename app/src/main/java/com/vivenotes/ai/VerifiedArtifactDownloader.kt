package com.vivenotes.ai

import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

internal data class ModelArtifact(
    val fileName: String,
    val bytes: Long,
    val sha256: String,
    val url: String,
)

/** Streams one pinned artifact to disk and exposes it only after size and SHA-256 verification. */
internal class VerifiedArtifactDownloader(
    private val openConnection: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
) {
    suspend fun download(
        artifact: ModelArtifact,
        destination: File,
        onBytes: (Long) -> Unit,
    ) {
        val connection = openConnection(URL(artifact.url)).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            val response = connection.responseCode
            require(response in 200..299) { "Server returned HTTP $response" }
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        written += count
                        require(written <= artifact.bytes) {
                            "${artifact.fileName} is larger than expected"
                        }
                        onBytes(written)
                    }
                    output.fd.sync()
                }
            }
            require(written == artifact.bytes) { "${artifact.fileName} is incomplete" }
            require(digest.digest().hex() == artifact.sha256) {
                "${artifact.fileName} failed verification"
            }
        } catch (failure: Exception) {
            destination.delete()
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 60_000
    }
}

internal fun ByteArray.hex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
