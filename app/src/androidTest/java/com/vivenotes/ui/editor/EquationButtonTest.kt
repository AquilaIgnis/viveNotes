package com.vivenotes.ui.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.junit.Rule
import org.junit.Test

/**
 * The Home tab's ƒ — the inline equation, which writes a `Mark` into the text under the caret.
 *
 * Driven through the whole [Ribbon] rather than through [EquationButton] alone, because the thing
 * worth pinning is that it is *reachable* — it moved here when the Insert tab was retired, and a
 * button that quietly stops being composed on the tab that now owns it is exactly the failure this
 * catches. Its canvas twin is `EquationObjectTest`.
 */
class EquationButtonTest {

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
    fun equationRequiresARealEditorCaret() {
        setRibbon(SelectionState(editorFocused = false))

        compose.onNodeWithTag(EquationTags.INLINE).performClick()
        compose.onNodeWithText("Insert equation").assertDoesNotExist()
    }

    @Test
    fun opensWithTheExampleAndRetainsTheEditorTarget() {
        setRibbon(SelectionState(editorFocused = true))

        compose.onNodeWithTag(EquationTags.INLINE).performClick()

        compose.onNodeWithText("Insert equation").assertIsDisplayed()
        val source = compose.onNodeWithTag(EquationTags.SOURCE)
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertTrue(source.contains("\\int"))
        assertTrue(commands.firstOrNull() == FormatCommand.RetainEquationTarget)
    }

    @Test
    fun opensAnExistingEquationForUpdate() {
        setRibbon(SelectionState(equation = "x^2", editorFocused = true))

        compose.onNodeWithTag(EquationTags.INLINE).performClick()

        compose.onNodeWithText("Edit equation").assertIsDisplayed()
        val source = compose.onNodeWithTag(EquationTags.SOURCE)
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertTrue(source.contains("x^2"))
        compose.onNodeWithText("Update").assertIsDisplayed()
    }

    @Test
    fun validatesAndSendsDelimiterFreeLatex() {
        setRibbon(SelectionState(editorFocused = true))
        compose.onNodeWithTag(EquationTags.INLINE).performClick()
        compose.onNodeWithTag(EquationTags.SOURCE).performTextReplacement("x^2+y^2=z^2")

        compose.onNodeWithTag(EquationTags.SUBMIT).performClick()

        compose.waitUntil(timeoutMillis = 10_000) {
            commands.any { it == FormatCommand.InsertEquation("x^2+y^2=z^2") }
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
        setLinkPreviews = {},
        openPane = {},
    )
}
