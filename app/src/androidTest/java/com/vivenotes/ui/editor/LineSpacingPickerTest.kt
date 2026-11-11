package com.vivenotes.ui.editor

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The Document tab's Line spacing menu: it ticks the paragraph's spacing and reports the one picked. */
class LineSpacingPickerTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun ticksTheCurrentSpacingAndReportsThePickedOne() {
        val picked = mutableListOf<Float>()
        compose.setContent {
            ViveNotesTheme { LineSpacingPicker(current = 1.5f) { picked += it } }
        }

        compose.onNodeWithTag(HomeTags.LINE_SPACING).performClick()
        compose.onNodeWithTag(HomeTags.lineSpacing(1.5f)).assertIsSelected()
        compose.onNodeWithTag(HomeTags.lineSpacing(1f)).assertIsNotSelected()
        compose.onNodeWithTag(HomeTags.lineSpacing(2f)).performClick()

        assertEquals(listOf(2f), picked)
    }
}
