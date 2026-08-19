package com.vivenotes.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import com.vivenotes.model.youTubeVideoId
import com.vivenotes.richtext.VideoThumbnails
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Video thumbnails for the editor's link previews.
 *
 * **The only place in the app that talks to a host the user did not choose.** Everything else
 * reaches the user's own sync server or GitHub, so this one is deliberately narrow: it fetches from
 * exactly one origin, at exactly one URL shape, keyed by an id that
 * [com.vivenotes.model.youTubeVideoId] has already proved is eleven characters of `[A-Za-z0-9_-]`.
 * There is no method here that takes a URL, so nothing upstream can turn it into a general
 * fetcher — and the whole class is unreachable while the Settings toggle is off, because the editor
 * is simply handed no [VideoThumbnails] at all.
 *
 * **Files live in `filesDir`, not `cacheDir`, and are still derived data.** A note is meant to
 * survive; a thumbnail evicted by the OS mid-flight would blank a card the writer has been looking
 * at for a month, offline, with no way to get it back. They carry nothing that is not re-fetchable,
 * so they are excluded from `.vive` export exactly as `attachment_text` is — see
 * `NotebookTransferManager`. Nothing references them from the database, so nothing has to be
 * refcounted: the id in the page's own text is the only pointer there is.
 *
 * Contrast [AttachmentStore], which re-encodes everything it stores. These arrive as small JPEGs
 * already — a maxres frame is around a hundred kilobytes — so re-encoding would cost a lossy pass
 * to save nothing. The allocation that class exists to avoid is handled at *decode* instead:
 * [ImageDecoder] is given a target size, so a 1280×720 frame never becomes a 3.7 MB bitmap for a
 * card 360 dp wide.
 */
