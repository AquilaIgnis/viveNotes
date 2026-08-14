package com.vivenotes.ui.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import com.vivenotes.data.DrawTool
import com.vivenotes.data.EditorDefaults
import com.vivenotes.data.EraserSettings
import com.vivenotes.data.HighlighterSettings
import com.vivenotes.data.PEN_COLORS
import com.vivenotes.data.PenPreset
import com.vivenotes.data.ShapeSettings
import com.vivenotes.data.ViewSettings
import com.vivenotes.model.PageStyle
import com.vivenotes.richtext.SelectionState
import com.vivenotes.ui.theme.ViveNotesTheme

/**
 * The tab strip's own controls, as opposed to the tabs.
 *
 * Only one so far: whether a finger may draw. It lived on the Draw tab until 2026-08-08 and was
 * moved here because it is not a drawing tool — it decides whether a finger on the canvas marks the
 * page or scrolls it, which is as true with the Home tab open as with Draw.
 */
class RibbonTabStripTest {

    @get:Rule
    val compose = createComposeRule()

    private var fingerDrawing: Boolean? = null

    @Test
    fun theFingerButtonTogglesWhoMayDraw() {
        setRibbon(allowFinger = false)

        compose.onNodeWithTag(RibbonTags.FINGER).performClick()

        assertEquals(true, fingerDrawing)
    }

    @Test
    fun theFingerButtonTurnsBackOff() {
        setRibbon(allowFinger = true)

        compose.onNodeWithTag(RibbonTags.FINGER).performClick()

        assertEquals(false, fingerDrawing)
    }

    /** The whole point of the move: it is not the Draw tab's, so it does not leave with the tab. */
    @Test
    fun theFingerButtonIsThereWithTheHomeTabOpen() {
        setRibbon(activeTab = RibbonTab.Document)

        compose.onNodeWithTag(RibbonTags.FINGER).assertIsDisplayed()
    }

    @Test
    fun theFingerButtonIsStillThereWithTheDrawTabOpen() {
        setRibbon(activeTab = RibbonTab.Draw)

        compose.onNodeWithTag(RibbonTags.FINGER).assertIsDisplayed()
    }

    private fun setRibbon(
        activeTab: RibbonTab = RibbonTab.Document,
        allowFinger: Boolean = false,
    ) {
        compose.setContent {
            ViveNotesTheme {
                Ribbon(
                    selection = SelectionState(),
                    activeTab = activeTab,
                    onTabChange = {},
                    onCommand = {},
                    defaults = EditorDefaults(),
                    onSetDefault = {},
                    pageStyle = PageStyle(),
                    viewSettings = ViewSettings(),
                    view = noopViewActions(),
                    pens = List(PenPreset.COUNT) { PenPreset.starting(it) },
                    palette = PEN_COLORS,
                    eraser = EraserSettings(),
                    highlighter = HighlighterSettings(),
                    shape = ShapeSettings(),
                    tool = DrawTool.None,
                    allowFinger = allowFinger,
                    draw = DrawActions(
                        selectTool = {},
                        updatePen = { _, _ -> },
                        updateEraser = {},
                        setDrawWithFinger = { fingerDrawing = it },
                    ),
                    pageOpen = true,
                )
            }
        }
    }

    private fun noopViewActions() = ViewActions(
        setRuleLines = {},
        setPageColor = {},
        setHideTitle = {},
        setZoom = {},
        zoomIn = {},
        zoomOut = {},
        zoomToPageWidth = {},
        setTabsLayout = {},
        setCanvasDark = {},
        openPane = {},
    )
}
