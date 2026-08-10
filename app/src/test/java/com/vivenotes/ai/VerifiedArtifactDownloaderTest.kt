package com.vivenotes.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class VerifiedArtifactDownloaderTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun successfulDownloadFollowsRedirectsStreamsProgressAndVerifiesTheArtifact() = runBlocking {
        val body = "verified formula bytes".toByteArray()
        val connection = FakeConnection(status = 200, body = body)
        val destination = temporary.newFile("model.part")
        val progress = mutableListOf<Long>()
        val downloader = VerifiedArtifactDownloader { connection }

        downloader.download(
            artifact = artifact(body),
            destination = destination,
            onBytes = progress::add,
        )

        assertArrayEquals(body, destination.readBytes())
        assertEquals(listOf(body.size.toLong()), progress)
        assertTrue(connection.instanceFollowRedirects)
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
        assertTrue(connection.disconnected)
    }

    @Test
    fun checksumFailureDeletesTheUntrustedPartialFile() = runBlocking {
        val body = "corrupt".toByteArray()
        val connection = FakeConnection(status = 200, body = body)
        val destination = temporary.newFile("corrupt.part")
        val downloader = VerifiedArtifactDownloader { connection }

        val failure = runCatching {
            downloader.download(
                artifact = artifact(body).copy(sha256 = "0".repeat(64)),
                destination = destination,
                onBytes = {},
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertFalse(destination.exists())
        assertTrue(connection.disconnected)
    }

    @Test
    fun httpFailureCreatesNoInstallableArtifact() = runBlocking {
        val connection = FakeConnection(status = 503, body = ByteArray(0))
        val destination = temporary.newFile("unavailable.part")
        val downloader = VerifiedArtifactDownloader { connection }

        val failure = runCatching {
            downloader.download(
                artifact = artifact(ByteArray(0)),
                destination = destination,
                onBytes = {},
            )
        }.exceptionOrNull()

        assertEquals("Server returned HTTP 503", failure?.message)
        assertFalse(destination.exists())
        assertTrue(connection.disconnected)
    }

    @Test
    fun formulaSourcesUseThePinnedAndroidTrustedHosts() {
        val model = URL(AiModelStore.FORMULA_MODEL_URL)
        val tokenizer = URL(AiModelStore.FORMULA_TOKENIZER_URL)

        assertEquals("github.com", model.host)
        assertEquals(
            "/GreatV/oar-ocr/releases/download/v0.3.0/pp-formulanet_plus-s.onnx",
            model.path,
        )
        assertEquals("huggingface.co", tokenizer.host)
        assertEquals(
            "/PaddlePaddle/PP-FormulaNet-L_safetensors/resolve/main/tokenizer.json",
            tokenizer.path,
        )
    }

    private fun artifact(body: ByteArray) = ModelArtifact(
        fileName = "model.onnx",
        bytes = body.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(body).hex(),
        url = "https://downloads.example/model.onnx",
    )
}

private class FakeConnection(
    private val status: Int,
    private val body: ByteArray,
) : HttpURLConnection(URL("https://downloads.example/model.onnx")) {
    var disconnected = false

    override fun getResponseCode(): Int = status
    override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    override fun disconnect() {
        disconnected = true
    }
    override fun usingProxy(): Boolean = false
    override fun connect() = Unit
}
