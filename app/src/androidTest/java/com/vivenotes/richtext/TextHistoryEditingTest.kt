package com.vivenotes.richtext

import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import com.vivenotes.keyboard.ShortcutAction
import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * What the editor reports for the page's undo history: each keystroke with what it was, each
 * command as one change, and the platform's own undo kept from ever running.
 */
class TextHistoryEditingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun eachKeystrokeIsReportedWithTheTextAndCaretOnEitherSide() = withEditor(Block.of("ab")) { view, edits, _ ->
        view.setSelection(2)
        view.text!!.insert(2, "c")
        view.text!!.insert(3, " ")
        view.text!!.insert(4, "d")
        assertEquals(listOf("abc", "abc ", "abc d"), edits.map { it.after.single().text })
        assertEquals("ab", edits.first().before.single().text)
        assertEquals(TextSelection(2), edits.first().selectionBefore)
        assertEquals(TextSelection(3), edits.first().selectionAfter)
        assertEquals(listOf(true, true, false), edits.map { it.keystroke!!.joinable })
        assertEquals("each change starts from the one before", edits[0].after, edits[1].before)
    }

    @Test
    fun aCommandIsOneChangeAndNoKeystroke() = withEditor(Block.of("hello world")) { view, edits, _ ->
        view.setSelection(0, 5)
        view.apply(FormatCommand.ToggleMark(Mark.Bold))
        val bold = edits.single()
        assertNull(bold.keystroke)
        assertEquals(TextSelection(0, 5), bold.selectionBefore)
        assertTrue(bold.after.single().runs.first().marks.contains(Mark.Bold))

        view.setSelection(6, 11)
        view.apply(FormatCommand.Clipboard(ClipboardAction.Copy))
        assertEquals("copying changes nothing", 1, edits.size)
        view.setSelection(5)
        view.onKeyDown(KeyEvent.KEYCODE_ENTER, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, KeyEvent.META_CTRL_ON))
        assertEquals("Ctrl+Enter types through the watcher but is one command", 2, edits.size)
        assertNull(edits.last().keystroke)
        assertEquals(listOf("hello", " world"), edits.last().after.map { it.text })
    }

    @Test
    fun theTextFieldsOwnUndoNeverRuns() = withEditor(Block.of("one")) { view, edits, shortcuts ->
        view.setSelection(3)
        view.text!!.insert(3, " two")
        val typed = view.text.toString()

        val ctrlZ = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON)
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_Z, ctrlZ))
        assertTrue(view.onTextContextMenuItem(android.R.id.undo))
        assertTrue(view.onTextContextMenuItem(android.R.id.redo))
        assertEquals(typed, view.text.toString())
        assertEquals(listOf(ShortcutAction.Undo, ShortcutAction.Undo, ShortcutAction.Redo), shortcuts)
        assertEquals(1, edits.size)
    }

    @Test
    fun aRestoredStepIsNotReportedAgainAndTheNextEditStartsFromIt() = withEditor(Block.of("abc")) { view, edits, _ ->
        view.restore(listOf(Block.of("xyz")), TextSelection(1, 2))
        assertTrue(edits.isEmpty())
        assertEquals(1, view.selectionStart)
        assertEquals(2, view.selectionEnd)
        view.text!!.replace(1, 2, "Y")
        assertEquals("xyz", edits.single().before.single().text)
        assertEquals("xYz", edits.single().after.single().text)
    }

    private fun withEditor(
        vararg blocks: Block,
        body: (OutlineEditText, MutableList<TextEdit>, MutableList<ShortcutAction>) -> Unit,
    ) {
        var editor: OutlineEditText? = null
        val edits = mutableListOf<TextEdit>()
        val shortcuts = mutableListOf<ShortcutAction>()
        compose.setContent {
            ViveNotesTheme {
                AndroidView(factory = { context ->
                    OutlineEditText(context).apply {
                        onTextEdited = { edits += it }
                        onWorkspaceShortcut = { shortcuts += it; true }
                        setBlocks(blocks.toList())
                        editor = this
                    }
                })
            }
        }
        compose.waitUntil(5_000) { (editor?.width ?: 0) > 0 }
        compose.runOnIdle {
            val view = checkNotNull(editor)
            view.requestFocus()
            body(view, edits, shortcuts)
        }
    }
}
