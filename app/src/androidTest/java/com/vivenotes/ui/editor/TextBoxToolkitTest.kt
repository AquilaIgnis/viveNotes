package com.vivenotes.ui.editor

import android.graphics.RectF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vivenotes.data.DrawTool
import com.vivenotes.data.EditorDefaults
import com.vivenotes.data.EraserSettings
import com.vivenotes.data.HighlighterSettings
import com.vivenotes.data.PEN_COLORS
import com.vivenotes.data.PenPreset
import com.vivenotes.data.ShapeSettings
import com.vivenotes.data.ViewSettings
import com.vivenotes.model.PageStyle
import com.vivenotes.richtext.FormatCommand
import com.vivenotes.richtext.SelectionState
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The TextBox Class — `docs/diagram.md`, planned in `docs/textBoxPlan.md`.
 *
 * Two things are worth pinning here and neither is about text. The **T button** was a mode switch
 * that could be pressed and never unpressed, because `DrawTool.None` *was* text mode (TD2). And the
 * **bar** is the first thing to drop a member of the base toolkit rather than extend it (TD4), which
 * is exactly the kind of change that silently takes a behaviour with it.
 */
class TextBoxToolkitTest {

    @get:Rule
    val compose = createComposeRule()

    private var armed: DrawTool? = null

    // -----------------------------------------------------------------------------------------
    // The toggle — TD2
    // -----------------------------------------------------------------------------------------

    private fun setRibbon(tool: DrawTool) {
        armed = null
        compose.setContent {
            ViveNotesTheme {
                Ribbon(
                    selection = SelectionState(),
                    activeTab = RibbonTab.Document,
                    onTabChange = {},
                    onCommand = {},
                    defaults = EditorDefaults(),
                    onSetDefault = {},
                    pageStyle = PageStyle(),
                    viewSettings = ViewSettings(),
                    view = ViewActions(
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
                    ),
                    pens = List(PenPreset.COUNT) { PenPreset.starting(it) },
                    palette = PEN_COLORS,
                    eraser = EraserSettings(),
                    highlighter = HighlighterSettings(),
                    shape = ShapeSettings(),
                    tool = tool,
                    allowFinger = false,
                    draw = DrawActions(
                        selectTool = { armed = it },
                        updatePen = { _, _ -> },
                        updateEraser = {},
                        setDrawWithFinger = {},
                    ),
                    pageOpen = true,
                )
            }
        }
    }

    @Test
    fun theTextButtonArmsTheTextTool() {
        setRibbon(tool = DrawTool.Pen(0))

        compose.onNodeWithTag(HomeTags.TEXT).performClick()

        assertEquals(DrawTool.Text, armed)
    }

    @Test
    fun pressingItAgainPutsTheToolDownInsteadOfDoingNothing() {
        // The whole of the bug: it used to select `None`, which *was* text mode, so the button could
        // be pressed forever and never released.
        setRibbon(tool = DrawTool.Text)

        compose.onNodeWithTag(HomeTags.TEXT).performClick()

        assertEquals(DrawTool.None, armed)
    }

    // -----------------------------------------------------------------------------------------
    // The bar — TD4
    // -----------------------------------------------------------------------------------------

    private var copied = false
    private var deleted = false
    private var commanded: FormatCommand? = null

    private fun setTextToolkit() {
        copied = false
        deleted = false
        commanded = null
        compose.setContent {
            ViveNotesTheme {
                Box(Modifier.size(400.dp)) {
                    ObjectTooltip(
                        swatch = null,
                        selectionBoundsInView = { RectF(60f, 200f, 360f, 300f) },
                        viewportSize = IntSize(1000, 1000),
                        onDelete = { deleted = true },
                        onCopy = { copied = true },
                        onRecolor = {},
                        extras = { SelectAllAction { commanded = FormatCommand.SelectAll } },
                    )
                }
            }
        }
    }

    @Test
    fun aTextBoxGetsCopyDeleteAndSelectAllButNoColour() {
        // `docs/diagram.md`: "hide color from [Prime Object], add select all". Colour is a mark on a
        // run — the Home tab owns it — so a container-level swatch would fight the ribbon.
        setTextToolkit()

        compose.onNodeWithTag(OBJECT_COPY_TAG).assertIsDisplayed()
        compose.onNodeWithTag(OBJECT_DELETE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(OBJECT_SELECT_ALL_TAG).assertIsDisplayed()
        compose.onNodeWithTag(OBJECT_COLOR_TAG).assertDoesNotExist()
        // Not a shape's bar, either: those extras belong to a kind that has a border.
        compose.onNodeWithTag(OBJECT_THICKNESS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(OBJECT_GROUP_TAG).assertDoesNotExist()
    }

    @Test
    fun theBarStillCopiesAndDeletesWithItsColourGone() {
        // Dropping a control from the base bar is exactly the change that takes a behaviour with it.
        setTextToolkit()

        compose.onNodeWithTag(OBJECT_COPY_TAG).performClick()
        compose.onNodeWithTag(OBJECT_DELETE_TAG).performClick()

        assertTrue("copy stopped working when the swatch went", copied)
        assertTrue("delete stopped working when the swatch went", deleted)
    }

    @Test
    fun selectAllGoesThroughTheCommandBus() {
        // AD6: one way to drive the editor. The bar is a few dp from it and still does not reach in.
        setTextToolkit()

        compose.onNodeWithTag(OBJECT_SELECT_ALL_TAG).performClick()

        assertEquals(FormatCommand.SelectAll, commanded)
    }
}
