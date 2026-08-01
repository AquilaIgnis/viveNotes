package com.vivenotes.ui.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import com.vivenotes.data.EditorDefaults
import com.vivenotes.data.ViewSettings
import com.vivenotes.model.Mark
import com.vivenotes.model.PageStyle
import com.vivenotes.richtext.FormatCommand
import com.vivenotes.richtext.SelectionState
import com.vivenotes.ui.theme.ViveNotesTheme

/**
 * The Home tab's font controls: what the size box reads, and the press-and-hold that moves the
 * app's default.
 *
 * Both halves turn on distinctions a wrong branch would quietly collapse — "no size mark" against
 * "several sizes at once", and picking a size against making it the default. The second used to be
 * one gesture, so choosing a size to write one sentence in changed what every later page opened at.
 */
class FontPickerTest {

    @get:Rule
    val compose = createComposeRule()

    private var picked: Mark? = null
    private var madeDefault: Mark? = null

    private fun setRibbon(
        selection: SelectionState,
        defaults: EditorDefaults = EditorDefaults(),
    ) {
        compose.setContent {
            ViveNotesTheme {
                Ribbon(
                    selection = selection,
                    activeTab = RibbonTab.Home,
                    onTabChange = {},
                    onCommand = { picked = (it as? FormatCommand.SetMark)?.mark },
                    defaults = defaults,
                    onSetDefault = { madeDefault = it },
                    pageStyle = PageStyle(),
                    viewSettings = ViewSettings(),
                    view = noopViewActions(),
                    pageOpen = true,
                )
            }
        }
    }

    @Test
    fun showsTheSizeOfTheSelectedText() {
        setRibbon(SelectionState(fontSize = 20, hasSelection = true))

        compose.onNodeWithText("20").assertIsDisplayed()
    }

    /** A selection with several sizes in it has no one number, and a guess would be a lie. */
    @Test
    fun showsNothingWhenTheSelectionMixesSizes() {
        setRibbon(SelectionState(fontSize = null, hasSelection = true), EditorDefaults(fontSize = 15))

        compose.onNodeWithText("15").assertDoesNotExist()
    }

    /** With no caret there is no text to describe, which is the one case where the default is it. */
    @Test
    fun fallsBackToTheDefaultWithNothingSelected() {
        setRibbon(SelectionState(fontSize = null, hasSelection = false), EditorDefaults(fontSize = 28))

        compose.onNodeWithText("28").assertIsDisplayed()
    }

    @Test
    fun holdingTheSizeMakesItTheDefault() {
        setRibbon(SelectionState(fontSize = 20, hasSelection = true))

        compose.onNodeWithTag(FontTags.SIZE).performTouchInput { longClick() }

        assertEquals(Mark.FontSize(20), madeDefault)
        assertNull("holding is not a pick; the selected text must not be restyled", picked)
    }

    /**
     * The state the app launches in, and the one the gesture was reported broken in: no editor
     * focused, so the box is showing the default rather than describing a caret.
     *
     * It was gated on there being a caret, which made the box show a number that holding it would
     * not set. What is on screen is what gets promoted, wherever the number came from.
     */
    @Test
    fun holdingTheNumberWorksWithNoEditorFocused() {
        setRibbon(SelectionState(), EditorDefaults(fontSize = 28))

        compose.onNodeWithTag(FontTags.SIZE).performTouchInput { longClick() }

        assertEquals(Mark.FontSize(28), madeDefault)
    }

    /** Nothing shown to promote, so nothing to promote it to. */
    @Test
    fun holdingAMixedSelectionChangesNothing() {
        setRibbon(SelectionState(fontSize = null, hasSelection = true), EditorDefaults(fontSize = 15))

        compose.onNodeWithTag(FontTags.SIZE).performTouchInput { longClick() }

        assertNull(madeDefault)
    }

    @Test
    fun tappingPicksASizeWithoutMovingTheDefault() {
        setRibbon(SelectionState(fontSize = 20, hasSelection = true))

        compose.onNodeWithTag(FontTags.SIZE).performClick()
        compose.onNodeWithText("36").performClick()

        assertEquals(Mark.FontSize(36), picked)
        assertNull("picking a size is an edit, not a preference", madeDefault)
    }

    @Test
    fun theMenuNamesTheDefaultAndTheGestureThatSetsIt() {
        setRibbon(SelectionState(fontSize = 20, hasSelection = true), EditorDefaults(fontSize = 28))

        compose.onNodeWithTag(FontTags.SIZE).performClick()

        compose.onNodeWithText("Default").assertIsDisplayed()
        compose.onNodeWithText("Hold a size to start new text at it").assertIsDisplayed()
    }

    /** The gesture as asked for: open the list of sizes and hold the one you want. */
    @Test
    fun holdingASizeInTheOpenMenuMakesItTheDefault() {
        setRibbon(SelectionState(fontSize = 20, hasSelection = true), EditorDefaults(fontSize = 15))

        compose.onNodeWithTag(FontTags.SIZE).performClick()
        compose.onNodeWithText("36").performTouchInput { longClick() }

        assertEquals(Mark.FontSize(36), madeDefault)
        assertNull("holding a size sets the default; it does not restyle the selection", picked)
    }

    /** The tag moving onto the held entry is the confirmation, so the menu has to stay up. */
    @Test
    fun holdingASizeLeavesTheMenuOpen() {
        setRibbon(SelectionState(fontSize = 20, hasSelection = true), EditorDefaults(fontSize = 15))

        compose.onNodeWithTag(FontTags.SIZE).performClick()
        compose.onNodeWithText("36").performTouchInput { longClick() }

        compose.onNodeWithText("36").assertIsDisplayed()
    }

    @Test
    fun holdingAFontInTheOpenMenuMakesItTheDefault() {
        setRibbon(SelectionState(fontFamily = "sans-serif", hasSelection = true))

        compose.onNodeWithTag(FontTags.FAMILY).performClick()
        compose.onNodeWithText("Lora").performTouchInput { longClick() }

        assertEquals(Mark.FontFamily("lora"), madeDefault)
        assertNull(picked)
    }

    /** Holding the box itself is the shortcut for "make what I am already using the default". */
    @Test
    fun theFamilyBoxWorksTheSameWay() {
        setRibbon(SelectionState(fontFamily = "lora", hasSelection = true))

        compose.onNodeWithText("Lora").assertIsDisplayed()
        compose.onNodeWithTag(FontTags.FAMILY).performTouchInput { longClick() }

        assertEquals(Mark.FontFamily("lora"), madeDefault)
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
