package com.vivenotes.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.vivenotes.ai.InkTextRegion
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * What a screen reader finds where handwriting is: each recognized line, said as its text, over the
 * ink it was read from — and nothing that takes the touch from what the ink was written over.
 */
class InkDescriptionTest {

    @get:Rule
    val compose = createComposeRule()

    private var tapsUnderneath = 0

    private val line = InkTextRegion(
        id = "line-1",
        text = "Athor the mutant",
        confidence = 0.9f,
        left = LEFT,
        top = TOP,
        right = LEFT + WIDTH,
        bottom = TOP + HEIGHT,
        strokeIds = listOf("stroke-1"),
    )

    private fun setPage(regions: List<InkTextRegion> = listOf(line)) {
        compose.setContent {
            ViveNotesTheme {
                val density = LocalDensity.current.density
                Box(Modifier.size(PAGE.dp, PAGE.dp)) {
                    // What the ink was written over, standing in for a text container or the canvas.
                    Box(Modifier.fillMaxSize().testTag(UNDERNEATH).clickable { tapsUnderneath++ })
                    InkDescriptions(
                        regions = regions,
                        density = density,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aLineIsSaidAsItsTextOverTheInkItWasReadFrom() {
        setPage()

        compose.onNodeWithContentDescription("Handwriting: Athor the mutant")
            .assertLeftPositionInRootIsEqualTo(LEFT.dp)
            .assertTopPositionInRootIsEqualTo(TOP.dp)
            .assertWidthIsEqualTo(WIDTH.dp)
            .assertHeightIsEqualTo(HEIGHT.dp)
    }

    /** A reading too unlikely to say is withheld, and the line is still there to be found. */
    @Test
    fun anUnlikelyReadingLeavesJustHandwriting() {
        setPage(listOf(line.copy(text = ":", confidence = 0.43f)))

        compose.onNodeWithContentDescription("Handwriting").assertExists()
        compose.onNodeWithContentDescription("Handwriting: :").assertDoesNotExist()
    }

    @Test
    fun aTapOnTheLineReachesWhatItWasWrittenOver() {
        setPage()

        compose.onNodeWithTag(UNDERNEATH).performTouchInput {
            click(percentOffset((LEFT + WIDTH / 2) / PAGE, (TOP + HEIGHT / 2) / PAGE))
        }
        compose.waitForIdle()

        assertEquals(1, tapsUnderneath)
    }

    private companion object {
        const val UNDERNEATH = "underneath"
        const val PAGE = 400f
        const val LEFT = 30f
        const val TOP = 80f
        const val WIDTH = 240f
        const val HEIGHT = 40f
    }
}
