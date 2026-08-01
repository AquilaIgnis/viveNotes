package com.vivenotes.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the seam that lets storage and transport use different formats — the thing that makes
 * adding MessagePack for the sync protocol a new codec rather than a change to the model.
 */
class DocumentCodecTest {

    private fun sample() = PageDoc(
        outlines = listOf(
            Outline.Text(
                id = "o1",
                x = 12f,
                y = 40f,
                width = 640f,
                minHeight = 120f,
                blocks = listOf(
                    Block(id = "b1", type = BlockType.Heading1, runs = listOf(Run("Title", setOf(Mark.Bold)))),
                    Block(id = "b2", type = BlockType.Bullet, indent = 1, runs = listOf(Run("body"))),
                ),
            ),
            Outline.Image(id = "i1", attachmentId = "a1", height = 200f),
        ),
    )

    @Test
    fun `json codec round trips through text and through bytes`() {
        val doc = sample()

        assertEquals(doc, JsonDocumentCodec.decodeFromString(JsonDocumentCodec.encodeToString(doc)))
        assertEquals(doc, JsonDocumentCodec.decode(JsonDocumentCodec.encode(doc)))
    }

    @Test
    fun `the text and byte forms of a text codec agree`() {
        val doc = sample()

        assertArrayEquals(
            JsonDocumentCodec.encodeToString(doc).encodeToByteArray(),
            JsonDocumentCodec.encode(doc),
        )
    }

    @Test
    fun `codecs are addressable by a stable id`() {
        // The id is written into every stored row, so changing it silently orphans documents.
        assertEquals("json/1", JsonDocumentCodec.id)
        assertEquals(JsonDocumentCodec, DocumentCodecs.byId("json/1"))
        assertNull(DocumentCodecs.byId("msgpack/1"))
    }

    /**
     * Stands in for MessagePack: a format that is genuinely binary rather than text. Proves the
     * abstraction does not quietly assume a string representation, which is the mistake that would
     * only surface once a real binary codec was wired in.
     */
    private object ReversedBytesCodec : DocumentCodec {
        override val id = "test-binary/1"
        override fun encode(doc: PageDoc): ByteArray =
            JsonDocumentCodec.encode(doc).reversedArray()

        override fun decode(bytes: ByteArray): PageDoc =
            JsonDocumentCodec.decode(bytes.reversedArray())
    }

    @Test
    fun `a binary codec round trips without going through a string`() {
        val doc = sample()

        val bytes = ReversedBytesCodec.encode(doc)

        assertTrue("expected an encoding unlike the JSON one", !bytes.contentEquals(JsonDocumentCodec.encode(doc)))
        assertEquals(doc, ReversedBytesCodec.decode(bytes))
    }

    @Test
    fun `documents encoded by one codec are unreadable by another`() {
        // Why every stored row records the codec that wrote it: guessing would corrupt silently.
        val bytes = ReversedBytesCodec.encode(sample())

        val decodedByWrongCodec = runCatching { JsonDocumentCodec.decode(bytes) }

        assertTrue("a mismatched codec should fail loudly", decodedByWrongCodec.isFailure)
    }
}
