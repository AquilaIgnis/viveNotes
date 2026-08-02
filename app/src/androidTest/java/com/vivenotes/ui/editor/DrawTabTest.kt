package com.vivenotes.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.vivenotes.data.DrawTool
import com.vivenotes.data.PenKind
import com.vivenotes.data.PenPreset
import com.vivenotes.ui.panel.PenPanelContent
import com.vivenotes.ui.panel.PenPanelTags
import com.vivenotes.ui.panel.PanelTags
import com.vivenotes.ui.theme.ViveNotesTheme

/**
 * The Draw tab's three pens are identical by design, so nothing on screen distinguishes them but
 * colour and position — which is exactly the shape of code where pen 2 opens pen 1's settings. These
 * check that a tool reaches the index it belongs to, and that the pane writes back the field it
 * names rather than a neighbouring one.
 */
class DrawTabTest {

    @get:Rule
    val compose = createComposeRule()

    private var selected: DrawTool? = null
    private var changed: PenPreset? = null
    private var changedIndex: Int? = null
    private var fingerDrawing: Boolean? = null

    private fun setTab(
        tool: DrawTool = DrawTool.None,
        pens: List<PenPreset> = List(PenPreset.COUNT) { PenPreset.starting(it) },
        allowFinger: Boolean = false,
    ) {
        compose.setContent {
            ViveNotesTheme {
                DrawTab(
                    pens = pens,
                    tool = tool,
                    allowFinger = allowFinger,
                    actions = DrawActions(
                        selectTool = { selected = it },
                        updatePen = { index, pen ->
                            changedIndex = index
                            changed = pen
                        },
                        setDrawWithFinger = { fingerDrawing = it },
                    ),
                )
            }
        }
    }

    private fun setPanel(pen: PenPreset = PenPreset.starting(0)) {
        compose.setContent {
            ViveNotesTheme {
                Column { PenPanelContent(pen = pen) { changed = it } }
            }
        }
    }

    // --- the tray ------------------------------------------------------------------------------

    @Test
    fun tappingAPenSelectsThatPen() {
        setTab()
        compose.onNodeWithTag(DrawTags.pen(2)).performClick()
        assertEquals(DrawTool.Pen(2), selected)
        compose.onNodeWithTag(PenPanelTags.PREVIEW).assertDoesNotExist()
    }

    @Test
    fun holdingAPenOpensItsSettings() {
        setTab()
        compose.onNodeWithTag(DrawTags.pen(1)).performTouchInput { longClick() }
        compose.onNodeWithText("Pen 2").assertIsDisplayed()
        compose.onNodeWithTag(PenPanelTags.PREVIEW).assertIsDisplayed()
        // Configuring a pen picks it up: the settings about to change are the ones you would draw with.
        assertEquals(DrawTool.Pen(1), selected)
    }

    @Test
    fun tappingTheSelectedPenOpensItsSettings() {
        setTab(tool = DrawTool.Pen(0))
        compose.onNodeWithTag(DrawTags.pen(0)).performClick()
        compose.onNodeWithText("Pen 1").assertIsDisplayed()
    }

    @Test
    fun tappingOutsideThePenSettingsDismissesThem() {
        setTab(tool = DrawTool.Pen(0))
        compose.onNodeWithTag(DrawTags.pen(0)).performClick()
        compose.onNodeWithTag(PenPanelTags.PREVIEW).assertIsDisplayed()

        compose.onNodeWithContentDescription("Undo").performTouchInput { click() }

        compose.onNodeWithTag(PenPanelTags.PREVIEW).assertDoesNotExist()
    }

    @Test
    fun floatingSettingsWriteBackToTheirOwnPen() {
        setTab(tool = DrawTool.Pen(2))
        compose.onNodeWithTag(DrawTags.pen(2)).performClick()
        val green = 0xFF00C853.toInt()

        compose.onNodeWithTag(PenPanelTags.color(green)).performClick()

        assertEquals(2, changedIndex)
        assertEquals(green, changed?.colorArgb)
    }

    @Test
    fun theEraserIsPickedUp() {
        setTab()
        compose.onNodeWithTag(DrawTags.ERASER).performClick()
        assertEquals(DrawTool.Eraser, selected)
    }

