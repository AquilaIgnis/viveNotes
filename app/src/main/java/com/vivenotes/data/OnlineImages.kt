package com.vivenotes.data

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.LruCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Where a search hit came from. The dialog marks where one source gives way to the next. */
enum class ImageSource { WikimediaCommons, Openverse }

/** One search hit: what the results grid shows, and what is fetched when it is picked. */
data class OnlineImage(
    val id: String,
    val title: String,
    /** What is inserted: the original when Android can decode it at a sensible size, else a rendition. */
    val url: String,
    /** A grid-sized rendition the source serves — when it can; see [OnlineImageSearch.thumbnail]. */
    val thumbnail: String,
    val width: Int,
    val height: Int,
    val source: ImageSource,
)

/** Where the next page of a search starts. Opaque outside this file and the two clients. */
sealed interface SearchCursor {
    /** [offRuns] counts the pages in a row that were mostly off-topic — see [nextAfterCommons]. */
    data class Commons(val offset: Int, val offRuns: Int = 0) : SearchCursor
    data class Openverse(val page: Int) : SearchCursor
}

sealed interface OnlineImageSearchResult {
    /** [next] is handed back to [OnlineImages.search] for what follows; null when nothing does. */
    data class Found(val images: List<OnlineImage>, val next: SearchCursor?) : OnlineImageSearchResult

    /** A quota is spent — per minute or per day. Waiting fixes it; retrying at once does not. */
    data object RateLimited : OnlineImageSearchResult

    /** No network, a timeout, or an answer that was not a result list. */
    data object Unreachable : OnlineImageSearchResult
}

/** Search online and insert — the Picture menu's "Search online". */
interface OnlineImages {
    /** The first page for [query] when [from] is null; otherwise the page [from] points at. */
    suspend fun search(query: String, from: SearchCursor?): OnlineImageSearchResult

    /** A small decoded preview for the grid, or null when it could not be fetched. */
    suspend fun thumbnail(image: OnlineImage): Bitmap?

    /** The bytes to import — [OnlineImage.url] when it can be had, the thumbnail when not. */
    suspend fun download(image: OnlineImage): ByteArray?
}

/** One source's answer for one page, before the two sources are stitched together. */
internal sealed interface SourceResult {
    /**
     * [onTopic] is how many [images] name the query in their own words — see
     * [WikimediaCommonsClient.isOnTopic]. Openverse does not measure it and reports them all.
     */
    data class Page(val images: List<OnlineImage>, val hasMore: Boolean, val onTopic: Int) : SourceResult
    data object RateLimited : SourceResult
    data object Unreachable : SourceResult
}

/**
 * Wikimedia Commons first, Openverse once Commons stops being about the query.
 *
 * Commons leads because it is where the diagrams are — a search for "meiosis" there is page after
 * page of labelled figures, and it renders its SVGs to PNG, which Openverse's results cannot offer.
 * Openverse follows because its photographs are broader. It is asked to leave Wikimedia out, so the
 * hand-over repeats nothing.
 *
 * Neither source needs a key. Bing's search APIs were retired in August 2025 and Google's Custom
 * Search JSON API is closed to new customers — and either one's key, shipped in an APK, would be a
 * key anyone could lift.
 *
 * Licensing is left to the person inserting the picture: nothing about the source is written onto
 * the page.
 */
