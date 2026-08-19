package com.vivenotes.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoLinkTest {

    private val id = "dQw4w9WgXcQ"

    @Test
    fun `reads every url form youtube hands out`() {
        val urls = listOf(
            "https://www.youtube.com/watch?v=$id",
            "https://youtube.com/watch?v=$id&t=90s",
            "https://m.youtube.com/watch?feature=share&v=$id",
            "https://music.youtube.com/watch?v=$id",
            "https://youtu.be/$id",
            "https://youtu.be/$id?t=42",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/embed/$id",
            "https://www.youtube.com/live/$id",
            "https://www.youtube-nocookie.com/embed/$id",
            "http://youtube.com/watch?v=$id",
            "youtube.com/watch?v=$id",
            "youtu.be/$id",
        )

        urls.forEach { url ->
            assertEquals("failed on $url", id, youTubeVideoId(url))
        }
    }

    @Test
    fun `refuses anything that is not a youtube video`() {
        listOf(
            "https://vimeo.com/watch?v=$id",
            "https://notyoutube.com/watch?v=$id",
            // Userinfo before the @ makes the real host `evil.example`.
            "https://youtube.com@evil.example/watch?v=$id",
            "https://www.youtube.com/watch?v=tooshort",
            "https://www.youtube.com/watch?v=$id!!extra",
            "https://www.youtube.com/results?search_query=cats",
            "https://www.youtube.com/@somechannel",
            "https://www.youtube.com/",
            "not a url at all",
        ).forEach { url ->
            assertNull("accepted $url", youTubeVideoId(url))
        }
    }

    @Test
    fun `an id may never escape its own characters`() {
        // The id names a file in the thumbnail cache, so traversal has to fail as an id first.
        assertNull(youTubeVideoId("https://youtu.be/../../etc"))
        assertNull(youTubeVideoId("https://youtu.be/..%2F..%2Fx"))
    }

    @Test
    fun `finds a link in the middle of a sentence and reports its exact range`() {
        val text = "watch https://youtu.be/$id later"
        val found = findVideoLinks(text)

        assertEquals(1, found.size)
        assertEquals(id, found[0].videoId)
        assertEquals("https://youtu.be/$id", text.substring(found[0].start, found[0].end))
    }

    @Test
    fun `discounts the punctuation of the sentence around the link`() {
        val cases = mapOf(
            "see https://youtu.be/$id." to "https://youtu.be/$id",
            "see (https://youtu.be/$id)" to "https://youtu.be/$id",
            "see \"https://youtu.be/$id\"," to "https://youtu.be/$id",
            "see <https://youtu.be/$id>" to "https://youtu.be/$id",
        )

        cases.forEach { (text, expected) ->
            val found = findVideoLinks(text)
            assertEquals("failed on $text", 1, found.size)
            assertEquals(expected, text.substring(found[0].start, found[0].end))
        }
    }

    @Test
    fun `keeps the pasted url rather than a canonical one, so a timestamp survives`() {
        val found = findVideoLinks("https://youtu.be/$id?t=90")

        assertEquals("https://youtu.be/$id?t=90", found.single().url)
    }

    @Test
    fun `finds several links across lines`() {
        val text = "one https://youtu.be/$id\ntwo https://www.youtube.com/watch?v=aaaaaaaaaaa"
        val found = findVideoLinks(text)

        assertEquals(listOf(id, "aaaaaaaaaaa"), found.map { it.videoId })
        // Ranges stay in reading order, which is what the editor's span application assumes.
        assertEquals(true, found[0].end <= found[1].start)
    }

    @Test
    fun `text with no link finds nothing`() {
        assertEquals(emptyList<VideoLink>(), findVideoLinks("a paragraph about youtube.com in general"))
    }
}
