package com.vivenotes.ui.editor

import android.graphics.RectF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vivenotes.data.PEN_COLORS
import com.vivenotes.ui.panel.PenPanelTags
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** The object toolkit's colour and fill menus, which use the Draw tab's shared colour row. */
class ObjectToolkitColorTest {

    @get:Rule
    val compose = createComposeRule()

    private val recolored = mutableListOf<Int>()
    private val filled = mutableListOf<Int?>()
    private val added = mutableListOf<Int>()

    private fun setToolkit(swatch: Color, extras: @Composable RowScope.() -> Unit = {}) {
        compose.setContent {
            ViveNotesTheme {
                Box(Modifier.size(400.dp)) {
                    ObjectTooltip(
                        swatch = swatch,
                        selectionBoundsInView = { RectF(60f, 200f, 160f, 260f) },
                        viewportSize = IntSize(1000, 1000),
                        onDelete = {},
                        onCopy = {},
                        onRecolor = { recolored += it },
                        locked = null,
                        onToggleLock = {},
                        extras = extras,
                        palette = PEN_COLORS,
                        onAddColor = { added += it },
                    )
                }
            }
        }
    }

    @Test
    fun recolorRingsTheCurrentColorAndClosesOnAPick() {
        val red = PEN_COLORS[2]
        setToolkit(Color(red))
        compose.onNodeWithTag(OBJECT_COLOR_TAG).performClick()

        compose.onNodeWithTag(ObjectColorTags.color(red)).assertIsSelected()
        compose.onNodeWithTag(ObjectColorTags.color(PEN_COLORS[4])).assertIsNotSelected().performClick()

        assertEquals(listOf(PEN_COLORS[4]), recolored)
        compose.onNodeWithTag(ObjectColorTags.color(red)).assertDoesNotExist()
    }

    /** A colour mixed for an object joins the shared row, once, and Done closes the whole menu. */
    @Test
    fun aMixedRecolorJoinsTheRow() {
        setToolkit(Color(PEN_COLORS[1]))
        compose.onNodeWithTag(OBJECT_COLOR_TAG).performClick()
        compose.onNodeWithTag(ObjectColorTags.CUSTOM).performClick()
        compose.onNodeWithTag(PenPanelTags.WHEEL).performTouchInput {
            down(Offset(width - 1f, height / 2f))
            up()
        }
        compose.onNodeWithText("Done").performClick()

        assertEquals(1, recolored.size)
        assertEquals(recolored, added)
        compose.onNodeWithTag(ObjectColorTags.CUSTOM).assertDoesNotExist()
    }

    /** No fill is the one value off the row, so it rings nothing there — not even the wheel. */
    @Test
    fun noFillRingsNoneAndNoSwatch() {
        setToolkit(Color.Black) { FillAction(fill = null) { filled += it } }
        compose.onNodeWithTag(OBJECT_FILL_TAG).performClick()

        compose.onNodeWithTag(OBJECT_FILL_NONE_TAG).assertIsSelected()
        compose.onNodeWithTag(ObjectColorTags.FILL_CUSTOM).assertIsNotSelected()
        PEN_COLORS.forEach { compose.onNodeWithTag(ObjectColorTags.fill(it)).assertIsNotSelected() }

        compose.onNodeWithTag(ObjectColorTags.fill(PEN_COLORS[8])).performClick()
        assertEquals(listOf<Int?>(PEN_COLORS[8]), filled)
        compose.onNodeWithTag(OBJECT_FILL_NONE_TAG).assertDoesNotExist()
    }

    @Test
    fun aMixedFillJoinsTheRow() {
        setToolkit(Color.Black) {
            FillAction(fill = PEN_COLORS[2], onAddColor = { added += it }) { filled += it }
        }
        compose.onNodeWithTag(OBJECT_FILL_TAG).performClick()
        compose.onNodeWithTag(ObjectColorTags.fill(PEN_COLORS[2])).assertIsSelected()
        compose.onNodeWithTag(OBJECT_FILL_NONE_TAG).assertIsNotSelected()
        compose.onNodeWithTag(ObjectColorTags.FILL_CUSTOM).performClick()
        compose.onNodeWithTag(PenPanelTags.WHEEL).performTouchInput {
            down(Offset(width * 0.3f, height * 0.3f))
            up()
        }
        compose.onNodeWithText("Done").performClick()

        val mixed = filled.single()
        assertEquals(listOf(mixed), added)
        compose.onNodeWithTag(OBJECT_FILL_NONE_TAG).assertDoesNotExist()
    }

    @Test
    fun noFillClears() {
        setToolkit(Color.Black) { FillAction(fill = PEN_COLORS[2]) { filled += it } }
        compose.onNodeWithTag(OBJECT_FILL_TAG).performClick()
        compose.onNodeWithTag(OBJECT_FILL_NONE_TAG).performClick()

        assertEquals(1, filled.size)
        assertNull(filled.single())
    }
}
