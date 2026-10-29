package com.vivenotes.keyboard

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyBindingsTest {
    private val ctrlZ = KeyChord(ShortcutKey.Z, ctrl = true)
    private val ctrlR = KeyChord(ShortcutKey.R, ctrl = true)
    private val ctrlShiftZ = KeyChord(ShortcutKey.Z, ctrl = true, shift = true)
    private val ctrlK = KeyChord(ShortcutKey.K, ctrl = true)
    private val ctrlC = KeyChord(ShortcutKey.C, ctrl = true)

    /** One chord, one command: the table's defaults must never have to choose between two. */
    @Test
    fun defaultChordsAreDistinctAndUsable() {
        val all = ShortcutAction.entries.flatMap { it.defaults }
        assertEquals("a default chord runs two commands", all.size, all.toSet().size)
        assertTrue(ShortcutAction.entries.all { action -> action.defaults.all(action::accepts) })
        assertTrue(ShortcutAction.entries.all { it.defaults.isNotEmpty() })
    }

    /** The desktop port's table, command for command: Android's old one plus its redo, clipboard and tool keys. */
    @Test
    fun defaultsMatchAndroidAndTheDesktop() {
        val shown = ShortcutAction.entries.associateWith { KeyBindings.Default.primary(it)?.label }
        assertEquals("Ctrl+N", shown[ShortcutAction.NewPage])
        assertEquals("Ctrl+Z", shown[ShortcutAction.Undo])
        assertEquals("Ctrl+R", shown[ShortcutAction.Redo])
        assertEquals("Ctrl+=", shown[ShortcutAction.ZoomIn])
        assertEquals("Ctrl+−", shown[ShortcutAction.ZoomOut])
        assertEquals("Ctrl+0", shown[ShortcutAction.ActualSize])
        assertEquals("Ctrl+B", shown[ShortcutAction.Bold])
        assertEquals("Tab", shown[ShortcutAction.Indent])
        assertEquals("Shift+Tab", shown[ShortcutAction.Outdent])
        assertEquals("Ctrl+Enter", shown[ShortcutAction.ExitList])
        assertEquals(ShortcutAction.ExitList,
            KeyBindings.Default.actionFor(KeyChord(ShortcutKey.NumPadEnter, ctrl = true)))
        assertEquals("Ctrl+Shift+V", shown[ShortcutAction.PastePlainText])
        assertEquals("Esc", shown[ShortcutAction.SelectTool])
        assertEquals("I", shown[ShortcutAction.TextTool])
        assertEquals("Delete", shown[ShortcutAction.DeleteSelection])
        assertEquals(ShortcutAction.Redo, KeyBindings.Default.actionFor(ctrlShiftZ))
        assertEquals(ShortcutAction.ZoomIn,
            KeyBindings.Default.actionFor(KeyChord(ShortcutKey.NumPadAdd, ctrl = true)))
        assertEquals(ShortcutGroup.entries, ShortcutAction.groups.map { it.first })
    }

    @Test
    fun everyChordSurvivesItsStoredForm() {
        for (key in ShortcutKey.entries) {
            for (modifiers in 0 until 16) {
                val chord = KeyChord(key, ctrl = modifiers and 1 != 0, shift = modifiers and 2 != 0,
                    alt = modifiers and 4 != 0, meta = modifiers and 8 != 0)
                assertEquals(chord, KeyChord.decode(chord.encode()))
            }
        }
        assertEquals("Ctrl+Shift+Alt+Super+Equals",
            KeyChord(ShortcutKey.Equals, ctrl = true, shift = true, alt = true, meta = true).encode())
        listOf("", "Ctrl+", "Ctrl+Nope", "Hyper+A", "Ctrl+Ctrl+A", "ctrl+A", "=").forEach {
            assertNull("decoded \"$it\"", KeyChord.decode(it))
        }
    }

    @Test
    fun onlyFunctionKeysTabEscAndDeleteStandAlone() {
        assertFalse(KeyChord(ShortcutKey.K).usable)
        assertFalse("Shift+K types a capital", KeyChord(ShortcutKey.K, shift = true).usable)
        assertTrue(KeyChord(ShortcutKey.Delete).usable)
        assertTrue(KeyChord(ShortcutKey.K, alt = true).usable)
        assertTrue(KeyChord(ShortcutKey.K, meta = true).usable)
        assertTrue(KeyChord(ShortcutKey.F5).usable)
        assertTrue(KeyChord(ShortcutKey.F12, shift = true).usable)
        assertTrue(KeyChord(ShortcutKey.Tab).usable)
    }

    /** The Text box tool's I: a key that types is a shortcut only for what never runs where it types. */
    @Test
    fun onlyACommandThatNeverRunsInTextTakesAKeyThatTypes() {
        val t = KeyChord(ShortcutKey.T)
        assertTrue(ShortcutAction.TextTool.accepts(t))
        assertTrue(ShortcutAction.TextTool.accepts(KeyChord(ShortcutKey.T, shift = true)))
        assertTrue(ShortcutAction.TextTool.accepts(ctrlK))
        assertFalse(ShortcutAction.Bold.accepts(t))
        assertFalse(ShortcutAction.NewPage.accepts(t))
        assertFalse(ShortcutAction.SelectTool.accepts(t))
        assertEquals(ShortcutScope.OutsideText, ShortcutAction.TextTool.scope)
        assertEquals(ShortcutGroup.Tools, ShortcutAction.TextTool.group)

        val stored = KeyBindings.of(mapOf(ShortcutAction.TextTool to listOf(t), ShortcutAction.Bold to listOf(KeyChord(ShortcutKey.Q))))
        assertEquals(t, stored.primary(ShortcutAction.TextTool))
        assertEquals("a bare letter for Bold is still no shortcut, so that change is ignored",
            KeyChord(ShortcutKey.B, ctrl = true), stored.primary(ShortcutAction.Bold))
    }

    @Test
    fun theTextToolRunsOnlyWhereNothingTypes() {
        val i = KeyChord(ShortcutKey.I)
        val bindings = KeyBindings.Default
        assertEquals(ShortcutDecision.Run(ShortcutAction.TextTool), bindings.decide(i, ShortcutScope.OutsideText))
        assertEquals("holding I must not flick the tool on and off",
            ShortcutDecision.Swallow, bindings.decide(i, ShortcutScope.OutsideText, repeat = true))
        assertEquals(ShortcutDecision.Pass, bindings.decide(i, ShortcutScope.Anywhere))
        assertEquals(ShortcutDecision.Pass, bindings.decide(i, ShortcutScope.Workspace))
        assertEquals("in a text box I is the letter", ShortcutDecision.Pass, bindings.decide(i, ShortcutScope.TextBox))
        // Nothing else runs where a letter is a shortcut.
        assertEquals(ShortcutDecision.Pass, bindings.decide(ctrlZ, ShortcutScope.OutsideText))
        assertEquals(ShortcutDecision.Pass, bindings.decide(KeyChord(ShortcutKey.Escape), ShortcutScope.OutsideText))
    }

    /** Regression guard: the text field's own Ctrl+C must not come back when the Text box tool takes it. */
    @Test
    fun aTextBoxDefaultGivenToTheTextToolIsUsedUpInATextBox() {
        val textOnCopy = KeyBindings.Default.rebind(ShortcutAction.TextTool, ctrlC)
        assertEquals(ShortcutDecision.Swallow, textOnCopy.decide(ctrlC, ShortcutScope.TextBox))
        assertEquals(ShortcutDecision.Run(ShortcutAction.TextTool), textOnCopy.decide(ctrlC, ShortcutScope.OutsideText))
        assertNull("I runs nothing once the tool moved off it", textOnCopy.actionFor(KeyChord(ShortcutKey.I)))
        assertEquals(ShortcutDecision.Pass, textOnCopy.decide(ctrlK, ShortcutScope.TextBox))
    }

    @Test
    fun rebindingTakesTheChordFromItsOwner() {
        val bindings = KeyBindings.Default.rebind(ShortcutAction.Undo, ctrlR)
        assertEquals(listOf(ctrlR), bindings.chords(ShortcutAction.Undo))
        // Redo keeps Android's Ctrl+Shift+Z, which is now what it shows.
        assertEquals(ctrlShiftZ, bindings.primary(ShortcutAction.Redo))
        assertTrue(bindings.isCustomized(ShortcutAction.Redo))
        // Ctrl+Z ran Undo alone, and now runs nothing.
        assertNull(bindings.actionFor(ctrlZ))
        assertEquals(ShortcutAction.Redo, KeyBindings.Default.conflict(ShortcutAction.Undo, ctrlR))
        assertNull(KeyBindings.Default.conflict(ShortcutAction.Redo, ctrlR))
        assertNull(KeyBindings.Default.conflict(ShortcutAction.Undo, ctrlK))
    }

    @Test
    fun aDisabledShortcutRunsNothingUntilReset() {
        val disabled = KeyBindings.Default.rebind(ShortcutAction.ZoomIn, null)
        assertNull(disabled.primary(ShortcutAction.ZoomIn))
        assertNull(disabled.actionFor(KeyChord(ShortcutKey.NumPadAdd, ctrl = true)))
        assertEquals(KeyBindings.Default, disabled.reset(ShortcutAction.ZoomIn))
    }

    @Test
    fun resetTakesTheDefaultsBackFromWhoeverHasThem() {
        val moved = KeyBindings.Default.rebind(ShortcutAction.Bold, ctrlZ)
        assertNull(moved.primary(ShortcutAction.Undo))
        val undoBack = moved.reset(ShortcutAction.Undo)
        assertEquals(ctrlZ, undoBack.primary(ShortcutAction.Undo))
        assertNull("Bold loses the chord Undo took back", undoBack.primary(ShortcutAction.Bold))
        assertEquals(KeyBindings.Default, undoBack.reset(ShortcutAction.Bold))
        assertEquals(KeyBindings.Default, moved.resetAll())
        assertFalse(KeyBindings.Default.isCustomized)
    }

    @Test
    fun givingAShortcutItsOwnDefaultsIsNoChange() {
        val bindings = KeyBindings.Default.rebind(ShortcutAction.NewPage, KeyChord(ShortcutKey.N, ctrl = true))
        assertEquals(KeyBindings.Default, bindings)
        assertTrue(bindings.changes.isEmpty())
    }

    @Test
    fun storedChangesAreCleanedAsTheyAreRead() {
        val bindings = KeyBindings.of(mapOf(
            ShortcutAction.Undo to listOf(ctrlK),
            // Claimed twice: the later action in the table keeps it.
            ShortcutAction.Bold to listOf(ctrlK),
            ShortcutAction.Italic to listOf(KeyChord(ShortcutKey.I)),
            ShortcutAction.NewPage to listOf(KeyChord(ShortcutKey.N, ctrl = true)),
        ))
        assertEquals(ctrlK, bindings.primary(ShortcutAction.Bold))
        assertNull(bindings.primary(ShortcutAction.Undo))
        assertEquals("a bare letter is not a shortcut, so that change is ignored",
            KeyChord(ShortcutKey.I, ctrl = true), bindings.primary(ShortcutAction.Italic))
        assertFalse(bindings.isCustomized(ShortcutAction.NewPage))
    }

    @Test
    fun eachDispatchPointRunsOnlyItsOwnCommands() {
        val bindings = KeyBindings.Default
        val esc = KeyChord(ShortcutKey.Escape)
        assertEquals(ShortcutDecision.Run(ShortcutAction.SelectTool), bindings.decide(esc, ShortcutScope.Anywhere))
        assertEquals(ShortcutDecision.Pass, bindings.decide(esc, ShortcutScope.Workspace))
        assertEquals(ShortcutDecision.Run(ShortcutAction.Undo), bindings.decide(ctrlZ, ShortcutScope.Workspace))
        assertEquals(ShortcutDecision.Run(ShortcutAction.DeleteSelection),
            bindings.decide(KeyChord(ShortcutKey.Delete), ShortcutScope.Workspace))
        assertEquals(ShortcutDecision.Pass,
            bindings.decide(KeyChord(ShortcutKey.Delete), ShortcutScope.TextBox))
        assertEquals(ShortcutDecision.Pass, bindings.decide(ctrlZ, ShortcutScope.Anywhere))
        // In a text box Ctrl+Z is the page's undo too: its history holds the text and its
        // formatting, which the text field's own would lose.
        assertEquals(ShortcutDecision.Run(ShortcutAction.Undo), bindings.decide(ctrlZ, ShortcutScope.TextBox))
        assertEquals(ShortcutDecision.Run(ShortcutAction.Copy), bindings.decide(ctrlC, ShortcutScope.TextBox))
        assertEquals(ShortcutDecision.Pass, bindings.decide(ctrlC, ShortcutScope.Workspace))
        assertEquals(ShortcutDecision.Pass, bindings.decide(ctrlK, ShortcutScope.TextBox))
    }

    /** Undo and Redo reach the canvas; the rest of the text box's commands, the clipboard's too, stay in it. */
    @Test
    fun onlyTheHistoryTextBoxCommandsAlsoRunOnTheCanvas() {
        val bindings = KeyBindings.Default
        for (action in listOf(ShortcutAction.Undo, ShortcutAction.Redo)) {
            assertEquals(ShortcutDecision.Run(action), bindings.decide(bindings.primary(action)!!, ShortcutScope.Workspace))
            assertEquals(ShortcutDecision.Run(action), bindings.decide(bindings.primary(action)!!, ShortcutScope.TextBox))
        }
        assertEquals(ShortcutDecision.Run(ShortcutAction.Redo), bindings.decide(ctrlShiftZ, ShortcutScope.Workspace))
        for (action in ShortcutAction.entries.filter { it.scope == ShortcutScope.TextBox && !it.alsoOnCanvas }) {
            assertEquals(action.name, ShortcutDecision.Pass, bindings.decide(bindings.primary(action)!!, ShortcutScope.Workspace))
        }
    }

    /** Regression guard: the text field's own undo would put text back without its formatting. */
    @Test
    fun aMovedUndoLeavesCtrlZToNoTextField() {
        val moved = KeyBindings.Default.rebind(ShortcutAction.Undo, ctrlK)
        assertEquals(ShortcutDecision.Swallow, moved.decide(ctrlZ, ShortcutScope.TextBox))
        assertEquals(ShortcutDecision.Run(ShortcutAction.Undo), moved.decide(ctrlK, ShortcutScope.TextBox))
        assertEquals(ShortcutDecision.Run(ShortcutAction.Undo), moved.decide(ctrlK, ShortcutScope.Workspace))
        val noRedo = KeyBindings.Default.rebind(ShortcutAction.Redo, null)
        assertEquals(ShortcutDecision.Swallow, noRedo.decide(ctrlShiftZ, ShortcutScope.TextBox))
    }

    /** Regression guard: a moved text box command must not fall back to the text field's own copy. */
    @Test
    fun aTextBoxDefaultThatRunsNothingIsUsedUp() {
        val moved = KeyBindings.Default.rebind(ShortcutAction.Copy, KeyChord(ShortcutKey.C, ctrl = true, shift = true))
        assertEquals(ShortcutDecision.Swallow, moved.decide(ctrlC, ShortcutScope.TextBox))
        assertEquals(ShortcutDecision.Run(ShortcutAction.Copy),
            moved.decide(KeyChord(ShortcutKey.C, ctrl = true, shift = true), ShortcutScope.TextBox))
        assertEquals(ShortcutDecision.Pass, moved.decide(ctrlC, ShortcutScope.Workspace))
    }

    @Test
    fun aWorkspaceCommandOnATextBoxDefaultRunsInTheTextBox() {
        val zoomOnCopy = KeyBindings.Default.rebind(ShortcutAction.ZoomIn, ctrlC)
        assertEquals(ShortcutDecision.Run(ShortcutAction.ZoomIn), zoomOnCopy.decide(ctrlC, ShortcutScope.TextBox))
        assertEquals(ShortcutDecision.Run(ShortcutAction.ZoomIn), zoomOnCopy.decide(ctrlC, ShortcutScope.Workspace))
        // Elsewhere a workspace command waits for the text field to leave the key.
        val zoomOnK = KeyBindings.Default.rebind(ShortcutAction.ZoomIn, ctrlK)
        assertEquals(ShortcutDecision.Pass, zoomOnK.decide(ctrlK, ShortcutScope.TextBox))
    }

    @Test
    fun heldKeysRepeatOnlyCommandsThatShould() {
        val bindings = KeyBindings.Default
        val newPage = KeyChord(ShortcutKey.N, ctrl = true)
        val zoomIn = KeyChord(ShortcutKey.Equals, ctrl = true)
        assertEquals(ShortcutDecision.Swallow, bindings.decide(newPage, ShortcutScope.Workspace, repeat = true))
        assertEquals(ShortcutDecision.Run(ShortcutAction.NewPage), bindings.decide(newPage, ShortcutScope.Workspace))
        assertEquals(ShortcutDecision.Run(ShortcutAction.ZoomIn),
            bindings.decide(zoomIn, ShortcutScope.Workspace, repeat = true))
    }

    /** Android prints Meta where the desktop prints Super, and both store it as Super. */
    @Test
    fun metaIsPrintedForAndroidAndStoredAsTheDesktopStoresIt() {
        val chord = KeyChord(ShortcutKey.K, ctrl = true, meta = true)
        assertEquals("Ctrl+Meta+K", chord.label)
        assertEquals("Ctrl+Super+K", chord.encode())
    }

    /** The Meta + / helper and dispatch both need every key on a real key code, and one each. */
    @Test
    fun everyKeyHasItsOwnAndroidKeyCode() {
        val codes = ShortcutKey.entries.map { it.keyCode }
        assertEquals(codes.size, codes.toSet().size)
        assertEquals(KeyEvent.KEYCODE_FORWARD_DEL, ShortcutKey.Delete.keyCode)
        assertEquals(KeyEvent.KEYCODE_DEL, ShortcutKey.Backspace.keyCode)
        assertEquals(KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON, ctrlShiftZ.metaState)
    }

    /** What the store writes is only the changes, and reads back to the same bindings. */
    @Test
    fun theStoredFormHoldsOnlyChangesAndSurvivesUnknownEntries() {
        val bindings = KeyBindings.Default.rebind(ShortcutAction.Undo, ctrlK).rebind(ShortcutAction.ZoomIn, null)
        val stored = KeyBindings.encode(bindings)
        assertEquals(listOf("Ctrl+K"), stored["Undo"])
        assertEquals(emptyList<String>(), stored["ZoomIn"])
        assertFalse("an unchanged command was written", "NewPage" in stored)
        assertEquals(bindings, KeyBindings.decode(stored))

        val later = stored + ("SomeCommandFromLater" to listOf("Ctrl+Q")) + ("Bold" to listOf("Ctrl+Nope"))
        assertEquals("unknown commands and unreadable chords are skipped", bindings, KeyBindings.decode(later))
        assertEquals(KeyBindings.Default, KeyBindings.decode(emptyMap()))
    }
}