class OnlineImageSearch internal constructor(
    private val http: PictureHttp = PictureHttp(),
    private val commons: suspend (query: String, offset: Int) -> SourceResult =
        WikimediaCommonsClient(http)::search,
    private val openverse: suspend (query: String, page: Int) -> SourceResult =
        OpenverseClient(http)::search,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : OnlineImages {

    /**
     * Decoded previews by image id, bounded by bytes rather than count.
     *
     * Scrolling back up the grid should not re-download what was on screen a moment ago — Openverse
     * counts each thumbnail against a daily quota — but nor should a long session keep every page of
     * results in memory. Lazy, so the search logic can be exercised on a plain JVM.
     */
    private val thumbnails by lazy {
        object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_BYTES) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }
    }

    /** A grid lays out twenty tiles at once; twenty sockets at once is not what that should mean. */
    private val fetches = Semaphore(PARALLEL_FETCHES)

    override suspend fun search(query: String, from: SearchCursor?): OnlineImageSearchResult =
        when (val cursor = from ?: SearchCursor.Commons(offset = 0)) {
            is SearchCursor.Commons -> when (val result = commons(query, cursor.offset)) {
                is SourceResult.Page -> {
                    val next = nextAfterCommons(cursor, result)
                    // A page with nothing to show — Commons had nothing at all, say — would leave
                    // the grid empty and asking for more. Openverse answers in its place.
                    if (result.images.isEmpty() && next is SearchCursor.Openverse) {
                        search(query, next)
                    } else {
                        OnlineImageSearchResult.Found(result.images, next)
                    }
                }
                // Commons being down or busy is no reason to show nothing: Openverse carries on.
                SourceResult.RateLimited, SourceResult.Unreachable ->
                    search(query, SearchCursor.Openverse(page = 1))
            }
            is SearchCursor.Openverse -> when (val result = openverse(query, cursor.page)) {
                is SourceResult.Page -> OnlineImageSearchResult.Found(
                    images = result.images,
                    next = if (result.hasMore) SearchCursor.Openverse(cursor.page + 1) else null,
                )
                SourceResult.RateLimited -> OnlineImageSearchResult.RateLimited
                SourceResult.Unreachable -> OnlineImageSearchResult.Unreachable
            }
        }

    /**
     * The source's own grid rendition first, the original when it has none.
     *
     * Openverse's thumbnail service cannot reach every provider: on 2026-10-03 every Wikimedia hit
     * answered `424 Thumbnail unavailable from provider.`, while the same originals downloaded
     * directly. The original is capped lower here than for an insert, because this is a tile.
     */
    override suspend fun thumbnail(image: OnlineImage): Bitmap? {
        thumbnails.get(image.id)?.let { return it }
        val bytes = fetches.withPermit {
            http.image(image.thumbnail, MAX_THUMBNAIL_BYTES)
                ?: http.image(image.url, MAX_PREVIEW_ORIGINAL_BYTES)
        } ?: return null
        val bitmap = withContext(io) { decodePreview(bytes) } ?: return null
        thumbnails.put(image.id, bitmap)
        return bitmap
    }

    /**
     * [OnlineImage.url] first, because a grid thumbnail stretched across half a page looks it. The
     * thumbnail is the fallback for files that are gone, too large, or served as something that is
     * not a picture — a removed Flickr photo, say, answers with an HTML page.
     */
    override suspend fun download(image: OnlineImage): ByteArray? =
        fetches.withPermit {
            http.image(image.url, MAX_ORIGINAL_BYTES) ?: http.image(image.thumbnail, MAX_THUMBNAIL_BYTES)
        }

    /** Decoded at grid size: a tile is ~160 dp, and a 600 px JPEG is 1.4 MB as a bitmap. */
    private fun decodePreview(bytes: ByteArray): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(bytes)) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > PREVIEW_MAX_DIMENSION) {
                val scale = PREVIEW_MAX_DIMENSION.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
    }.getOrNull()

    internal companion object {
        /**
         * Commons pages shown before relevance is judged at all.
         *
         * The judgement is crude — words of the query in the title or English description — and
         * misses real hits named another way: "Krebs cycle" finds forty diagrams titled "Citric
         * acid cycle" before any says "Krebs". Commons' own ranking is better than the test, so
         * the first pages are always its.
         */
        const val COMMONS_TRUSTED_PAGES = 3

        /**
         * Consecutive mostly-off-topic pages that end Commons. Two, because one is noise: "red fox"
         * dips to 7 of 20 on its seventh page and is back to 10 on its eighth.
         */
        const val COMMONS_OFF_TOPIC_RUN = 2

        /** Where Commons gives way however on-topic it still looks — ten pages of one source. */
        const val COMMONS_MAX_RESULTS = 200

        /**
         * Where a Commons page leads: more Commons, or Openverse.
         *
         * Commons hands over when it runs out, when [COMMONS_MAX_RESULTS] is reached, or when
         * [COMMONS_OFF_TOPIC_RUN] pages in a row, the last of them past the trusted ones, had fewer
         * than half their hits on topic. Over the eight pages measured on 2026-10-03, that kept
         * "meiosis", "red fox" and "eiffel tower" on Commons throughout, moved "Krebs cycle" to
         * Openverse after its sixth page, and "quadratic formula" — 13, 3 and 4 of 20 on topic —
         * after its third.
         */
        internal fun nextAfterCommons(cursor: SearchCursor.Commons, page: SourceResult.Page): SearchCursor {
            val offTopic = page.images.isNotEmpty() && page.onTopic * 2 < page.images.size
            val offRuns = if (offTopic) cursor.offRuns + 1 else 0
            val nextOffset = cursor.offset + WikimediaCommonsClient.PAGE_SIZE
            val pagesShown = nextOffset / WikimediaCommonsClient.PAGE_SIZE
            val drifted = pagesShown >= COMMONS_TRUSTED_PAGES && offRuns >= COMMONS_OFF_TOPIC_RUN
            return if (!page.hasMore || drifted || nextOffset >= COMMONS_MAX_RESULTS) {
                SearchCursor.Openverse(page = 1)
            } else {
                SearchCursor.Commons(nextOffset, offRuns)
            }
        }

        private const val PARALLEL_FETCHES = 4

        /**
         * A file larger than this is not worth fetching whole. [AttachmentStore] scales whatever
         * arrives down to its own maximum, so past this the extra bytes would only be thrown away —
         * the thumbnail is used instead.
         */
        private const val MAX_ORIGINAL_BYTES = 30 * 1024 * 1024
        private const val MAX_THUMBNAIL_BYTES = 2 * 1024 * 1024
        private const val MAX_PREVIEW_ORIGINAL_BYTES = 8 * 1024 * 1024
        private const val PREVIEW_MAX_DIMENSION = 360
        private const val THUMBNAIL_CACHE_BYTES = 16 * 1024 * 1024
    }
}