class VideoThumbnailStore(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val openConnection: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
) : VideoThumbnails {

    private val appContext = context.applicationContext

    /**
     * Process-lifetime, because a fetch belongs to the app rather than to whichever editor happened
     * to ask: the same link on the same page is requested again the moment the view is recreated,
     * and a scope that died with the view would throw away a download that was nearly finished.
     */
    private val scope = CoroutineScope(SupervisorJob() + io)

    private val directory: File
        get() = File(appContext.filesDir, DIRECTORY).apply { mkdirs() }

    /**
     * Decoded thumbnails, most-recently-used last.
     *
     * Small and bounded rather than a soft-reference map: a page shows a handful of cards, and the
     * cost of a miss is a disk decode, not a network round trip. Guarded by its own lock because
     * [cached] is called from the main thread and filled from [io].
     */
    private val memory = object : LinkedHashMap<String, Bitmap>(MEMORY_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > MEMORY_ENTRIES
    }

    private val inFlight = mutableSetOf<String>()

    /**
     * When each video's fetch last failed.
     *
     * Without it, a video with no thumbnail — deleted, private, or simply an offline device — would
     * be re-fetched on every keystroke after the link, because the editor's refresh pass has no
     * memory of its own. [RETRY_AFTER_MS] rather than a permanent blocklist, so coming back onto a
     * network fixes itself without restarting the app.
     */
    private val failedAt = mutableMapOf<String, Long>()

    override fun cached(videoId: String): Bitmap? = synchronized(memory) { memory[videoId] }

    override fun request(videoId: String, onReady: () -> Unit) {
        if (!isKnownVideoId(videoId)) return
        synchronized(memory) {
            if (memory.containsKey(videoId)) {
                onReady()
                return
            }
            if (videoId in inFlight) return
            val failed = failedAt[videoId]
            if (failed != null && System.currentTimeMillis() - failed < RETRY_AFTER_MS) return
            inFlight += videoId
        }

        scope.launch {
            val bitmap = runCatching { load(videoId) }.getOrNull()
            synchronized(memory) {
                inFlight -= videoId
                if (bitmap == null) {
                    failedAt[videoId] = System.currentTimeMillis()
                } else {
                    failedAt -= videoId
                    memory[videoId] = bitmap
                }
            }
            if (bitmap != null) withContext(Dispatchers.Main) { onReady() }
        }
    }

    /** Reads the stored frame, downloading it first if this device has never seen it. */
    private suspend fun load(videoId: String): Bitmap? {
        val file = File(directory, videoId)
        if (!file.exists()) {
            val bytes = download(videoId) ?: return null
            // Written beside and moved into place, so a fetch interrupted halfway cannot leave a
            // truncated file sitting under a name the next launch will trust — the same rule
            // `AttachmentStore.import` follows.
            val staging = File(directory, "$videoId.part")
            staging.writeBytes(bytes)
            if (!staging.renameTo(file)) {
                staging.delete()
                return null
            }
        }
        return decode(file)
    }

    /**
     * The best frame YouTube actually has for this video.
     *
     * `maxresdefault` is a clean 1280×720 but only exists for videos uploaded above that
     * resolution, so a miss here is routine rather than an error — it 404s and the 320×180
     * `mqdefault`, which every video has, is what a short or an old upload gets. Both are 16:9;
     * `hqdefault`, the other always-present size, is 4:3 with letterbox bars baked into the pixels,
     * which is why it is not the fallback.
     */
    private suspend fun download(videoId: String): ByteArray? {
        THUMBNAIL_NAMES.forEach { name ->
            coroutineContext.ensureActive()
            val bytes = runCatching { fetch("$ORIGIN/vi/$videoId/$name") }.getOrNull()
            if (bytes != null) return bytes
        }
        return null
    }

    private suspend fun fetch(url: String): ByteArray? {
        val connection = openConnection(URL(url)).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) return null
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            val out = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                    // A thumbnail is a hundred kilobytes. Anything claiming to be far more is a
                    // redirect somewhere unexpected, and reading it to the end would be the bug.
                    if (out.size() > MAX_BYTES) return null
                }
            }
            return out.toByteArray().takeIf { it.isNotEmpty() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Decodes at card size rather than at frame size.
     *
     * [DECODE_MAX_WIDTH] is twice the widest a card is drawn, which leaves the picture sharp when
     * the page is zoomed in without keeping a full 720p frame per link in memory.
     */
    private suspend fun decode(file: File): Bitmap? = withContext(io) {
        runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > DECODE_MAX_WIDTH) {
                    val scale = DECODE_MAX_WIDTH.toFloat() / longest
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
                // Drawn through a BitmapShader by `VideoEmbedSpan`, which a hardware bitmap cannot
                // be the source of; software keeps it usable at this size. Same call
                // `AttachmentStore.loadBitmap` makes, for the same reason.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        }.getOrNull()
    }

    private companion object {
        private const val DIRECTORY = "video_thumbnails"

        /** The image host, and the only one this class will ever open a connection to. */
        private const val ORIGIN = "https://i.ytimg.com"

        private val THUMBNAIL_NAMES = listOf("maxresdefault.jpg", "mqdefault.jpg")

        private const val MEMORY_ENTRIES = 24
        private const val DECODE_MAX_WIDTH = 720
        private const val MAX_BYTES = 4 * 1024 * 1024
        private const val COPY_BUFFER_BYTES = 32 * 1024
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val RETRY_AFTER_MS = 5 * 60 * 1000L

        /**
         * Re-checks the caller's id against the parser that produced it.
         *
         * Belt and braces: [request] is only ever called with an id
         * [com.vivenotes.model.youTubeVideoId] returned, but this class turns that string into a
         * filename and into a URL path, so it refuses to do either on a value it has not proved for
         * itself. Written as a round trip through the real parser rather than a second regular
         * expression, so the two can never drift apart.
         */
        private fun isKnownVideoId(videoId: String): Boolean =
            youTubeVideoId("https://youtu.be/$videoId") == videoId
    }
}
