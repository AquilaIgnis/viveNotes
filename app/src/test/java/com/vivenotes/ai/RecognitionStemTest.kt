package com.vivenotes.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one thing about recognition that can be checked without a device, and the thing most worth
 * checking — `simulations/formula-render` measured mean token accuracy 0.615 against 0.898 on the
 * strength of this arithmetic alone.
 *
 * The property is not the formula, it is what the formula is for: **whatever size a formula is
 * written at, its strokes must land the same number of pixels wide in the square the model reads.**
 * A regression here is silent — recognition keeps working, it simply gets worse — so it is asserted
 * by replaying the scaling `preprocessFormula` performs rather than by restating the algebra.
 */
class RecognitionStemTest {

    /** What [preprocessFormula] does to a stroke of [stem] page units in a selection [longest] across. */
    private fun stemInModelFrame(longest: Float, stem: Float): Float =
        stem * FORMULA_INPUT_PX / (longest + stem)

    @Test
    fun everySelectionSizeLandsAtTheSameStemWidth() {
        // A subscript, a formula, and a whole line of algebra written across the page.
        listOf(12f, 40f, 144f, 190f, 400f, 1000f).forEach { longest ->
            val stem = recognitionStemSize(longest)
            assertEquals(
                "a selection $longest units across did not land at the target stem",
                RECOGNITION_STEM_PX,
                stemInModelFrame(longest, stem),
                0.01f,
            )
        }
    }

    @Test
    fun theStoredWidthDoesNotLandAtTheSameStemWidth() {
        // The defect, stated as a test so nobody reverts to it: page 3's three formulas are 144,
        // 190 and 219 units across, and drawing all three at the stored 2 dp hands the model three
        // different stem widths — which is why one formula read cleanly and the next did not.
        // 5.3, 4.0 and 3.5 px respectively: every one of them under the target, and each a
        // different distance under it.
        val widths = listOf(144f, 190f, 219f).map { stemInModelFrame(it, 2f) }
        assertTrue(
            "the stored width was expected to scale differently per formula, got $widths",
            widths.max() - widths.min() > 1.5f,
        )
        widths.forEach {
            assertTrue("$it should be thinner than the target stem", it < RECOGNITION_STEM_PX)
        }
    }

    @Test
    fun theWidthStaysPositiveForADegenerateSelection() {
        assertTrue(recognitionStemSize(0f) >= 0f)
        assertTrue(recognitionStemSize(1f) > 0f)
    }
}
