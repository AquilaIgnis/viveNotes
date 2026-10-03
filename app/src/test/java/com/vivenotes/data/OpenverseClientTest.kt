package com.vivenotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The request and response shapes, checked against what the live API returned on 2026-10-03. */
class OpenverseClientTest {

    @Test
    fun `a search asks for the anonymous page size, decodable formats, and no Wikimedia`() {
        val url = OpenverseClient.searchUrl("  red fox ", 3)
        assertEquals(
            "https://api.openverse.org/v1/images/?q=red+fox&page=3&page_size=20" +
                "&extension=jpg,jpeg,png,gif,webp&excluded_source=wikimedia",
            url,
        )
    }

    @Test
    fun `a query is encoded rather than spliced`() {
        assertTrue(OpenverseClient.searchUrl("cats&page_size=500", 1).contains("q=cats%26page_size%3D500&"))
    }

    @Test
    fun `hits are read and ones without an address are dropped`() {
        val found = OpenverseClient.parseSearch(
            """
            {"result_count":240,"page_count":12,"page_size":20,"page":1,"results":[
              {"id":"a","title":"Cat Fish 2","url":"https://live.staticflickr.com/a.jpg",
               "thumbnail":"https://api.openverse.org/v1/images/a/thumb/","width":716,"height":1024,
               "creator":"admiller","license":"by","tags":[]},
              {"id":"b","title":null,"url":null,"thumbnail":"https://api.openverse.org/v1/images/b/thumb/"},
              {"id":"c","url":"https://example.org/c.png","thumbnail":"https://api.openverse.org/v1/images/c/thumb/"}
            ]}
            """.trimIndent(),
        )!!
        assertEquals(listOf("a", "c"), found.images.map { it.id })
        assertEquals("Cat Fish 2", found.images[0].title)
        assertEquals(716, found.images[0].width)
        assertEquals(ImageSource.Openverse, found.images[0].source)
        assertEquals("", found.images[1].title)
        assertEquals(0, found.images[1].width)
        assertTrue(found.hasMore)
    }

    @Test
    fun `the last page has no more`() {
        val found = OpenverseClient.parseSearch("""{"page_count":3,"page":3,"results":[]}""")!!
        assertFalse(found.hasMore)
    }

    @Test
    fun `paging stops at the anonymous depth even when more pages are claimed`() {
        // 12 × 20 = 240: asking for page 13 is refused outright, not answered short.
        val found = OpenverseClient.parseSearch("""{"page_count":50,"page":12,"results":[]}""")!!
        assertFalse(found.hasMore)
    }

    @Test
    fun `a body that is not JSON is not a result list`() {
        assertNull(OpenverseClient.parseSearch("<html>rate limited</html>"))
    }

    @Test
    fun `an error object reads as nothing found rather than as more to fetch`() {
        val found = OpenverseClient.parseSearch("""{"detail":"pagination depth may not exceed 240"}""")!!
        assertTrue(found.images.isEmpty())
        assertFalse(found.hasMore)
    }
}
