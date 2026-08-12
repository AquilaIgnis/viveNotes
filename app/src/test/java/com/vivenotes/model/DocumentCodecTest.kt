package com.vivenotes.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the seam that lets storage and transport use different formats — the thing that makes
 * adding MessagePack for the sync protocol a new codec rather than a change to the model.
 *
 * The *binary* half of that seam is [BinaryCodecTest]'s, which exercises it through CBOR. This file
 * once carried a `ReversedBytesCodec` standing in for "a genuinely binary format, someday"; CBOR is
 * that format, so the stand-in and the two tests built on it were removed rather than kept beside a
 * real codec proving the same thing twice.
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

    /**
     * The registry, for every codec there is.
     *
     * The id is written into every stored row, so changing one silently orphans documents — which is
     * why the literals are spelled out here rather than read back off the objects.
     */
    @Test
    fun `codecs are addressable by a stable id`() {
        assertEquals("json/1", JsonDocumentCodec.id)
        assertEquals("cbor/1", CborDocumentCodec.id)
        assertEquals(JsonDocumentCodec, DocumentCodecs.byId("json/1"))
        assertEquals(CborDocumentCodec, DocumentCodecs.byId("cbor/1"))
        // An id from a build this one does not have degrades to null rather than to a wrong guess.
        assertNull(DocumentCodecs.byId("msgpack/1"))
    }
}
