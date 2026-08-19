package com.vivenotes.data

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The fetch, the fallback and the caching, without touching the network.
 *
 * Instrumented because the class decodes with `ImageDecoder` and writes into `filesDir`, neither of
 * which exists on the JVM. The connection is injected instead of the whole store being faked, so
 * what is under test is the real URL construction, the real 404 fallback and the real disk round
 * trip — everything except the socket.
 */
class VideoThumbnailStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = "dQw4w9WgXcQ"
    private val directory get() = File(context.filesDir, "video_thumbnails")

    private lateinit var served: MutableList<String>

    @Before
    fun clean() {
        directory.deleteRecursively()
        served = mutableListOf()
    }

    @After
    fun sweep() {
        directory.deleteRecursively()
    }

    @Test
    fun fetchesTheMaxResFrameAndKeepsIt() {
        val store = storeServing { url ->
            if (url.endsWith("maxresdefault.jpg")) Response(200, jpeg(1280, 720)) else Response(404)
        }

        val bitmap = awaitThumbnail(store)

        assertNotNull("the frame should have been fetched", bitmap)
        assertEquals(listOf("https://i.ytimg.com/vi/$id/maxresdefault.jpg"), served)
        assertTrue("the frame should be on disk", File(directory, id).exists())
        // Cached in memory afterwards, so a redraw costs nothing.
        assertNotNull(store.cached(id))
        // No half-written file left behind by the staged rename.
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun fallsBackToTheFrameEveryVideoHas() {
        val store = storeServing { url ->
            if (url.endsWith("mqdefault.jpg")) Response(200, jpeg(320, 180)) else Response(404)
        }

        assertNotNull(awaitThumbnail(store))
        assertEquals(
            listOf(
                "https://i.ytimg.com/vi/$id/maxresdefault.jpg",
                "https://i.ytimg.com/vi/$id/mqdefault.jpg",
            ),
            served,
        )
    }

    @Test
    fun readsTheSecondTimeFromDiskRatherThanTheNetwork() {
        val store = storeServing { Response(200, jpeg(320, 180)) }
        awaitThumbnail(store)
        val firstPass = served.size

        // A fresh store, so the memory cache cannot be what answers.
        val reopened = storeServing { Response(200, jpeg(320, 180)) }
        served.clear()
        assertNotNull(awaitThumbnail(reopened))
        assertTrue("a stored frame must not be re-fetched", served.isEmpty())
        assertTrue(firstPass > 0)
    }

    @Test
    fun aVideoWithNoThumbnailIsNotAskedForAgain() {
        val store = storeServing { Response(404) }
        val latch = CountDownLatch(1)

        store.request(id) { latch.countDown() }
        // Nothing is ready, so the callback never comes; wait for both attempts to have been made.
        waitUntil { served.size == 2 }
        assertTrue("a failed fetch must not report success", latch.count == 1L)

        served.clear()
        store.request(id) { latch.countDown() }
        Thread.sleep(300)
        assertTrue("a failure inside the retry window must not go back out", served.isEmpty())
    }

    @Test
    fun refusesAnIdItCannotProveIsAVideoId() {
        val store = storeServing { Response(200, jpeg(320, 180)) }

        store.request("../../etc/passwd") {}
        store.request("short") {}
        store.request("$id/extra") {}
        Thread.sleep(300)

        assertTrue("no connection may be opened for a malformed id", served.isEmpty())
        assertNull(store.cached("../../etc/passwd"))
    }

    @Test
    fun refusesABodyFarLargerThanAThumbnail() {
        val store = storeServing { Response(200, ByteArray(5 * 1024 * 1024)) }

        store.request(id) {}
        waitUntil { served.size == 2 }
        Thread.sleep(300)

        assertNull("an oversized body must not be stored", store.cached(id))
        assertTrue(File(directory, id).exists().not())
    }

    // --- harness -------------------------------------------------------------------------------

    private data class Response(val code: Int, val body: ByteArray = ByteArray(0))

    private fun storeServing(handler: (String) -> Response): VideoThumbnailStore =
        VideoThumbnailStore(context) { url ->
            served += url.toString()
            FakeConnection(url, handler(url.toString()))
        }

    /** Requests [id] and blocks until the store reports a bitmap, or gives up. */
    private fun awaitThumbnail(store: VideoThumbnailStore): Bitmap? {
        val latch = CountDownLatch(1)
        store.request(id) { latch.countDown() }
        latch.await(10, TimeUnit.SECONDS)
        return store.cached(id)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting; requests so far: $served")
    }

    private fun jpeg(width: Int, height: Int): ByteArray = ByteArrayOutputStream().use { out ->
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            .compress(Bitmap.CompressFormat.JPEG, 90, out)
        out.toByteArray()
    }

    private class FakeConnection(url: URL, private val response: Response) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = response.code
        override fun getInputStream(): InputStream = ByteArrayInputStream(response.body)
    }
}
