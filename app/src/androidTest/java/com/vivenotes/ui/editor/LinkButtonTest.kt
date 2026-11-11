package com.vivenotes.ui.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
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
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The Document tab's link button, driven through the whole [Ribbon] so it is pinned as reachable on
 * the tab that owns it.
 */
class LinkButtonTest {

    @get:Rule
    val compose = createComposeRule()

    private val commands = mutableListOf<FormatCommand>()

    private fun setRibbon(selection: SelectionState) {
        compose.setContent {
            ViveNotesTheme {
                Ribbon(
                    selection = selection,
                    activeTab = RibbonTab.Document,
                    onTabChange = {},
                    onCommand = { commands += it },
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
                    allowFinger = false,
                    draw = DrawActions(
                        selectTool = {},
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
    fun linkButtonUsesSelectedTextAndNormalizesTheAddress() {
        setRibbon(SelectionState(editorFocused = true, hasSelection = true, linkText = "selected"))
        compose.onNodeWithTag("insert-link").performScrollTo().performClick()
        compose.onNodeWithText("Insert link").assertIsDisplayed()
        val text = compose.onNodeWithTag("link-text")
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("selected", text)
        compose.onNodeWithTag("link-address").performTextReplacement("example.com/page")
        compose.onNodeWithTag("link-submit").performClick()
        assertTrue(commands.contains(FormatCommand.RetainInlineTarget))
        assertTrue(commands.contains(FormatCommand.InsertLink("selected", "https://example.com/page")))
    }

    private fun noopViewActions() = ViewActions(
        setRuleLines = {},
        setDefaultRuleLines = {},
        setPageColor = {},
        setHideTitle = {},
        setZoom = {},
        zoomIn = {},
        zoomOut = {},
        zoomToPageWidth = {},
        setTabsLayout = {},
        setCanvasDark = {},
        setLinkPreviews = {},
        openPane = {},
    )
}
