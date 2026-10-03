package com.vivenotes.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Wikimedia Commons' own search, through the MediaWiki API: free, keyless, and asking only that
 * clients identify themselves, which [PictureHttp] does.
 *
 * First in [OnlineImageSearch]. Two things Openverse cannot do are why: Commons renders its SVG
 * diagrams to PNG at any standard width, and it answers from the live wiki rather than an index
 * refreshed now and then.
 */
internal class WikimediaCommonsClient(private val http: PictureHttp) {

    /** [offset] counts results, not pages. */
    suspend fun search(query: String, offset: Int): SourceResult =
        when (val answer = http.text(searchUrl(query, offset))) {
            is PictureHttp.Text.Ok -> parseSearch(answer.body, query) ?: SourceResult.Unreachable
            PictureHttp.Text.RateLimited -> SourceResult.RateLimited
            PictureHttp.Text.Failed -> SourceResult.Unreachable
        }

    companion object {
        private const val ENDPOINT = "https://commons.wikimedia.org/w/api.php"

        const val PAGE_SIZE = 20

        /** Commons' standard thumbnail width nearest a grid tile. */
        private const val GRID_WIDTH = 330

        /**
         * Widths Wikimedia renders on request. Others are refused with a 400 — on 2026-10-03, 1024
         * was refused while 960 and 1280 were served — so a rendition is always one of these.
         */
        private val STANDARD_WIDTHS = listOf(330, 500, 960, 1280, 1920)

        /** Formats inserted as they are. Anything else — SVG, TIFF — is inserted as a rendition. */
        private val DECODABLE_MIMES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

        private val json = Json { ignoreUnknownKeys = true }

        fun searchUrl(query: String, offset: Int): String {
            val params = linkedMapOf(
                "action" to "query",
                "format" to "json",
                "formatversion" to "2",
                "generator" to "search",
                // The File: namespace, and only pictures in it — not PDFs, audio or video.
                "gsrnamespace" to "6",
                "gsrsearch" to query.trim() + " filetype:bitmap|drawing",
                "gsrlimit" to PAGE_SIZE.toString(),
                "gsroffset" to offset.toString(),
                "prop" to "imageinfo",
                "iiprop" to "url|size|mime|extmetadata",
                "iiurlwidth" to GRID_WIDTH.toString(),
                // Only what [isOnTopic] reads; the full set is several kilobytes a file.
                "iiextmetadatafilter" to "ImageDescription",
                "iiextmetadatalanguage" to "en",
            )
            return ENDPOINT + "?" + params.entries.joinToString("&") { (key, value) ->
                key + "=" + URLEncoder.encode(value, Charsets.UTF_8)
            }
        }

        /** Null when [body] is not a result list at all. Files without a rendition are dropped. */
        fun parseSearch(body: String, query: String): SourceResult.Page? {
            val response = runCatching { json.decodeFromString<SearchResponse>(body) }.getOrNull()
                ?: return null
            var onTopic = 0
            // A generator hands its pages back in no particular order; `index` is the rank.
            val images = response.query?.pages.orEmpty().sortedBy { it.index }.mapNotNull { page ->
                val info = page.imageinfo.firstOrNull() ?: return@mapNotNull null
                val thumbnail = info.thumburl?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val original = info.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = page.title.removePrefix("File:").substringBeforeLast('.')
                if (isOnTopic(query, title + " " + descriptionOf(info.extmetadata))) onTopic++
                OnlineImage(
                    id = "commons:${page.pageid}",
                    title = title,
                    url = insertUrl(info.mime, info.width, info.height, original, thumbnail),
                    thumbnail = thumbnail,
                    width = info.width,
                    height = info.height,
                    source = ImageSource.WikimediaCommons,
                )
            }
            return SourceResult.Page(images, hasMore = response.`continue` != null, onTopic = onTopic)
        }

        /**
         * The original when Android can decode it and [AttachmentStore] would keep it whole;
         * otherwise a standard-width rendition, which is smaller to fetch and the same once
         * imported. An SVG is always a rendition: there is no pixel width to stay under.
         */
        fun insertUrl(mime: String?, width: Int, height: Int, original: String, thumbnail: String): String {
            if (mime in DECODABLE_MIMES && maxOf(width, height) <= AttachmentStore.MAX_DIMENSION) {
                return original
            }
            val widest = STANDARD_WIDTHS.last()
            val target = if (mime == "image/svg+xml") {
                widest
            } else {
                // A bitmap is never scaled up; asking for more than it has is refused.
                STANDARD_WIDTHS.lastOrNull { it <= minOf(width, widest) }
            } ?: return original
            return renditionOf(thumbnail, target) ?: original
        }

        /** [thumbnail] at another width — Wikimedia's `.../<width>px-<name>` scheme. */
        private fun renditionOf(thumbnail: String, width: Int): String? {
            val match = RENDITION_WIDTH.findAll(thumbnail).lastOrNull() ?: return null
            return thumbnail.replaceRange(match.groups[1]!!.range, width.toString())
        }

        private val RENDITION_WIDTH = Regex("""/(\d+)px-""")

        /**
         * Whether [text] names every word of [query], as a word or the start of one ("fox" finds
         * "foxes"), ignoring case and accents.
         *
         * A proxy for relevance, since the API no longer reports a score. It undercounts — other
         * languages, scientific names and synonyms all miss — which is why [OnlineImageSearch]
         * trusts Commons' ranking for the first pages and only acts on a run of misses.
         */
        fun isOnTopic(query: String, text: String): Boolean {
            val words = wordsOf(text)
            return wordsOf(query).all { wanted -> words.any { it.startsWith(wanted) } }
        }

        private fun wordsOf(text: String): List<String> =
            Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
                .replace(COMBINING_MARKS, "")
                .split(NON_WORD)
                .filter { it.isNotEmpty() }

        private val COMBINING_MARKS = Regex("""\p{Mn}+""")
        private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
        private val TAGS = Regex("""<[^>]*>""")

        /**
         * The English description, as text. `extmetadata` is an object when there is any and an
         * empty *array* when there is none — PHP's empty array — so it is read loosely.
         */
        private fun descriptionOf(extmetadata: JsonElement?): String {
            val description = (extmetadata as? JsonObject)?.get("ImageDescription") as? JsonObject
            val value = (description?.get("value") as? JsonPrimitive)?.takeIf { it.isString }?.content
            return value?.replace(TAGS, " ").orEmpty()
        }
    }

    @Serializable
    private data class SearchResponse(
        val `continue`: JsonObject? = null,
        val query: Query? = null,
    )

    @Serializable
    private data class Query(val pages: List<Page> = emptyList())

    @Serializable
    private data class Page(
        val pageid: Long,
        val title: String,
        val index: Int = Int.MAX_VALUE,
        val imageinfo: List<ImageInfo> = emptyList(),
    )

    @Serializable
    private data class ImageInfo(
        val url: String? = null,
        val thumburl: String? = null,
        val mime: String? = null,
        val width: Int = 0,
        val height: Int = 0,
        val extmetadata: JsonElement? = null,
    )
}