    /** Otherwise there is no way back to the text without picking up a pen first. */
    @Test
    fun tappingTheArmedEraserPutsItDown() {
        setTab(tool = DrawTool.Eraser)
        compose.onNodeWithTag(DrawTags.ERASER).performClick()
        assertEquals(DrawTool.None, selected)
    }

    /**
     * Icon-only now, so they are found by the description rather than by a label — and still inert,
     * because the undo stack is feature C6.
     */
    @Test
    fun undoAndRedoAreIconsAndNotWired() {
        setTab()
        compose.onNodeWithContentDescription("Undo").assertIsDisplayed()
        compose.onNodeWithContentDescription("Redo").assertIsDisplayed()
        compose.onNodeWithContentDescription("Undo").assertHasNoClickAction()
        compose.onNodeWithText("Undo").assertDoesNotExist()
    }

    @Test
    fun theFingerButtonTogglesWhoMayDraw() {
        setTab(allowFinger = false)
        compose.onNodeWithTag(DrawTags.FINGER).performClick()
        assertEquals(true, fingerDrawing)
    }

    @Test
    fun theFingerButtonTurnsBackOff() {
        setTab(allowFinger = true)
        compose.onNodeWithTag(DrawTags.FINGER).performClick()
        assertEquals(false, fingerDrawing)
    }

    // --- the pen pane --------------------------------------------------------------------------

    @Test
    fun eachToggleWritesItsOwnField() {
        setPanel()
        compose.onNodeWithTag(PanelTags.field("Circle to lasso")).performClick()
        assertEquals(true, changed?.circleToLasso)
        // Nothing else moved: the copy() went to the field the row is named after.
        assertEquals(PenPreset.starting(0).holdToDrawShape, changed?.holdToDrawShape)
        assertEquals(PenPreset.starting(0).scribbleToErase, changed?.scribbleToErase)
    }

    @Test
    fun pickingAColorChangesOnlyTheColor() {
        val pen = PenPreset.starting(0)
        setPanel(pen)
        val green = 0xFF00C853.toInt()
        compose.onNodeWithTag(PenPanelTags.color(green)).performClick()
        assertEquals(pen.copy(colorArgb = green), changed)
    }

    @Test
    fun theCalligraphyPenIsSelectable() {
        setPanel()
        compose.onNodeWithTag(PenPanelTags.kind(PenKind.Calligraphy)).performClick()
        assertEquals(PenKind.Calligraphy, changed?.kind)
    }

    /**
     * The middle pen type in `docs/references/pen-tooltip.jpeg` is crossed out, which this project
     * reads as out of scope — so it is absent rather than disabled, the way the View tab handles
     * Dock to Desktop. Only two cards exist.
     */
    @Test
    fun theCrossedOutPenTypeIsNotPresent() {
        setPanel()
        assertEquals(2, PenKind.entries.size)
        compose.onNodeWithTag(PenPanelTags.kind(PenKind.Fountain)).assertIsDisplayed()
        compose.onNodeWithTag(PenPanelTags.kind(PenKind.Calligraphy)).assertIsDisplayed()
    }

    /**
     * The fountain pen is this app's plain pen — one width, whatever the pressure — so the control
     * is absent rather than disabled. The calligraphy pen is the one that responds.
     */
    @Test
    fun theFountainPenHasNoPressureSetting() {
        setPanel(PenPreset.starting(0).copy(kind = PenKind.Fountain))
        compose.onNodeWithTag(PanelTags.field("Pressure sensitivity")).assertDoesNotExist()
    }

    @Test
    fun theCalligraphyPenHasAPressureSetting() {
        setPanel(PenPreset.starting(0).copy(kind = PenKind.Calligraphy))
        compose.onNodeWithTag(PanelTags.field("Pressure sensitivity")).assertIsDisplayed()
    }

    @Test
    fun theStrokePreviewIsShown() {
        setPanel()
        compose.onNodeWithTag(PenPanelTags.PREVIEW).assertIsDisplayed()
    }

    @Test
    fun addColorIsPlacedButNotWired() {
        setPanel()
        compose.onNodeWithContentDescription("Add color").assertIsDisplayed()
        assertTrue("nothing should have been written by merely showing the pane", changed == null)
    }
}
