package com.vivenotes.ui

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.data.AttachmentStore
import com.vivenotes.data.EditorDefaultsStore
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.PenSettingsStore
import com.vivenotes.data.ViewSettingsStore
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.ink.InkPoint
import com.vivenotes.model.Align
import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.model.Run
import com.vivenotes.richtext.Keystroke
import com.vivenotes.richtext.TextEdit
import com.vivenotes.richtext.TextSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Text and its formatting are undo steps, in the same history as the page's objects. The editor is
 * stood in for by what `OutlineEditText` reports — `TextHistoryEditingTest` checks that half — so
 * this is the history's half: how steps join, what Undo puts back, and what leaves the ring.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TextHistoryViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private lateinit var db: NotesDatabase
    private lateinit var vm: NotesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        val repository = NotesRepository(db)
        kotlinx.coroutines.runBlocking { repository.seedIfEmpty() }
        vm = NotesViewModel(repository, AttachmentStore(context, db), EditorDefaultsStore(context),
            ViewSettingsStore(context), PenSettingsStore(context), inkDispatcher = dispatcher)
        scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    /**
     * One container's editor, as far as the history sees it: the text, the caret, and the change
     * each keystroke or command reports. Undo and Redo come back through [NotesViewModel.textRestores].
     */
    private inner class Editor(val id: String) {
        var caret = TextSelection(0)
        val blocks: List<Block> get() = vm.initialBlocksFor(id)
        val text: String get() = blocks.joinToString("\n") { it.text }

        fun report(after: List<Block>, selection: TextSelection, keystroke: Keystroke?) {
            val before = blocks
            vm.onBlocksChanged(id, after)
            vm.onTextEdited(id, TextEdit(before, after, caret, selection, keystroke, composing = false))
            caret = selection
        }

        private fun write(new: String, selection: TextSelection) {
            val old = text
            val lines = new.split('\n')
            val after = lines.mapIndexed { i, line ->
                blocks.getOrNull(i)?.copy(runs = listOf(Run(line))) ?: Block.of(line)
            }
            report(after, selection, Keystroke.of(old, new, caret.clamped(old.length)))
        }

        fun type(chars: String) = chars.forEach { char ->
            val t = text
            write(t.substring(0, caret.min) + char + t.substring(caret.max), TextSelection(caret.min + 1))
        }

        fun backspace(times: Int) = repeat(times) {
            val t = text
            write(t.removeRange(caret.start - 1, caret.start), TextSelection(caret.start - 1))
        }

        fun select(selection: TextSelection) { caret = selection }

        /** A command over the whole first paragraph — Bold, say, or Center. */
        fun command(change: (Block) -> Block) = report(listOf(change(blocks.first())) + blocks.drop(1), caret, keystroke = null)
    }

    private fun TestScope.editor(): Editor {
        val editor = Editor(vm.createOutline(100f, 300f))
        backgroundScope.launch { vm.textRestores.collect { if (it.outlineId == editor.id) editor.caret = it.selection } }
        runCurrent()
        return editor
    }

    private fun TestScope.undo(times: Int = 1) = repeat(times) { vm.undoCanvas(); runCurrent() }
    private fun TestScope.redo(times: Int = 1) = repeat(times) { vm.redoCanvas(); runCurrent() }

    private val Block.marks: Set<Mark> get() = runs.first().marks

    @Test
    fun typingIsUndoneAWordAtATime() = runTest(dispatcher) {
        val box = editor()
        box.type("hello world")
        undo()
        assertEquals("hello ", box.text)
        undo()
        assertEquals("", box.text)
        redo()
        assertEquals("hello ", box.text)
        redo()
        assertEquals("hello world", box.text)
        assertEquals("the caret goes back to where the text changed", TextSelection(11), box.caret)
    }

    @Test
    fun aNewLineStartsTheNextStep() = runTest(dispatcher) {
        val box = editor()
        box.type("one\ntwo")
        undo()
        assertEquals("one\n", box.text)
        undo()
        assertEquals("", box.text)
    }

    @Test
    fun deletingIsOneStepAndAMovedCaretStartsAnother() = runTest(dispatcher) {
        val deleted = editor()
        deleted.type("abcdef")
        deleted.backspace(3)
        assertEquals("abc", deleted.text)
        undo()
        assertEquals("abcdef", deleted.text)
        assertEquals(TextSelection(6), deleted.caret)

        val inserted = editor()
        inserted.type("abc")
        inserted.select(TextSelection(1))
        inserted.type("x")
        assertEquals("axbc", inserted.text)
        undo()
        assertEquals("abc", inserted.text)
        assertEquals(TextSelection(1), inserted.caret)
    }

    @Test
    fun formattingIsUndoneAndRedoneStepByStep() = runTest(dispatcher) {
        val box = editor()
        box.type("hello world")
        box.select(TextSelection(0, 5))
        box.command { it.copy(runs = listOf(Run("hello", setOf(Mark.Bold)), Run(" world"))) }
        box.command { it.copy(align = Align.Center) }
        val formatted = box.blocks

        undo()
        assertEquals(Align.Start, box.blocks.first().align)
        assertEquals(setOf<Mark>(Mark.Bold), box.blocks.first().marks)
        assertEquals(TextSelection(0, 5), box.caret)
        undo()
        assertEquals(emptySet<Mark>(), box.blocks.first().marks)
        assertEquals("undoing formatting leaves the text", "hello world", box.text)
        redo(2)
        assertEquals(formatted, box.blocks)
        assertFalse(vm.canvasUndoState.value.canRedo)
    }

    @Test
    fun aCommandEndsTheTypingBeforeIt() = runTest(dispatcher) {
        val box = editor()
        box.type("ab")
        box.command { it.copy(align = Align.Center) }
        box.type("c")
        undo()
        assertEquals("ab", box.text)
        assertEquals(Align.Center, box.blocks.first().align)
        undo()
        assertEquals(Align.Start, box.blocks.first().align)
        undo()
        assertEquals("", box.text)
    }

    @Test
    fun typingAfterAnUndoDoesNotJoinTheStepBelow() = runTest(dispatcher) {
        val box = editor()
        box.type("abcd")
        box.select(TextSelection(2))
        box.type("x")
        undo()
        assertEquals("abcd", box.text)
        box.type("y")
        assertFalse("a new edit drops the steps that were undone", vm.canvasUndoState.value.canRedo)
        undo()
        assertEquals("abcd", box.text)
        undo()
        assertEquals("", box.text)
    }

    @Test
    fun textAndObjectStepsShareOneHistory() = runTest(dispatcher) {
        val box = editor()
        box.type("note")
        vm.copyOutline(box.id)
        vm.pasteObjects(InkPoint(400f, 500f))
        val pasted = vm.uiState.value.outlines.size
        box.type("s")
        undo()
        assertEquals("note", box.text)
        assertEquals("the typing is undone before the paste", pasted, vm.uiState.value.outlines.size)
        undo()
        assertEquals(pasted - 1, vm.uiState.value.outlines.size)
        undo()
        assertEquals("", box.text)
    }

    @Test
    fun anAbandonedBoxTakesItsTextStepsWithIt() = runTest(dispatcher) {
        val box = editor()
        box.type("x")
        box.backspace(1)
        assertTrue(vm.canvasUndoState.value.canUndo)
        vm.onOutlineBlurred(box.id)
        assertFalse(vm.uiState.value.outlines.any { it.id == box.id })
        assertFalse(vm.canvasUndoState.value.canUndo)
        assertFalse(vm.canvasUndoState.value.canRedo)
    }

    /** Text steps count against the page's ring like any other: twenty of them. */
    @Test
    fun historyKeepsItsLastTwentySteps() = runTest(dispatcher) {
        val box = editor()
        box.type("x")
        repeat(30) { step -> box.command { it.copy(align = if (step % 2 == 0) Align.Center else Align.Start) } }
        undo(19)
        assertTrue(vm.canvasUndoState.value.canUndo)
        undo()
        assertFalse(vm.canvasUndoState.value.canUndo)
        advanceUntilIdle()
    }
}
