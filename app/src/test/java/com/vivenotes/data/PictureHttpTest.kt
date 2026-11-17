package com.vivenotes.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

class PictureHttpTest {
    @Test
    fun `an online SVG download returns the original bytes without fetching its PNG thumbnail`() = runBlocking {
        val source = """<svg xmlns="http://www.w3.org/2000/svg" width="80" height="40"/>""".encodeToByteArray()
        val fetched = mutableListOf<String>()
        val http = PictureHttp(openConnection = { url ->
            fetched += url.toString()
            Response(source, "Image/SVG+XML; charset=utf-8")
        })
        val image = OnlineImage("svg", "diagram", "https://example.org/a.svg", "https://example.org/a.png", 80, 40, ImageSource.Openverse)

        assertArrayEquals(source, OnlineImageSearch(http = http).download(image))
        assertEquals(listOf(image.url), fetched)
    }

    @Test
    fun `SVG content length is checked against the importer limit before reading`() = runBlocking {
        val response = Response(byteArrayOf(1), "image/svg+xml", SvgPictureSource.MAX_BYTES + 1L)
        val http = PictureHttp(openConnection = { response })

        assertNull(http.image("https://example.org/a.svg", 30 * 1024 * 1024))
        assertFalse(response.openedBody)
    }

    @Test
    fun `an SVG without content length still obeys the importer limit`() = runBlocking {
        val response = Response(ByteArray(SvgPictureSource.MAX_BYTES + 1), "image/svg+xml", -1)
        val http = PictureHttp(openConnection = { response })

        assertNull(http.image("https://example.org/a.svg", 30 * 1024 * 1024))
    }

    @Test
    fun `a smaller caller limit still applies to SVG previews`() = runBlocking {
        val response = Response(ByteArray(1025), "image/svg+xml", -1)
        val http = PictureHttp(openConnection = { response })

        assertNull(http.image("https://example.org/a.svg", 1024))
    }

    @Test
    fun `an HTML response still falls back to the thumbnail`() = runBlocking {
        val thumbnail = byteArrayOf(1, 2, 3)
        val http = PictureHttp(openConnection = { url ->
            if (url.path.endsWith(".svg")) Response("<html>Gone</html>".encodeToByteArray(), "text/html")
            else Response(thumbnail, "image/png")
        })
        val image = OnlineImage("svg", "diagram", "https://example.org/a.svg", "https://example.org/a.png", 80, 40, ImageSource.WikimediaCommons)

        assertArrayEquals(thumbnail, OnlineImageSearch(http = http).download(image))
    }

    private class Response(
        private val body: ByteArray,
        private val type: String,
        private val length: Long = body.size.toLong(),
    ) : HttpURLConnection(URL("https://example.org/")) {
        var openedBody = false
        override fun getResponseCode() = HTTP_OK
        override fun getContentType() = type
        override fun getContentLengthLong() = length
        override fun getInputStream() = ByteArrayInputStream(body).also { openedBody = true }
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }
}
