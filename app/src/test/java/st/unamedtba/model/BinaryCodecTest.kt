package st.unamedtba.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Everything a binary codec has to survive before it is safe to store notes in.
 *
 * The two ways a format quietly loses data are polymorphism — sealed [Outline] and [Mark] types
 * being flattened or mis-tagged — and unknown fields, where a document written by a newer build
 * must degrade rather than fail to open. JSON gets both right; a replacement is only equivalent if
 * it does too.
 */
class BinaryCodecTest {

    private fun everyFeature() = PageDoc(
        outlines = listOf(
            Outline.Text(
                id = "o1",
                x = 12f,
                y = 40f,
                width = 640f,
                minHeight = 120f,
                blocks = listOf(
                    Block(
                        id = "b1",
                        type = BlockType.Heading1,
                        align = Align.Center,
                        runs = listOf(Run("Title", setOf(Mark.Bold, Mark.Italic))),
                    ),
                    Block(
                        id = "b2",
                        type = BlockType.Bullet,
                        indent = 2,
                        runs = listOf(
                            Run("plain"),
                            Run("colour", setOf(Mark.TextColor(0xFFFF0000.toInt()))),
                            Run("mark", setOf(Mark.Highlight(0x66FFEB3B))),
                            Run("size", setOf(Mark.FontSize(24))),
                            Run("font", setOf(Mark.FontFamily("inter"))),
                            Run("link", setOf(Mark.Link("https://example.com"))),
                            Run("sub", setOf(Mark.Subscript)),
                            Run("sup", setOf(Mark.Superscript)),
                            Run("strike", setOf(Mark.Strikethrough, Mark.Underline)),
                        ),
                    ),
                    Block(id = "b3", type = BlockType.Todo, checked = false, runs = listOf(Run("todo"))),
                    Block(id = "b4", type = BlockType.Code, runs = listOf(Run("val x = 1"))),
                ),
            ),
            Outline.Image(id = "i1", attachmentId = "a1", height = 200f),
            Outline.Ink(id = "k1", height = 90f),
        ),
    )

    @Test
    fun `cbor round trips every document feature including sealed types`() {
        val doc = everyFeature()

        assertEquals(doc, CborDocumentCodec.decode(CborDocumentCodec.encode(doc)))
    }

    @Test
    fun `cbor keeps unchecked todos distinct from non-todo blocks`() {
        val doc = PageDoc(
            outlines = listOf(
                Outline.Text(
                    id = "o",
                    blocks = listOf(
                        Block(id = "t", type = BlockType.Todo, checked = false, runs = listOf(Run("x"))),
                        Block(id = "p", runs = listOf(Run("y"))),
                    ),
                ),
            ),
        )

        val blocks = (CborDocumentCodec.decode(CborDocumentCodec.encode(doc)).outlines.first() as Outline.Text).blocks

        assertEquals(false, blocks[0].checked)
        assertEquals(null, blocks[1].checked)
    }

    @Test
    fun `cbor is meaningfully smaller than json`() {
        val doc = everyFeature()

        val json = JsonDocumentCodec.encode(doc).size
        val cbor = CborDocumentCodec.encode(doc).size

        assertTrue("cbor=$cbor json=$json — expected the binary form to be smaller", cbor < json)
        println("CODEC_SIZE json=$json cbor=$cbor ratio=${"%.2f".format(cbor.toDouble() / json)}")
    }

    @Test
    fun `a document is only readable by the codec that wrote it`() {
        // Why every stored row records its format: the wrong codec must fail, not misread.
        val cborBytes = CborDocumentCodec.encode(everyFeature())

        assertTrue(runCatching { JsonDocumentCodec.decode(cborBytes) }.isFailure)
    }

    @Test
    fun `both codecs are reachable by id`() {
        assertEquals(JsonDocumentCodec, DocumentCodecs.byId("json/1"))
        assertEquals(CborDocumentCodec, DocumentCodecs.byId("cbor/1"))
    }
}
