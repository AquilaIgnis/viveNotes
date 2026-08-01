package st.unamedtba.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The schema 1 → 2 move, which changes what an outline's `y` is measured from.
 *
 * Schema 1 laid content out in a box below the title header, so `y = 0` meant "just under the
 * title"; schema 2 measures from the page's own top-left corner, the same origin as the sheet and
 * its guides. Getting this wrong moves every container on every saved page, which is why it is a
 * pure function on the model and tested here rather than through the editor.
 */
class SchemaMigrationTest {

    private fun schemaOne(style: PageStyle = PageStyle(), vararg y: Float) = PageDoc(
        schema = 1,
        style = style,
        outlines = y.mapIndexed { i, at -> Outline.Text(id = "o$i", y = at, blocks = emptyList()) },
    )

    @Test
    fun `a titled page drops its outlines by the title band`() {
        val migrated = schemaOne(y = floatArrayOf(0f, 250f)).migrated()

        assertEquals(
            listOf(PageStyle.TITLE_BAND_DP, 250f + PageStyle.TITLE_BAND_DP),
            migrated.outlines.map { it.y },
        )
        assertEquals(PageDoc.CURRENT_SCHEMA, migrated.schema)
    }

    /** With the title hidden, schema 1 already started at the top of the page. Nothing to move. */
    @Test
    fun `a page with no title keeps its outlines where they are`() {
        val migrated = schemaOne(style = PageStyle(hideTitle = true), y = floatArrayOf(0f, 250f)).migrated()

        assertEquals(listOf(0f, 250f), migrated.outlines.map { it.y })
        assertEquals(PageDoc.CURRENT_SCHEMA, migrated.schema)
    }

    /**
     * `loadDoc` migrates on every open, so a page that has already moved must not move again. The
     * schema stamp is what stops it, and this is the test that says so.
     */
    @Test
    fun `migrating twice moves nothing the second time`() {
        val once = schemaOne(y = floatArrayOf(30f)).migrated()

        val twice = once.migrated()

        assertEquals(once.outlines.map { it.y }, twice.outlines.map { it.y })
        assertSame("an up-to-date document should be handed back untouched", once, twice)
    }

    @Test
    fun `x, width and content are left alone`() {
        val doc = PageDoc(
            schema = 1,
            outlines = listOf(
                Outline.Text(
                    id = "o",
                    x = 120f,
                    y = 10f,
                    width = 400f,
                    minHeight = 90f,
                    blocks = listOf(Block.of("kept")),
                ),
            ),
        )

        val moved = doc.migrated().outlines.single() as Outline.Text

        assertEquals(120f, moved.x, 0f)
        assertEquals(400f, moved.width, 0f)
        assertEquals(90f, moved.minHeight, 0f)
        assertEquals("kept", moved.blocks.single().text)
    }

    /** Images and ink are positioned the same way, so they have to move with the text. */
    @Test
    fun `every kind of outline moves together`() {
        val doc = PageDoc(
            schema = 1,
            outlines = listOf(
                Outline.Text(id = "t", y = 0f, blocks = emptyList()),
                Outline.Image(id = "i", y = 0f, attachmentId = "a", height = 100f),
                Outline.Ink(id = "k", y = 0f),
            ),
        )

        val moved = doc.migrated().outlines

        assertEquals(List(3) { PageStyle.TITLE_BAND_DP }, moved.map { it.y })
    }

    /** A document written by this build carries the current stamp and starts below the title. */
    @Test
    fun `a new document is already current`() {
        val fresh = PageDoc.empty()

        assertEquals(PageDoc.CURRENT_SCHEMA, fresh.schema)
        assertSame("a current document should be handed back untouched", fresh, fresh.migrated())
        assertEquals(PageStyle.TITLE_BAND_DP, fresh.outlines.single().y, 0f)
    }
}
