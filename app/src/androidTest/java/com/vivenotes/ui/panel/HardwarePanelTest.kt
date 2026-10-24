package com.vivenotes.ui.panel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vivenotes.data.StylusAction
import com.vivenotes.data.StylusButtonMap
import com.vivenotes.keyboard.KeyBindings
import com.vivenotes.keyboard.KeyChord
import com.vivenotes.keyboard.ShortcutAction
import com.vivenotes.keyboard.ShortcutKey
import com.vivenotes.ui.StylusPress
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
@OptIn(ExperimentalTestApi::class)
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
        compose.onNodeWithText("Ctrl+R").assertIsDisplayed()
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

    /** Three rows, always — a pen that reports fewer clicks is not something the app can know. */
    @Test
    fun allThreeClickCountsGetARow() {
        setPanel()

        StylusPress.entries.forEach { press ->
            compose.onNodeWithTag(PanelTags.field(press.name)).assertIsDisplayed()
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

        compose.onNodeWithText("Pen 2").assertIsDisplayed()
        compose.onNodeWithText("Highlighter").assertIsDisplayed()
        compose.onNodeWithText("Undo").assertIsDisplayed()
    }

    /**
     * The failure this guards is a row wired to the wrong field: picking on the double-click row must
     * change `double` and leave the other two exactly as they were.
     */
    @Test
    fun pickingAnActionRebindsThatClickCountAndOnlyThatOne() {
        setPanel()

        compose.onNodeWithTag(PanelTags.field(StylusPress.Double.name)).performClick()
        compose.onNodeWithText("Undo").performClick()

        assertEquals(StylusAction.Undo, buttons.double)
        assertEquals(StylusButtonMap().single, buttons.single)
        assertEquals(StylusButtonMap().triple, buttons.triple)
    }

    /** A third click starts unbound, and binding it is the same one gesture. */
    @Test
    fun theUnboundThirdClickCanBeGivenAnAction() {
        setPanel()

        compose.onNodeWithTag(PanelTags.field(StylusPress.Triple.name)).performClick()
        compose.onNodeWithText("Redo").performClick()

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

    // --- the keyboard shortcuts -------------------------------------------------------------------

    private val ctrlK = KeyChord(ShortcutKey.K, ctrl = true)

    private fun openKeyboard() {
        compose.onNodeWithTag(HardwareTags.kind(HardwareKind.Keyboard)).performClick()
    }

    private fun capture(keys: KeyInjectionScope.() -> Unit) {
        // Nothing here focuses it: the dialog does that itself once its window has the focus,
        // which is what a keyboard needs, and what this waits for.
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag(ShortcutDialogTags.CAPTURE) and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(ShortcutDialogTags.CAPTURE).performKeyInput(keys)
        compose.waitForIdle()
    }

    /** A row opens the key capture, and the next chord pressed is the shortcut. */
    @Test
    fun aShortcutIsChangedByPressingItsNewKeys() {
        setPanel()
        openKeyboard()

        compose.onNodeWithTag(HardwareTags.shortcut(ShortcutAction.Undo)).performClick()
        compose.onNodeWithTag(ShortcutDialogTags.CAPTURE).assertIsDisplayed()
        capture {
            keyDown(Key.CtrlLeft)
            pressKey(Key.K)
            keyUp(Key.CtrlLeft)
        }

        assertEquals(ctrlK, bindings.value.primary(ShortcutAction.Undo))
        compose.onNodeWithTag(ShortcutDialogTags.CAPTURE).assertDoesNotExist()
        compose.onNodeWithText("Ctrl+K").assertIsDisplayed()
        compose.onNodeWithTag(HardwareTags.reset(ShortcutAction.Undo)).assertIsDisplayed()
    }

    /** A chord another command has is taken only once Replace says so, and leaves that command. */
    @Test
    fun aKeyAnotherCommandUsesIsTakenOnlyOnReplace() {
        setPanel()
        openKeyboard()

        compose.onNodeWithTag(HardwareTags.shortcut(ShortcutAction.Undo)).performClick()
        capture {
            keyDown(Key.CtrlLeft)
            pressKey(Key.R)
            keyUp(Key.CtrlLeft)
        }
        compose.onNodeWithTag(ShortcutDialogTags.MESSAGE).assertTextContains("already used for Redo", substring = true)
        assertEquals(KeyBindings.Default, bindings.value)

        compose.onNodeWithTag(ShortcutDialogTags.REPLACE).performClick()
        compose.waitForIdle()

        assertEquals(KeyChord(ShortcutKey.R, ctrl = true), bindings.value.primary(ShortcutAction.Undo))
        assertEquals("Redo keeps Android's Ctrl+Shift+Z",
            KeyChord(ShortcutKey.Z, ctrl = true, shift = true), bindings.value.primary(ShortcutAction.Redo))
    }

    /** A key that types is refused for a command that runs in text; Backspace disables; Esc cancels. */
    @Test
    fun aKeyThatTypesIsRefusedBackspaceDisablesAndEscCancels() {
        setPanel()
        openKeyboard()

        compose.onNodeWithTag(HardwareTags.shortcut(ShortcutAction.Undo)).performClick()
        capture { pressKey(Key.K) }
        compose.onNodeWithTag(ShortcutDialogTags.MESSAGE).assertTextContains("on its own", substring = true)
        assertEquals(KeyBindings.Default, bindings.value)

        capture { pressKey(Key.Escape) }
        compose.onNodeWithTag(ShortcutDialogTags.CAPTURE).assertDoesNotExist()
        assertEquals(KeyBindings.Default, bindings.value)

        compose.onNodeWithTag(HardwareTags.shortcut(ShortcutAction.Undo)).performClick()
        capture { pressKey(Key.Backspace) }
        assertNull(bindings.value.primary(ShortcutAction.Undo))
        compose.onNodeWithTag(HardwareTags.shortcut(ShortcutAction.Undo)).assertTextContains("Disabled", substring = true)
    }

    /** One row's reset puts that one back; Reset all asks before it throws every change away. */
    @Test
    fun resetPutsOneBackAndResetAllAsksFirst() {
        bindings.value = KeyBindings.Default.rebind(ShortcutAction.Undo, ctrlK)
            .rebind(ShortcutAction.Bold, KeyChord(ShortcutKey.B, ctrl = true, shift = true))
        setPanel()
        openKeyboard()

        compose.onNodeWithTag(HardwareTags.reset(ShortcutAction.Undo)).performClick()
        compose.waitForIdle()
        assertEquals(KeyChord(ShortcutKey.Z, ctrl = true), bindings.value.primary(ShortcutAction.Undo))
        assertTrue(bindings.value.isCustomized(ShortcutAction.Bold))

        compose.onNodeWithTag(HardwareTags.RESET_ALL).performScrollTo().performClick()
        compose.onNodeWithTag(ShortcutDialogTags.CANCEL_RESET_ALL).performClick()
        compose.waitForIdle()
        assertTrue("Cancel reset everything", bindings.value.isCustomized)

        compose.onNodeWithTag(HardwareTags.RESET_ALL).performScrollTo().performClick()
        compose.onNodeWithTag(ShortcutDialogTags.CONFIRM_RESET_ALL).performClick()
        compose.waitForIdle()
        assertEquals(KeyBindings.Default, bindings.value)
        compose.onNodeWithTag(HardwareTags.RESET_ALL).assertIsNotEnabled()
    }

    private val bindings = mutableStateOf(KeyBindings.Default)

    private fun setPanel() {
        compose.setContent {
            ViveNotesTheme {
                // Scrollable as the docked pane is: the shortcut list runs past one screen.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    HardwarePanelContent(
                        allowFinger = allowFinger,
                        onSetDrawWithFinger = { allowFinger = it },
                        buttons = buttons,
                        onSetButtons = { buttons = it },
                        keyBindings = bindings.value,
                        onSetKeyBindings = { bindings.value = it },
                    )
                }
            }
        }
    }
}
