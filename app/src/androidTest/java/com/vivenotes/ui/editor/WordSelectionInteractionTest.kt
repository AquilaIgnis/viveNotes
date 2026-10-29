package com.vivenotes.ui.editor

import android.content.ClipboardManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.vivenotes.data.EditorDefaults
import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.model.PageStyle
import com.vivenotes.model.newTable
import com.vivenotes.richtext.OutlineEditText
import com.vivenotes.richtext.TextEdit
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.OutlineBox
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Ctrl+double-click with a mouse selects words beside each other, in a text box or across a table's
 * cells, and copying, formatting and typing then reach every one of them — as one step of Undo.
 */
class WordSelectionInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        /** What the emulator labels the clip it copies over from the host. */
        const val HOST_CLIP = "host clipboard"
    }

    private val grid = newTable(2, 1, x = 60f, y = 420f)
    private val table = grid.copy(rows = grid.rows.map { row ->
        row.copy(cells = row.cells.mapIndexed { index, cell ->
            cell.copy(blocks = listOf(Block.of(if (index == 0) "alpha beta" else "gamma omega")))
        })
    })
    private val box = OutlineBox("box", x = 60f, y = 140f, width = 500f)
    private val blocks = mutableMapOf<String, List<Block>>(box.id to listOf(Block.of("one middle three"))).apply {
        table.rows.flatMap { it.cells }.forEach { put(it.id, it.blocks) }
    }

    /** Each step of Undo the page was handed: the editors' changes, grouped as the history groups them. */
    private val steps = mutableListOf<List<String>>()
    private var collecting: MutableList<String>? = null

    private fun setPage() {
        compose.setContent {
            ViveNotesTheme {
                EditorPane(
                    title = "",
                    createdAt = 0L,
                    defaults = EditorDefaults(),
                    style = PageStyle(hideTitle = true),
                    zoom = 1f,
                    onTitleChange = {},
                    outlines = listOf(box),
                    pageRevision = 0,
                    pageId = "page",
                    initialBlocksFor = { blocks[it] ?: listOf(Block.empty()) },
                    commands = MutableSharedFlow(),
                    onBlocksChanged = { id, changed -> blocks[id] = changed },
                    onTextEdited = { id: String, _: TextEdit -> collecting?.add(id) ?: steps.add(listOf(id)) },
                    onTextEditsTogether = { body ->
                        val step = mutableListOf<String>()
                        collecting = step
                        try { body() } finally { collecting = null }
                        if (step.isNotEmpty()) steps += step
                    },
                    onSelectionChanged = {},
                    onMarkArmed = {},
                    onCreateOutline = { _, _ -> "" },
                    textArmed = false,
                    onMoveOutline = { _, _, _ -> },
                    onResizeOutline = { _, _ -> },
                    onSetOutlineMinHeight = { _, _ -> },
                    onOutlineBlurred = {},
                    tables = listOf(table),
                    onCanvasMeasured = { _, _ -> },
                    showPrintMargins = false,
                )
            }
        }
        compose.waitUntil(5_000) { editors().size == 3 && editors().all { it.width > 0 } }
    }

    private fun editors(): List<OutlineEditText> {
        val found = mutableListOf<OutlineEditText>()
        fun walk(view: View) {
            if (view is OutlineEditText) found += view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(compose.activity.window.decorView)
        return found
    }

    private fun editor(text: String): OutlineEditText = editors().single { it.text.toString() == text }

    /** Two clicks of the mouse's primary button at [offset], with Ctrl held unless [ctrl] is false. */
    private fun OutlineEditText.doubleClick(offset: Int, ctrl: Boolean = true) {
        val shown = layout
        val line = shown.getLineForOffset(offset)
        val x = shown.getPrimaryHorizontal(offset) + totalPaddingLeft + 2f
        val y = (shown.getLineTop(line) + shown.getLineBottom(line)) / 2f + totalPaddingTop
        val start = android.os.SystemClock.uptimeMillis()
        repeat(2) { click ->
            val at = start + click * 120L
            dispatchTouchEvent(mouse(MotionEvent.ACTION_DOWN, at, x, y, ctrl, MotionEvent.BUTTON_PRIMARY))
            dispatchTouchEvent(mouse(MotionEvent.ACTION_UP, at + 40L, x, y, ctrl, 0))
        }
    }

    private fun mouse(action: Int, time: Long, x: Float, y: Float, ctrl: Boolean, buttons: Int): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y })
        val meta = if (ctrl) KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON else 0
        return MotionEvent.obtain(time, time, action, 1, properties, coordinates, meta, buttons,
            1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
    }

    private fun OutlineEditText.ctrl(keyCode: Int) {
        onKeyDown(keyCode, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON))
    }

    private fun OutlineEditText.marks(start: Int, end: Int): Set<Mark> = marksIn(TextSelection(start, end))

    private val clipboard: ClipboardManager get() = compose.activity.getSystemService(ClipboardManager::class.java)

    /** The clip, or null when the emulator's clipboard sharing has already put its own in its place. */
    private fun ownClip(): String? = clipboard.primaryClip
        ?.takeUnless { it.description.label?.toString() == HOST_CLIP }
        ?.getItemAt(0)?.text?.toString()

    /**
     * What [action] put on the clipboard. An emulator sharing its clipboard with the host replaces
     * any clip within seconds, so an action that can be repeated is, until its clip is read first.
     */
    private fun clipMadeBy(action: () -> Unit): String? {
        repeat(5) {
            action()
            ownClip()?.let { return it }
        }
        return null
    }

    @Test
    fun ctrlDoubleClickKeepsBothWordsSelectedAndCopyAndBoldUseBoth() {
        setPage()
        compose.runOnIdle {
            val field = editor("one middle three")
            field.doubleClick(1)
            field.doubleClick(13)
            assertTrue(field.hasFocus())
            assertEquals("the word clicked last is the editor's own selection", 11, field.selectionStart)
            assertEquals(16, field.selectionEnd)
            assertEquals("the earlier word is still drawn selected", listOf(TextSelection(0, 3)), field.extraSelections)

            assertEquals("one\nthree", clipMadeBy { field.ctrl(KeyEvent.KEYCODE_C) })

            field.ctrl(KeyEvent.KEYCODE_B)
            assertTrue(Mark.Bold in field.marks(0, 3))
            assertFalse(Mark.Bold in field.marks(4, 10))
            assertTrue(Mark.Bold in field.marks(11, 16))
            assertEquals("bold at both words is one step", listOf(listOf(box.id)), steps)
            assertEquals(listOf(TextSelection(0, 3)), field.extraSelections)
        }
    }

    @Test
    fun ctrlDoubleClickAcrossCellsFormatsAndCopiesEveryWord() {
        setPage()
        compose.runOnIdle {
            val left = editor("alpha beta")
            val right = editor("gamma omega")
            right.doubleClick(8)
            left.doubleClick(2)
            assertTrue(left.hasFocus())
            assertEquals(listOf(TextSelection(6, 11)), right.extraSelections)

            assertEquals("copied in reading order", "alpha\nomega", clipMadeBy { left.ctrl(KeyEvent.KEYCODE_C) })

            left.ctrl(KeyEvent.KEYCODE_B)
            assertTrue(Mark.Bold in left.marks(0, 5))
            assertFalse(Mark.Bold in left.marks(6, 10))
            assertFalse(Mark.Bold in right.marks(0, 5))
            assertTrue(Mark.Bold in right.marks(6, 11))
            val cells = table.rows.single().cells.map { it.id }
            assertEquals("one step across both cells", 1, steps.size)
            assertEquals(cells.toSet(), steps.single().toSet())
        }
    }

    @Test
    fun typingReplacesEverySelectedWordAndAPlainClickPutsThemDown() {
        setPage()
        compose.runOnIdle {
            val field = editor("one middle three")
            field.doubleClick(1)
            field.doubleClick(13)
            // Through an input connection, as a keyboard types: it moves the caret, then replaces.
            val keyboard = field.onCreateInputConnection(EditorInfo())!!
            keyboard.commitText("new", 1)
            assertEquals("new middle new", field.text.toString())
            keyboard.commitText("!", 1)
            assertEquals("new! middle new!", field.text.toString())
            assertEquals("each keystroke is one step for both words", 2, steps.size)
            assertTrue(steps.all { it == listOf(box.id, box.id) })

            field.doubleClick(5, ctrl = false)
            assertTrue(field.extraSelections.isEmpty())
            keyboard.commitText("?", 1)
            assertEquals("after a plain click, typing is typing in one place", 1, field.text.toString().count { it == '?' })
        }
    }

    /** The selection toolbar's Copy, Cut and Paste reach every word, as the ribbon's and the keys' do. */
    @Test
    fun theSelectionToolbarsClipboardActionsUseEveryWord() {
        setPage()
        compose.runOnIdle {
            val field = editor("one middle three")
            field.doubleClick(1)
            field.doubleClick(13)
            assertEquals("one\nthree", clipMadeBy { assertTrue(field.onTextContextMenuItem(android.R.id.copy)) })

            assertTrue(field.onTextContextMenuItem(android.R.id.cut))
            assertEquals(" middle ", field.text.toString())
            // A cut cannot be made twice; when the emulator got to the clip first there is nothing to read.
            ownClip()?.let { assertEquals("one\nthree", it) }
            assertEquals("cutting both words is one step", 1, steps.size)

            assertEquals("X", clipMadeBy { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("note", "X")) })
            assertTrue(field.onTextContextMenuItem(android.R.id.paste))
            assertEquals("pasted where each word was", "X middle X", field.text.toString())
            assertEquals(2, steps.size)
        }
    }

    /** A keyboard that composes each word as it is typed, as Gboard does, still types at every word. */
    @Test
    fun aComposingKeyboardTypesAtEveryWord() {
        setPage()
        compose.runOnIdle {
            val field = editor("one middle three")
            field.doubleClick(1)
            field.doubleClick(13)
            val keyboard = field.onCreateInputConnection(EditorInfo())!!
            keyboard.setComposingText("n", 1)
            keyboard.setComposingText("ne", 1)
            keyboard.setComposingText("new", 1)
            keyboard.finishComposingText()
            assertEquals("new middle new", field.text.toString())
            keyboard.commitText("!", 1)
            assertEquals("new! middle new!", field.text.toString())
        }
    }

    @Test
    fun repeatingAWordTakesItBackOut() {
        setPage()
        compose.runOnIdle {
            val field = editor("one middle three")
            field.doubleClick(1)
            field.doubleClick(13)
            field.doubleClick(1)
            assertTrue(field.extraSelections.isEmpty())
            assertEquals(11, field.selectionStart)
            assertEquals(16, field.selectionEnd)
        }
    }
}
