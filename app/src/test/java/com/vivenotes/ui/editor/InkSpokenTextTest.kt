package com.vivenotes.ui.editor

import com.vivenotes.ai.InkTextRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which reading of a handwritten line a screen reader is given. The cases are real readings of the
 * Recognition Test page — handwritten maths read as text — beside a prose line as the study scores
 * them.
 */
class InkSpokenTextTest {

    private fun line(
        text: String,
        confidence: Float,
        alternateText: String? = null,
        alternateConfidence: Float? = null,
    ) = InkTextRegion(
        id = "line",
        text = text,
        confidence = confidence,
        alternateText = alternateText,
        alternateConfidence = alternateConfidence,
        left = 0f,
        top = 0f,
        right = 100f,
        bottom = 20f,
        strokeIds = emptyList(),
    )

    @Test
    fun aConfidentReadingIsSpoken() {
        assertEquals("Athor the mutant", line("Athor the mutant", 0.91f).spokenText())
        assertEquals("3k - 5", line("3k - 5", 0.84f).spokenText())
    }

    /** The fallback rendering read it better, so it is the one to say. */
    @Test
    fun theLikelierOfTheTwoReadingsIsSpoken() {
        assertEquals("x{2-4=0", line("\$x^{2}- 4 = 0", 0.62f, "x{2-4=0", 0.80f).spokenText())
        assertEquals("3k - 5", line("3k - 5", 0.84f, "3k-s", 0.70f).spokenText())
    }

    /** Noise is withheld, not read out: the line is still there, announced as handwriting. */
    @Test
    fun aReadingBelowTheThresholdIsWithheld() {
        assertNull(line(":", 0.43f, "-", 0.45f).spokenText())
        assertNull(line("1", 0.11f, "2", 0.09f).spokenText())
        assertNull(line("[3 t]", 0.68f, "[3 3]", 0.67f).spokenText())
    }

    @Test
    fun aBlankReadingIsWithheldHoweverConfident() {
        assertNull(line(" ", 0.95f).spokenText())
    }
}
