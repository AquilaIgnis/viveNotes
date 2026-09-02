package com.vivenotes.data

import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which marks the one-shot repair takes the "I picked this" flag back off, and which it must not.
 *
 * The bug it undoes is a picker's, not a renderer's: on a dark canvas the automatic pen's own
 * colour is the white swatch, so tapping that swatch to get back to ordinary ink recorded every
 * stroke afterwards as *deliberately* white — and deliberate white is kept, including on the white
 * sheet a PDF export always is. The rule has to be narrow enough that a colour someone really did
 * choose is never quietly re-resolved: only the two colours automatic itself produces qualify.
 */
class AutomaticInkRepairRulesTest {

    private val red = 0xFFE53935.toInt()

    @Test
    fun deliberateWhiteAndBlackAreWhatTheRepairTakesBack() {
        assertTrue(isChosenAutomaticInk(AUTOMATIC_LIGHT, followsTheme = false))
        assertTrue(isChosenAutomaticInk(AUTOMATIC_DARK, followsTheme = false))
    }

    /** A mark that already follows the canvas, or never said, has nothing to repair. */
    @Test
    fun marksThatAreNotDeliberateAreLeftAlone() {
        assertEquals(false, isChosenAutomaticInk(AUTOMATIC_LIGHT, followsTheme = true))
        assertEquals(false, isChosenAutomaticInk(AUTOMATIC_LIGHT, followsTheme = null))
    }

    /** The whole point of the flag: a colour someone picked from the palette stays picked. */
    @Test
    fun aChosenColorIsNotACandidate() {
        assertEquals(false, isChosenAutomaticInk(red, followsTheme = false))
    }

    /**
     * Alpha is part of the match, so a translucent highlight — which `InkCodec` records as
     * deliberate on purpose, a highlighter having no automatic — is never repainted opaque.
     */
    @Test
    fun aTranslucentWashIsNotACandidate() {
        assertEquals(false, isChosenAutomaticInk(0x66FFFFFF, followsTheme = false))
    }

    @Test
    fun aPageWithNothingToRepairIsNotRewritten() {
        val doc = PageDoc(
            outlines = listOf(
                Outline.Shape(id = "chosen", borderArgb = red, borderFollowsTheme = false),
                Outline.Shape(id = "automatic", borderArgb = AUTOMATIC_LIGHT, borderFollowsTheme = true),
            ),
        )
        assertNull(doc.withRepairedAutomaticInk())
    }

    /**
     * Shapes and tables both. A straight line held out of a stroke is a shape carrying the pen's own
     * flag, so a page repaired in its ink and not in its lines would come out half fixed.
     */
    @Test
    fun deliberatelyWhiteBordersForgetTheirIntent() {
        val doc = PageDoc(
            outlines = listOf(
                Outline.Shape(id = "line", borderArgb = AUTOMATIC_LIGHT, borderFollowsTheme = false),
                Outline.Table(id = "grid", borderArgb = AUTOMATIC_LIGHT, borderFollowsTheme = false),
                Outline.Shape(id = "chosen", borderArgb = red, borderFollowsTheme = false),
            ),
        )

        val repaired = requireNotNull(doc.withRepairedAutomaticInk())

        val shape = repaired.outlines.filterIsInstance<Outline.Shape>().single { it.id == "line" }
        val table = repaired.outlines.filterIsInstance<Outline.Table>().single()
        val chosen = repaired.outlines.filterIsInstance<Outline.Shape>().single { it.id == "chosen" }
        assertNull(shape.borderFollowsTheme)
        assertNull(table.borderFollowsTheme)
        // The colour itself is never touched: what changes is only whether it is still a statement.
        assertEquals(AUTOMATIC_LIGHT, shape.borderArgb)
        assertEquals(false, chosen.borderFollowsTheme)
    }

    /** Applied twice it is the same document, which is what makes a re-run harmless. */
    @Test
    fun theRepairIsIdempotent() {
        val doc = PageDoc(
            outlines = listOf(
                Outline.Shape(id = "line", borderArgb = AUTOMATIC_LIGHT, borderFollowsTheme = false),
            ),
        )

        val once = requireNotNull(doc.withRepairedAutomaticInk())
        assertNull(once.withRepairedAutomaticInk())
    }
}
