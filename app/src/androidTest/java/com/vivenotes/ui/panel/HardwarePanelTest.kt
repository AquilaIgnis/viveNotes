package com.vivenotes.ui.panel

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vivenotes.data.StylusAction
import com.vivenotes.data.StylusButtonMap
import com.vivenotes.ui.StylusPress
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The Hardware pane: two devices along the top, and the settings below belong to whichever is
 * selected.
 *
 * The tests worth having here are the ones about the *switch*, since that is the whole structure —
 * a section that shows for the wrong device, or shows for both, is the failure this pane has — plus
 * the pen-button rows, where the failure is a row writing its choice into the wrong binding.
 */
class HardwarePanelTest {

    @get:Rule
    val compose = createComposeRule()

    private var allowFinger = false
    private var buttons = StylusButtonMap()

    @Test
    fun itOpensOnStylus() {
        setPanel()

        compose.onNodeWithText("Let a finger draw").assertIsDisplayed()
    }

    @Test
    fun keyboardShowsTheShortcutsAndNotTheStylusSettings() {
        setPanel()

        compose.onNodeWithTag(HardwareTags.kind(HardwareKind.Keyboard)).performClick()

        compose.onNodeWithTag(HardwareTags.SHORTCUTS).assertIsDisplayed()
        compose.onNodeWithText("Let a finger draw").assertDoesNotExist()
        compose.onNodeWithText("Pen button").assertDoesNotExist()
    }

    /** The table is the live one, so a shortcut that exists is a shortcut that is listed. */
    @Test
    fun theShortcutListNamesRealChords() {
        setPanel()

        compose.onNodeWithTag(HardwareTags.kind(HardwareKind.Keyboard)).performClick()

        compose.onNodeWithText("New page").assertIsDisplayed()
        compose.onNodeWithText("Ctrl+N").assertIsDisplayed()
        compose.onNodeWithText("Ctrl+Shift+Z").assertIsDisplayed()
    }

    @Test
    fun theStylusSectionTogglesWhoMayDraw() {
        setPanel()

        compose.onNodeWithTag(PanelTags.field("Let a finger draw")).performClick()

        assertEquals(true, allowFinger)
    }

    /** Coming back to a device shows its settings again, rather than the one selected in between. */
    @Test
    fun switchingBackToStylusRestoresItsSettings() {
        setPanel()

        compose.onNodeWithTag(HardwareTags.kind(HardwareKind.Keyboard)).performClick()
        compose.onNodeWithTag(HardwareTags.kind(HardwareKind.Stylus)).performClick()

        compose.onNodeWithText("Let a finger draw").assertIsDisplayed()
    }

    // --- the pen button rows ---------------------------------------------------------------------

    /** Three rows, always — a pen that reports fewer clicks is not something the app can know (SB8). */
    @Test
    fun allThreeClickCountsGetARow() {
        setPanel()

        StylusPress.entries.forEach { press ->
            compose.onNodeWithTag(PanelTags.field(press.label)).assertIsDisplayed()
        }
    }

    /** Each row shows what that binding currently is, not what another row holds. */
    @Test
    fun eachRowShowsItsOwnBinding() {
        buttons = StylusButtonMap(
            single = StylusAction.Pen2,
            double = StylusAction.Highlighter,
            triple = StylusAction.Undo,
        )
        setPanel()

        compose.onNodeWithText(StylusAction.Pen2.label).assertIsDisplayed()
        compose.onNodeWithText(StylusAction.Highlighter.label).assertIsDisplayed()
        compose.onNodeWithText(StylusAction.Undo.label).assertIsDisplayed()
    }

    /**
     * The failure this guards is a row wired to the wrong field: picking on the double-click row must
     * change `double` and leave the other two exactly as they were.
     */
    @Test
    fun pickingAnActionRebindsThatClickCountAndOnlyThatOne() {
        setPanel()

        compose.onNodeWithTag(PanelTags.field(StylusPress.Double.label)).performClick()
        compose.onNodeWithText(StylusAction.Undo.label).performClick()

        assertEquals(StylusAction.Undo, buttons.double)
        assertEquals(StylusButtonMap().single, buttons.single)
        assertEquals(StylusButtonMap().triple, buttons.triple)
    }

    /** A third click starts unbound, and binding it is the same one gesture. */
    @Test
    fun theUnboundThirdClickCanBeGivenAnAction() {
        setPanel()

        compose.onNodeWithTag(PanelTags.field(StylusPress.Triple.label)).performClick()
        compose.onNodeWithText(StylusAction.Redo.label).performClick()

        assertEquals(StylusAction.Redo, buttons.triple)
    }

    /**
     * The (i) explains why there is no double-click speed to set. Hidden until asked for, like every
     * other explanation in a pane.
     */
    @Test
    fun theSectionExplainsThatThePenCountsItsOwnClicks() {
        setPanel()

        compose.onNodeWithContentDescription("About Pen button").performClick()

        compose.onNodeWithText("counts its own clicks", substring = true).assertIsDisplayed()
    }

    private fun setPanel() {
        compose.setContent {
            ViveNotesTheme {
                Column {
                    HardwarePanelContent(
                        allowFinger = allowFinger,
                        onSetDrawWithFinger = { allowFinger = it },
                        buttons = buttons,
                        onSetButtons = { buttons = it },
                    )
                }
            }
        }
    }
}
