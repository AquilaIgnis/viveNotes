package com.vivenotes.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/**
 * Openverse: openly licensed pictures from Flickr, museums and others, searched without a key.
 *
 * Second in [OnlineImageSearch], after Wikimedia Commons, so Wikimedia is excluded here — Commons
 * has already shown it, and better.
 *
 * Its anonymous limits, as the API reported them on 2026-10-03: 20 searches a minute and 200 a day
 * per address, 1000 thumbnails a day, at most [PAGE_SIZE] results a page and [MAX_DEPTH] deep. The
 * page size and depth are enforced by refusal, not truncation, so asking past either fails.
 */
internal class OpenverseClient(private val http: PictureHttp) {

    /** [page] counts from 1. */
    suspend fun search(query: String, page: Int): SourceResult =
        when (val answer = http.text(searchUrl(query, page))) {
            is PictureHttp.Text.Ok -> parseSearch(answer.body) ?: SourceResult.Unreachable
            PictureHttp.Text.RateLimited -> SourceResult.RateLimited
            PictureHttp.Text.Failed -> SourceResult.Unreachable
        }

    companion object {
        private const val ENDPOINT = "https://api.openverse.org/v1/images/"

        /** The most an anonymous request may ask for. */
        const val PAGE_SIZE = 20

        /** How far into the results an anonymous request may page. */
        const val MAX_DEPTH = 240

        /**
         * Formats [android.graphics.ImageDecoder] reads. SVG is the one that matters: it would sit
         * in the grid as tiles that can never be inserted.
         */
        private const val EXTENSIONS = "jpg,jpeg,png,gif,webp"

        private val json = Json { ignoreUnknownKeys = true }

        fun searchUrl(query: String, page: Int): String =
            ENDPOINT +
                "?q=" + URLEncoder.encode(query.trim(), Charsets.UTF_8) +
                "&page=" + page +
                "&page_size=" + PAGE_SIZE +
                "&extension=" + EXTENSIONS +
                "&excluded_source=wikimedia"

        /** Null when [body] is not a result list at all. Hits missing an address are dropped. */
        fun parseSearch(body: String): SourceResult.Page? {
            val response = runCatching { json.decodeFromString<SearchResponse>(body) }.getOrNull()
                ?: return null
            val images = response.results.mapNotNull { hit ->
                val url = hit.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val thumbnail = hit.thumbnail?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                OnlineImage(
                    id = hit.id,
                    title = hit.title.orEmpty(),
                    url = url,
                    thumbnail = thumbnail,
                    width = hit.width ?: 0,
                    height = hit.height ?: 0,
                    source = ImageSource.Openverse,
                )
            }
            val hasMore = response.page < response.pageCount && response.page * PAGE_SIZE < MAX_DEPTH
            return SourceResult.Page(images, hasMore, onTopic = images.size)
        }
    }

    @Serializable
    private data class SearchResponse(
        val page: Int = 1,
        @SerialName("page_count") val pageCount: Int = 0,
        val results: List<Hit> = emptyList(),
    )

    @Serializable
    private data class Hit(
        val id: String,
        val title: String? = null,
        val url: String? = null,
        val thumbnail: String? = null,
        val width: Int? = null,
        val height: Int? = null,
    )
}
