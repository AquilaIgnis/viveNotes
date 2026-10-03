package com.vivenotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shapes taken from what the Commons API returned on 2026-10-03. */
class WikimediaCommonsClientTest {

    @Test
    fun `a search asks for pictures only, a grid-sized rendition, and the English description`() {
        val url = WikimediaCommonsClient.searchUrl(" meiosis ", 40)
        assertTrue(url.startsWith("https://commons.wikimedia.org/w/api.php?action=query&format=json"))
        assertTrue(url.contains("&gsrnamespace=6&"))
        assertTrue(url.contains("&gsrsearch=meiosis+filetype%3Abitmap%7Cdrawing&"))
        assertTrue(url.contains("&gsrlimit=20&gsroffset=40&"))
        assertTrue(url.contains("&iiurlwidth=330&"))
        assertTrue(url.endsWith("&iiextmetadatafilter=ImageDescription&iiextmetadatalanguage=en"))
    }

    @Test
    fun `hits come back in rank order, not the order the generator lists them`() {
        val page = WikimediaCommonsClient.parseSearch(RESPONSE, "meiosis")!!
        assertEquals(listOf("commons:1", "commons:2", "commons:3"), page.images.map { it.id })
        assertEquals("Meiosis Stages", page.images[0].title)
        assertEquals(ImageSource.WikimediaCommons, page.images[0].source)
        assertTrue(page.hasMore)
    }

    @Test
    fun `a file with no description still parses — extmetadata is then an empty array`() {
        val page = WikimediaCommonsClient.parseSearch(RESPONSE, "meiosis")!!
        assertEquals("Cell division 2", page.images[2].title)
    }

    @Test
    fun `on-topic counts title and description matches`() {
        // 1: title. 2: only the description says "meiosis". 3: neither.
        assertEquals(2, WikimediaCommonsClient.parseSearch(RESPONSE, "meiosis")!!.onTopic)
    }

    @Test
    fun `no continuation means Commons has nothing more`() {
        val page = WikimediaCommonsClient.parseSearch("""{"batchcomplete":true}""", "zzz")!!
        assertTrue(page.images.isEmpty())
        assertFalse(page.hasMore)
    }

    @Test
    fun `a body that is not JSON is not a result list`() {
        assertNull(WikimediaCommonsClient.parseSearch("<html>Too many requests</html>", "cat"))
    }

    @Test
    fun `a decodable original of a sensible size is inserted as it is`() {
        assertEquals(ORIGINAL, WikimediaCommonsClient.insertUrl("image/jpeg", 1280, 720, ORIGINAL, THUMB))
    }

    @Test
    fun `an SVG is inserted as the widest standard rendition`() {
        assertEquals(
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/7/74/A.svg/1920px-A.svg.png?utm_source=x",
            WikimediaCommonsClient.insertUrl(
                "image/svg+xml", 400, 400, "https://upload.wikimedia.org/wikipedia/commons/7/74/A.svg",
                "https://thumb.wikimedia.org/wikipedia/commons/thumb/7/74/A.svg/330px-A.svg.png?utm_source=x",
            ),
        )
    }

    @Test
    fun `a huge photograph is inserted as a rendition, not fetched whole`() {
        assertEquals(
            THUMB.replace("/330px-", "/1920px-"),
            WikimediaCommonsClient.insertUrl("image/jpeg", 14600, 5000, ORIGINAL, THUMB),
        )
    }

    @Test
    fun `a bitmap is never asked for at more than its own width`() {
        // Wikimedia refuses an upscale, and refuses any width off its standard list.
        assertEquals(
            THUMB.replace("/330px-", "/960px-"),
            WikimediaCommonsClient.insertUrl("image/tiff", 1000, 800, ORIGINAL, THUMB),
        )
    }

    @Test
    fun `relevance ignores case and accents and accepts a word's start`() {
        assertTrue(WikimediaCommonsClient.isOnTopic("revolution", "La Révolution française"))
        assertTrue(WikimediaCommonsClient.isOnTopic("red fox", "Two Red Foxes in snow"))
        assertFalse(WikimediaCommonsClient.isOnTopic("red fox", "Vulpes vulpes"))
        assertFalse(WikimediaCommonsClient.isOnTopic("cat", "Concatenation"))
    }

    private companion object {
        const val ORIGINAL = "https://upload.wikimedia.org/wikipedia/commons/f/fa/Grasshopper.jpg"
        const val THUMB =
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/f/fa/Grasshopper.jpg/330px-Grasshopper.jpg"

        val RESPONSE = """
            {"batchcomplete":true,"continue":{"gsroffset":20,"continue":"gsroffset||"},"query":{"pages":[
              {"pageid":3,"ns":6,"title":"File:Cell division 2.png","index":3,"imageinfo":[
                {"size":1,"width":474,"height":506,"thumburl":"https://thumb.wikimedia.org/t3/330px-c.png",
                 "url":"https://upload.wikimedia.org/c.png","mime":"image/png","extmetadata":[]}]},
              {"pageid":1,"ns":6,"title":"File:Meiosis Stages.svg","index":1,"imageinfo":[
                {"size":1,"width":2809,"height":800,"thumburl":"https://thumb.wikimedia.org/t1/330px-a.svg.png",
                 "url":"https://upload.wikimedia.org/a.svg","mime":"image/svg+xml",
                 "extmetadata":{"ImageDescription":{"value":"Stages","source":"commons-desc-page"}}}]},
              {"pageid":2,"ns":6,"title":"File:Prophase I.jpg","index":2,"imageinfo":[
                {"size":1,"width":657,"height":723,"thumburl":"https://thumb.wikimedia.org/t2/330px-b.jpg",
                 "url":"https://upload.wikimedia.org/b.jpg","mime":"image/jpeg",
                 "extmetadata":{"ImageDescription":{"value":"<i>Stages of</i> <b>meiosis</b>","source":"x"}}}]},
              {"pageid":4,"ns":6,"title":"File:No info.jpg","index":4}
            ]}}
        """.trimIndent()
    }
}
