package com.vivenotes.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where Wikimedia Commons hands over to Openverse. */
class OnlineImageSearchTest {

    @Test
    fun `an on-topic page stays on Commons`() {
        val next = OnlineImageSearch.nextAfterCommons(SearchCursor.Commons(0), page(onTopic = 20))
        assertEquals(SearchCursor.Commons(20, offRuns = 0), next)
    }

    @Test
    fun `Commons running out hands over at once`() {
        val next = OnlineImageSearch.nextAfterCommons(SearchCursor.Commons(0), page(onTopic = 20, hasMore = false))
        assertEquals(SearchCursor.Openverse(1), next)
    }

    @Test
    fun `the first pages are trusted however off-topic they look`() {
        // "Krebs cycle": its first two pages are diagrams titled "Citric acid cycle".
        var cursor: SearchCursor = SearchCursor.Commons(0)
        repeat(2) { cursor = OnlineImageSearch.nextAfterCommons(cursor as SearchCursor.Commons, page(onTopic = 2)) }
        assertEquals(SearchCursor.Commons(40, offRuns = 2), cursor)
    }

    @Test
    fun `two off-topic pages in a row past the trusted ones hand over`() {
        // "quadratic formula": 13, 3, 4 of 20.
        var cursor: SearchCursor = SearchCursor.Commons(0)
        listOf(13, 3, 4).forEach {
            cursor = OnlineImageSearch.nextAfterCommons(cursor as SearchCursor.Commons, page(onTopic = it))
        }
        assertEquals(SearchCursor.Openverse(1), cursor)
    }

    @Test
    fun `one off-topic page is noise, not a hand-over`() {
        // "red fox": 7 of 20 on its seventh page, 10 on its eighth.
        val dipped = OnlineImageSearch.nextAfterCommons(SearchCursor.Commons(120), page(onTopic = 7))
        assertEquals(SearchCursor.Commons(140, offRuns = 1), dipped)
        val recovered = OnlineImageSearch.nextAfterCommons(dipped as SearchCursor.Commons, page(onTopic = 10))
        assertEquals(SearchCursor.Commons(160, offRuns = 0), recovered)
    }

    @Test
    fun `Commons hands over at the cap however on-topic it still is`() {
        val next = OnlineImageSearch.nextAfterCommons(SearchCursor.Commons(180), page(onTopic = 20))
        assertEquals(SearchCursor.Openverse(1), next)
    }

    @Test
    fun `a search starts on Commons and pages through Openverse to the end`() = runBlocking {
        val search = OnlineImageSearch(
            commons = { _, _ -> page(onTopic = 20, hasMore = false) },
            openverse = { _, page -> page(onTopic = 20, hasMore = page < 2, source = ImageSource.Openverse) },
        )
        val first = search.search("x", null) as OnlineImageSearchResult.Found
        assertEquals(SearchCursor.Openverse(1), first.next)
        val second = search.search("x", first.next) as OnlineImageSearchResult.Found
        assertEquals(SearchCursor.Openverse(2), second.next)
        val last = search.search("x", second.next) as OnlineImageSearchResult.Found
        assertNull(last.next)
    }

    @Test
    fun `Commons finding nothing is answered by Openverse, not an empty grid`() = runBlocking {
        val search = OnlineImageSearch(
            commons = { _, _ -> SourceResult.Page(emptyList(), hasMore = false, onTopic = 0) },
            openverse = { _, _ -> page(onTopic = 20, hasMore = true, source = ImageSource.Openverse) },
        )
        val found = search.search("x", null) as OnlineImageSearchResult.Found
        assertEquals(ImageSource.Openverse, found.images.first().source)
        assertEquals(SearchCursor.Openverse(2), found.next)
    }

    @Test
    fun `Commons being down is answered by Openverse`() = runBlocking {
        val search = OnlineImageSearch(
            commons = { _, _ -> SourceResult.Unreachable },
            openverse = { _, _ -> page(onTopic = 20, hasMore = false, source = ImageSource.Openverse) },
        )
        val found = search.search("x", null) as OnlineImageSearchResult.Found
        assertEquals(ImageSource.Openverse, found.images.first().source)
    }

    @Test
    fun `Openverse rate limiting is reported`() = runBlocking {
        val search = OnlineImageSearch(
            commons = { _, _ -> SourceResult.Unreachable },
            openverse = { _, _ -> SourceResult.RateLimited },
        )
        assertEquals(OnlineImageSearchResult.RateLimited, search.search("x", null))
    }

    private fun page(
        onTopic: Int,
        hasMore: Boolean = true,
        source: ImageSource = ImageSource.WikimediaCommons,
    ): SourceResult.Page =
        SourceResult.Page(
            images = List(20) { index ->
                OnlineImage(
                    id = "$index",
                    title = "",
                    url = "https://example.org/$index.jpg",
                    thumbnail = "https://example.org/$index/thumb",
                    width = 1,
                    height = 1,
                    source = source,
                )
            },
            hasMore = hasMore,
            onTopic = onTopic,
        )
}
