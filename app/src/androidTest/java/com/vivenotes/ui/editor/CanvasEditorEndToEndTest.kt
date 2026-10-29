package com.vivenotes.ui.editor

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.data.AttachmentStore
import com.vivenotes.data.CanvasClipboardSource
import com.vivenotes.data.ClipboardContent
import com.vivenotes.data.EditorDefaultsStore
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.PageLoad
import com.vivenotes.data.PenSettingsStore
import com.vivenotes.data.ViewSettingsStore
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.model.Block
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.PageStyle
import com.vivenotes.richtext.FormatCommand
import com.vivenotes.richtext.OutlineEditText
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.NotesViewModel
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The canvas over a real ViewModel: what the history and the clipboard do, seen in the editors on
 * screen rather than in the ViewModel's state alone.
 */
class CanvasEditorEndToEndTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: NotesDatabase
    private lateinit var vm: NotesViewModel
    private lateinit var density: Density
    private val source = Source()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        val repository = NotesRepository(db)
        runBlocking {
            repository.seedIfEmpty()
            val pageId = db.query("SELECT id FROM pages ORDER BY sortIndex LIMIT 1", emptyArray()).use {
                it.moveToFirst()
                it.getString(0)
            }
            check(repository.loadDoc(pageId) is PageLoad.Loaded)
            repository.saveDoc(pageId, PageDoc(outlines = listOf(
                Outline.Text(id = "box", x = 40f, y = PageStyle.TITLE_BAND_DP, blocks = listOf(Block.of("start"))),
            )))
        }
        compose.runOnUiThread {
            vm = NotesViewModel(repository, AttachmentStore(context, db), EditorDefaultsStore(context),
                ViewSettingsStore(context), PenSettingsStore(context), systemClipboard = source)
        }
        compose.waitUntil(10_000) { vm.uiState.value.outlines.any { it.id == "box" } }
        compose.setContent {
            density = LocalDensity.current
            val state by vm.uiState.collectAsState()
            val hasClipboard by vm.hasClipboard.collectAsState()
            val pending by vm.pendingPasteLayout.collectAsState()
            val pastes by vm.pendingPastes.collectAsState()
            ViveNotesTheme {
                EditorPane(
                    title = state.title,
                    createdAt = 0L,
                    defaults = com.vivenotes.data.EditorDefaults(),
                    style = state.pageStyle,
                    zoom = 1f,
                    onTitleChange = {},
                    outlines = state.outlines,
                    pageRevision = state.pageRevision,
                    pageId = state.selectedPageId,
                    initialBlocksFor = vm::initialBlocksFor,
                    commands = vm.commands,
                    onBlocksChanged = vm::onBlocksChanged,
                    onTextEdited = vm::onTextEdited,
                    textRestores = vm.textRestores,
                    onTextEditsTogether = vm::asOneTextStep,
                    onSelectionChanged = vm::onSelectionChanged,
                    onMarkArmed = vm::onMarkArmed,
                    onCreateOutline = vm::createOutline,
                    textArmed = false,
                    onMoveOutline = vm::moveOutline,
                    onResizeOutline = vm::resizeOutline,
                    onSetOutlineMinHeight = vm::setOutlineMinHeight,
                    onOutlineBlurred = vm::onOutlineBlurred,
                    tables = state.tables,
                    onCanvasMeasured = { _, _ -> },
                    showPrintMargins = false,
                    hasClipboard = hasClipboard,
                    onPaste = vm::pasteObjects,
                    pendingPasteLayout = pending,
                    onPasteMeasured = vm::completePasteLayout,
                    pastesLoadingAt = pastes.filter { it.pageId == state.selectedPageId }.map { it.at },
                )
            }
        }
        compose.waitUntil(5_000) { editors().any { it.text.toString() == "start" && it.width > 0 } }
    }

    @After
    fun tearDown() {
        compose.runOnUiThread { vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
        db.close()
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

    private fun box(): OutlineEditText = editors().single { it.text.toString().startsWith("start") }

    /** Typing and Bold, undone from the ViewModel, come back in the editor on screen with its caret. */
    @Test
    fun undoingTextPutsItBackInTheEditorWithTheCaretWhereItChanged() {
        compose.runOnIdle {
            val editor = box()
            editor.requestFocus()
            editor.setSelection(5)
            editor.onCreateInputConnection(EditorInfo())!!.commitText(" more", 1)
            assertEquals("start more", editor.text.toString())
            editor.setSelection(0, 5)
        }
        vm.send(FormatCommand.ToggleMark(Mark.Bold))
        compose.waitUntil(5_000) { Mark.Bold in box().marksIn(TextSelection(0, 5)) }

        compose.runOnIdle { box().clearFocus() }
        compose.runOnIdle { vm.undoCanvas() }
        compose.waitUntil(5_000) { Mark.Bold !in box().marksIn(TextSelection(0, 5)) }
        compose.runOnIdle {
            val editor = box()
            assertTrue("undoing text leaves its box being edited", editor.hasFocus())
            assertEquals(0, editor.selectionStart)
            assertEquals(5, editor.selectionEnd)
            assertEquals("start more", editor.text.toString())
            vm.undoCanvas()
        }
        compose.waitUntil(5_000) { box().text.toString() == "start" }
        compose.runOnIdle {
            assertEquals("the caret goes back to where the typing began", 5, box().selectionStart)
            assertEquals("start", vm.initialBlocksFor("box").single().text)
            vm.redoCanvas()
            vm.redoCanvas()
        }
        compose.waitUntil(5_000) { box().text.toString() == "start more" && Mark.Bold in box().marksIn(TextSelection(0, 5)) }
    }

    /** A double-tap on bare canvas offers Paste, and another app's page lands as its objects. */
    @Test
    fun theDoubleTapPasteMenuPastesAnotherAppsTableAndHeadingAsObjects() {
        source.content = ClipboardContent(
            text = "Title\nName\tValue\nAfter",
            html = "<h1>Title</h1><table><tr><th>Name</th><th>Value</th></tr><tr><td>x</td><td>1</td></tr></table><p>After</p>",
        )
        compose.runOnIdle { vm.refreshClipboard() }
        val count = vm.uiState.value.outlines.size
        val tap = with(density) { Offset(220.dp.toPx(), 420.dp.toPx()) }

        compose.onRoot().performTouchInput { doubleClick(tap) }
        compose.onNodeWithTag(PageTags.PASTE).performClick()
        compose.waitUntil(10_000) { vm.uiState.value.tables.isNotEmpty() }

        compose.runOnIdle {
            val boxes = vm.uiState.value.outlines.drop(count)
            assertEquals("the heading and the paragraph after the table are boxes of their own", 2, boxes.size)
            assertEquals(BlockType.Heading1, vm.initialBlocksFor(boxes[0].id).single().type)
            assertEquals("After", vm.initialBlocksFor(boxes[1].id).single().text)
            val table = vm.uiState.value.tables.single()
            assertTrue(table.headerRow)
            assertEquals("1", vm.initialBlocksFor(table.cellAt(1, 1)!!.id).single().text)
            assertTrue("stacked in the order copied", boxes[0].y < table.y && table.y < boxes[1].y)
            assertNull("measured by the canvas, so nothing waits any more", vm.pendingPasteLayout.value)
            vm.undoCanvas()
        }
        compose.waitUntil(5_000) { vm.uiState.value.tables.isEmpty() }
        assertEquals("one Undo takes the whole paste back", count, vm.uiState.value.outlines.size)
    }

    /** While a picture downloads, the paste shows where it will land, and stops when it has. */
    @Test
    fun aPasteStillDownloadingShowsItsProgressWhereItWillLand() {
        val gate = CompletableDeferred<Unit>()
        source.ready = gate
        source.content = ClipboardContent(html = "<img src='https://example.org/slow.png'>")
        compose.runOnIdle { vm.refreshClipboard() }
        val tap = with(density) { Offset(220.dp.toPx(), 420.dp.toPx()) }

        compose.onRoot().performTouchInput { doubleClick(tap) }
        compose.onNodeWithTag(PageTags.PASTE).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(PageTags.PASTE_PROGRESS).fetchSemanticsNodes().isNotEmpty()
        }
        val shown = compose.onNodeWithTag(PageTags.PASTE_PROGRESS).fetchSemanticsNode().boundsInRoot
        val landing = compose.runOnIdle { vm.pendingPastes.value.single().at }
        assertEquals("at the point the paste was asked for", 220f, landing.x, 2f)
        assertEquals("drawn from where the paste lands", tap.x, shown.left, 2 * density.density)
        assertEquals(tap.y, shown.top, 2 * density.density)

        gate.complete(Unit)
        compose.waitUntil(5_000) { vm.pendingPastes.value.isEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(PageTags.PASTE_PROGRESS).fetchSemanticsNodes().isEmpty() }
        // Nothing came back for the picture, so its address is pasted instead.
        compose.runOnIdle {
            assertEquals("https://example.org/slow.png", vm.initialBlocksFor(vm.uiState.value.outlines.last().id).single().text)
        }
    }

    private class Source : CanvasClipboardSource {
        var content = ClipboardContent()
        var ready: CompletableDeferred<Unit>? = null
        override fun hasContent() = content.text != null || content.html != null
        override suspend fun readContent() = content
        override suspend fun imageBytes(address: String): ByteArray? {
            ready?.await()
            return null
        }
        override fun markInternalCopy() {}
        override fun ownsInternalCopy() = false
    }
}
