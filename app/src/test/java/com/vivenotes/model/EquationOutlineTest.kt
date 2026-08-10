package com.vivenotes.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The equation as a Prime Object — the Draw tab's ƒ.
 *
 * Everything here is arithmetic and serialization, which is why it runs on the JVM: the parts that
 * need a device — arming the tool, placing one, dragging its corners — are in `EquationObjectTest`.
 *
 * Two things are worth pinning hardest. **A corner drag is absolute against the geometry it started
 * from**, the contract every object on this canvas shares and the one whose breakage compounds a
 * drag's frames into each other; and **the source is what is stored**, because the whole argument for
 * an equation being data rather than a picture is that sync, export and the MCP server can read it
 * without a renderer.
 */
class EquationOutlineTest {

    private fun equation(
        x: Float = 100f,
        y: Float = 50f,
        width: Float = 80f,
        height: Float = 40f,
        latex: String = "x^2",
    ) = Outline.Equation(id = "eq", x = x, y = y, width = width, height = height, latex = latex)

    // -----------------------------------------------------------------------------------------
    // Geometry
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a move shifts the box and leaves its size alone`() {
        val moved = equation().translated(dx = 12f, dy = -8f)

        assertEquals(112f, moved.x)
        assertEquals(42f, moved.y)
        assertEquals(80f, moved.width)
        assertEquals(40f, moved.height)
    }

    /**
     * The far corner stays put, which is what a corner drag looks like to the person doing it.
     *
     * Anchored at the bottom-right — the corner opposite a top-left grab — so doubling the box has to
     * move the origin *back* by the whole of the growth rather than growing down and right.
     */
    @Test
    fun `a resize holds its anchor still`() {
        val source = equation(x = 100f, y = 50f, width = 80f, height = 40f)
        val anchorX = source.x + source.width
        val anchorY = source.y + source.height

        val scaled = source.scaledAbout(anchorX, anchorY, scaleX = 2f, scaleY = 2f)

        assertEquals(160f, scaled.width)
        assertEquals(80f, scaled.height)
        assertEquals("the anchored corner moved", anchorX, scaled.x + scaled.width)
        assertEquals("the anchored corner moved", anchorY, scaled.y + scaled.height)
        assertEquals(20f, scaled.x)
        assertEquals(10f, scaled.y)
    }

    /**
     * **Absolute, not compounding.** Two frames of one drag each report a scale measured from the
     * geometry the drag began with, so the last of them applied to that geometry is the answer —
     * applying them in sequence is the bug this guards, and it multiplies rather than replaces.
     */
    @Test
    fun `two frames of one drag are not two scales`() {
        val source = equation(width = 80f, height = 40f)
        val anchorX = source.x
        val anchorY = source.y

        val compounded = source
            .scaledAbout(anchorX, anchorY, 1.5f, 1.5f)
            .scaledAbout(anchorX, anchorY, 2f, 2f)
        val correct = source.scaledAbout(anchorX, anchorY, 2f, 2f)

        assertEquals(160f, correct.width)
        assertNotEquals(
            "applied per frame, one drag's scales multiply together",
            correct.width,
            compounded.width,
        )
    }

    /** Squashed flat one way, the other axis keeps its own size — the floor is per axis. */
    @Test
    fun `a resize never goes through zero`() {
        val flattened = equation(width = 80f, height = 40f)
            .scaledAbout(0f, 0f, scaleX = 0f, scaleY = 1f)

        assertEquals(Outline.Equation.MIN_SIZE, flattened.width)
        assertEquals(40f, flattened.height)
        assertTrue("a box with no corners left cannot be grabbed back", flattened.width > 0f)
    }

    // -----------------------------------------------------------------------------------------
    // The document
    // -----------------------------------------------------------------------------------------

    @Test
    fun `an equation survives the round trip as its source`() {
        val doc = PageDoc(
            outlines = listOf(equation(latex = "\\int_a^b f'(t)\\,dt")),
        )

        val restored = decodePageDoc(doc.encode()).outlines.single() as Outline.Equation

        assertEquals("\\int_a^b f'(t)\\,dt", restored.latex)
        assertEquals(100f, restored.x)
        assertEquals(80f, restored.width)
        assertEquals(40f, restored.height)
    }

    /** Null is "follow the page", which is a different state from any colour it could be given. */
    @Test
    fun `an automatic colour stays absent across the round trip`() {
        val doc = PageDoc(outlines = listOf(equation().copy(colorArgb = null)))

        val restored = decodePageDoc(doc.encode()).outlines.single() as Outline.Equation

        assertEquals(null, restored.colorArgb)
    }

    /**
     * The formula is searchable, which is the reason to store a source rather than a bitmap.
     *
     * The same answer `Run.searchText` gives for the inline mark: someone hunting the page they wrote
     * an integral on is hunting this one, and nobody can search a rendered picture.
     */
    @Test
    fun `the source is in the page's plain text`() {
        val doc = PageDoc(
            outlines = listOf(
                Outline.Text(id = "t", blocks = listOf(Block.of("before"))),
                equation(latex = "e^{i\\pi}+1=0"),
            ),
        )

        assertTrue(doc.plainText().contains("e^{i\\pi}+1=0"))
        assertTrue(doc.plainText().contains("before"))
    }

    /** The schema 1 → 2 shift reaches every kind, this one included — see [SchemaMigrationTest]. */
    @Test
    fun `an equation moves with the page when the schema migrates`() {
        val migrated = PageDoc(schema = 1, outlines = listOf(equation(y = 10f))).migrated()

        val moved = migrated.outlines.single() as Outline.Equation
        assertEquals(10f + PageStyle.TITLE_BAND_DP, moved.y)
    }
}
