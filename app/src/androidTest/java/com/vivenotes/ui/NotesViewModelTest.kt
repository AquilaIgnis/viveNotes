package com.vivenotes.ui

import androidx.ink.brush.InputToolType
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.vivenotes.data.AttachmentStore
import com.vivenotes.data.DrawTool
import com.vivenotes.data.DeletedItemKind
import com.vivenotes.data.EditorDefaults
import com.vivenotes.data.EditorDefaultsStore
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.PageLoad
import com.vivenotes.data.PageRevisionLoad
import com.vivenotes.data.PenSettingsStore
import com.vivenotes.data.ShapeSettings
import com.vivenotes.data.TableSettings
import com.vivenotes.data.ViewSettings
import com.vivenotes.data.ViewSettingsStore
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.PageContentEntity
import com.vivenotes.ink.InkCodec
import com.vivenotes.ink.InkBounds
import com.vivenotes.ink.CanvasSelection
import com.vivenotes.ink.InkLassoMove
import com.vivenotes.ink.InkLassoResize
import com.vivenotes.ink.InkPoint
import com.vivenotes.ink.projectionKey
import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.model.Orientation
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.PageSpace
import com.vivenotes.model.PageStyle
import com.vivenotes.model.PaperSize
import com.vivenotes.model.SpaceCut
import com.vivenotes.model.RuleLines
import com.vivenotes.model.plainText
import com.vivenotes.richtext.FormatCommand

/**
 * The ViewModel owns the load → edit → save cycle, and a bug here destroyed a page's contents
 * during development: an unreadable document was replaced by a blank one, which autosave then
 * wrote back. These tests guard that cycle rather than any single function.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class NotesViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)

    private lateinit var db: NotesDatabase
    private lateinit var repository: NotesRepository
    private lateinit var attachments: AttachmentStore
    private lateinit var editorDefaults: EditorDefaultsStore
    private lateinit var viewSettings: ViewSettingsStore
    private lateinit var penSettings: PenSettingsStore

    /** The device's own editor defaults, restored after the tests that overwrite them. */
    private var savedDefaults: EditorDefaults? = null

    /** Same borrowed file, same obligation: the zoom tests put the user's own zoom back. */
    private var savedZoom: Float? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        repository = NotesRepository(db)
        // Rows live in the in-memory database above; the bytes would land in the installed app's
        // files directory, and no test here inserts a picture.
        attachments = AttachmentStore(context, db)
        // Backed by a real file, unlike the in-memory database — the preferences delegate memoises
        // per Context, so every test in this class shares one store rather than clashing over it.
        editorDefaults = EditorDefaultsStore(context)
        viewSettings = ViewSettingsStore(context)
        penSettings = PenSettingsStore(context)
        // That file belongs to the installed app, not to the test, so whatever the defaults tests
        // write to it has to be put back — otherwise running the suite silently changes the font
        // the user's own build types in.
        savedDefaults = runBlocking { editorDefaults.defaults.first() }
        savedZoom = runBlocking { viewSettings.settings.first().zoom }
    }

    @After
    fun tearDown() {
        savedDefaults?.let { saved ->
            runBlocking {
                editorDefaults.setFontSize(saved.fontSize)
                editorDefaults.setFontFamily(saved.fontFamily)
            }
        }
        savedZoom?.let { runBlocking { viewSettings.setZoom(it) } }
        db.close()
        Dispatchers.resetMain()
    }

    /** Builds a settled view model sitting on the seeded Welcome page. */
    private suspend fun seededViewModel(): NotesViewModel {
        val vm = NotesViewModel(
            repository,
            attachments,
            editorDefaults,
            viewSettings,
            penSettings,
            inkDispatcher = dispatcher,
        )
        scheduler.advanceUntilIdle()
        assertNotNull("expected the seeded page to be open", vm.uiState.value.selectedPageId)
        return vm
    }

    /** A selection naming objects by id. Bounds do not matter to the clipboard, only membership. */
    private fun selectionOf(
        inkIds: Set<String> = emptySet(),
        shapeIds: Set<String> = emptySet(),
    ) = CanvasSelection(
        inkIds = inkIds,
        shapeIds = shapeIds,
        bounds = InkBounds(0f, 0f, 0f, 0f),
    )

    private fun storedText(pageId: String): String = runBlocking {
        when (val load = repository.loadDoc(pageId)) {
            is PageLoad.Loaded -> load.doc.plainText()
            is PageLoad.Unreadable -> error("page $pageId became unreadable")
        }
    }

    private fun storedJson(pageId: String): String = runBlocking {
        db.pageContentDao().byId(pageId)!!.docJson
    }

    // --- the data-loss regression --------------------------------------------------------------

    /**
     * Regression: a page whose body cannot be decoded is shown blank, and autosave used to write
     * that blank page straight over the original.
     */
    @Test
    fun anUnreadablePageIsNeverOverwritten() = runTest(dispatcher) {
        val vm = seededViewModel()
        val sectionId = vm.uiState.value.selectedSectionId!!

        // Corrupt a page the view model is not currently sitting on. Corrupting the open page
        // instead would prove nothing: opening a page flushes the previous one first, so the
        // flush would rewrite valid content over the garbage before it was ever read.
        val pageId = repository.createPage(sectionId, "corrupt")
        val garbage = """{"outlines":[{"t":"text","id":"x","blocks":"not-an-array"}]}"""
        db.pageContentDao().upsert(PageContentEntity(pageId, garbage, 1L))

        vm.openPage(pageId)
        advanceUntilIdle()

        // Everything the editor would do on a page it just opened.
        vm.onBlocksChanged(vm.uiState.value.outlines.first().id, listOf(Block.of("typed over it")))
        vm.moveOutline(vm.uiState.value.outlines.first().id, 10f, 10f)
        advanceUntilIdle()

        assertEquals("the unreadable document was overwritten", garbage, storedJson(pageId))
        assertNotNull("the user was not told the page is unreadable", vm.uiState.value.contentError)
    }

    /**
     * Regression: the seeded page was blanked by the first thing that saved it.
     *
     * Seeding creates a page's row and writes its content a moment later. The tree observer used
     * to start alongside the seed, so it could open the page in that gap, load the empty document
     * the row was created with, and hold it — and the first save wrote that emptiness over the
     * seed. Found on a clean install by changing the page colour, which is a save that touches no
     * text at all.
     */
    @Test
    fun theSeededPageIsNotBlankedByASaveThatTouchesNoText() = runTest(dispatcher) {
        val vm = seededViewModel()
        val pageId = vm.uiState.value.selectedPageId!!
        assertTrue(
            "the view model opened the seeded page before its content was written",
            vm.initialBlocksFor(vm.uiState.value.outlines.first().id).any { it.text.isNotBlank() },
        )

        vm.setRuleLines(RuleLines.Wide)
        advanceUntilIdle()

        assertTrue("the seeded page was blanked", storedText(pageId).contains("This is a page"))
    }

    // --- the load/edit/save cycle --------------------------------------------------------------

    @Test
    fun editsAreAutosaved() = runTest(dispatcher) {
        val vm = seededViewModel()
        val pageId = vm.uiState.value.selectedPageId!!

        vm.onBlocksChanged(vm.uiState.value.outlines.first().id, listOf(Block.of("autosaved text")))
        advanceUntilIdle()

        assertTrue(storedText(pageId).contains("autosaved text"))
    }

    @Test
    fun switchingPagesFlushesPendingEditsFirst() = runTest(dispatcher) {
        val vm = seededViewModel()
        val firstPage = vm.uiState.value.selectedPageId!!
        val sectionId = vm.uiState.value.selectedSectionId!!

        vm.onBlocksChanged(vm.uiState.value.outlines.first().id, listOf(Block.of("must not be lost")))
        // Deliberately no idle here: the edit is still inside the autosave debounce window.
        val secondPage = repository.createPage(sectionId, "other")
        vm.openPage(secondPage)
        advanceUntilIdle()

        assertTrue(
            "the last edit was lost when switching pages",
            storedText(firstPage).contains("must not be lost"),
        )
    }

    @Test
    fun restoringARevisionKeepsUnsavedEditorStateAsTheSafetyVersion() = runTest(dispatcher) {
        val vm = seededViewModel()
        val pageId = vm.uiState.value.selectedPageId!!

        // First-run seeding replaces the page's initial empty body, which gives this test an older
        // checkpoint to restore without manufacturing repository state behind the ViewModel.
        vm.loadVersionHistory()
        advanceUntilIdle()
        assertNotNull(vm.versionHistory.value.preview)

        val outlineId = vm.uiState.value.outlines.first().id
        vm.onBlocksChanged(outlineId, listOf(Block.of("still inside the autosave window")))
        // Do not advance time: restore itself must flush this live editor state before checkpointing.
        vm.restoreSelectedVersion()
        advanceUntilIdle()

        assertTrue(vm.initialBlocksFor(vm.uiState.value.outlines.first().id).all { it.text.isBlank() })
        assertTrue(vm.versionHistory.value.message?.contains("still in history") == true)

        val safetyCopyExists = repository.revisionHistory(pageId).any { revision ->
            val load = repository.loadRevision(pageId, revision.id)
            load is PageRevisionLoad.Loaded &&
                load.doc.plainText().contains("still inside the autosave window")
        }
        assertTrue("the live editor state was not checkpointed before restore", safetyCopyExists)
    }

    @Test
    fun restoringBackAndForwardRefreshesTheVisibleInkCanvas() = runTest(dispatcher) {
        val vm = seededViewModel()
        val pageId = vm.uiState.value.selectedPageId!!
        vm.onStrokeFinished(inkStroke(10f to 20f, 90f to 20f))
        advanceUntilIdle()
        assertEquals(1, vm.strokes.value.size)

        // The seeded page's first checkpoint is its blank, ink-free state. Restoring it must first
        // protect the currently visible Welcome page and its stroke as the forward version.
        vm.loadVersionHistory()
        advanceUntilIdle()
        vm.restoreSelectedVersion()
        advanceUntilIdle()
        assertTrue(vm.strokes.value.isEmpty())

        vm.loadVersionHistory()
        advanceUntilIdle()
        val forward = vm.versionHistory.value.revisions.first { revision ->
            val loaded = repository.loadRevision(pageId, revision.id)
            loaded is PageRevisionLoad.Loaded && loaded.doc.plainText().contains("This is a page")
        }
        vm.selectVersionRevision(forward.id)
        advanceUntilIdle()
        vm.restoreSelectedVersion()
        advanceUntilIdle()

        assertEquals(1, vm.strokes.value.size)
        assertTrue(storedText(pageId).contains("This is a page"))
    }

    @Test
    fun reopeningAPageRestoresItsContentAndGeometry() = runTest(dispatcher) {
        val vm = seededViewModel()
        val pageId = vm.uiState.value.selectedPageId!!
        val outlineId = vm.uiState.value.outlines.first().id

        vm.onBlocksChanged(outlineId, listOf(Block.of("persisted body")))
        vm.moveOutline(outlineId, 120f, 250f)
        vm.resizeOutline(outlineId, 480f)
        vm.setOutlineMinHeight(outlineId, 300f)
        advanceUntilIdle()

        vm.openPage(pageId)
        advanceUntilIdle()

        val box = vm.uiState.value.outlines.single()
        assertEquals(120f, box.x, 0.01f)
        assertEquals(250f, box.y, 0.01f)
        assertEquals(480f, box.width, 0.01f)
        assertEquals(300f, box.minHeight, 0.01f)
        assertTrue(storedText(pageId).contains("persisted body"))
    }

    @Test
    fun reopeningReplaysComponentEraseWithoutTouchingInkDrawnLater() = runTest(dispatcher) {
        val vm = seededViewModel()
        val pageId = vm.uiState.value.selectedPageId!!
        vm.selectTool(DrawTool.Pen(0))

        vm.onStrokeFinished(inkStroke(10f to 50f, 90f to 50f))
        advanceUntilIdle()
        val oldId = vm.strokes.value.single().id

        vm.eraseStrokeParts(inkStroke(50f to 35f, 50f to 65f, sizeDp = 18f))
        advanceUntilIdle()
        assertFalse(vm.strokes.value.any { it.stroke.overlaps(centerBox()) })

        vm.eraseStrokeObjects(inkStroke(20f to 45f, 20f to 55f, sizeDp = 12f))
        advanceUntilIdle()
        assertFalse(vm.strokes.value.single().stroke.overlaps(leftBox()))
        assertTrue(vm.strokes.value.single().stroke.overlaps(rightBox()))

        // This crosses the same place after both erases and must not become a target during replay.
        vm.onStrokeFinished(inkStroke(50f to 10f, 50f to 90f))
        advanceUntilIdle()
        val newId = vm.strokes.value.first { it.id != oldId }.id

        vm.openPage(pageId)
        advanceUntilIdle()

        assertFalse(vm.strokes.value.first { it.id == oldId }.stroke.overlaps(centerBox()))
        assertFalse(vm.strokes.value.first { it.id == oldId }.stroke.overlaps(leftBox()))
        assertTrue(vm.strokes.value.first { it.id == oldId }.stroke.overlaps(rightBox()))
        assertTrue(vm.strokes.value.first { it.id == newId }.stroke.overlaps(centerBox()))
    }

    @Test
    fun reopeningReplaysLassoMoveBeforeAnEraseAtItsNewPosition() = runTest(dispatcher) {
        val vm = seededViewModel()
        val pageId = vm.uiState.value.selectedPageId!!
        vm.selectTool(DrawTool.Pen(0))
        vm.onStrokeFinished(inkStroke(10f to 50f, 90f to 50f))
        advanceUntilIdle()

        val original = vm.strokes.value.single()
        val path = listOf(
            InkPoint(0f, 30f),
            InkPoint(100f, 30f),
            InkPoint(100f, 70f),
            InkPoint(0f, 70f),
        )
        vm.moveInk(
            InkLassoMove(
                path = path,
                targetIds = setOf(original.id),
                projections = setOf(original.projectionKey),
                dx = 100f,
                dy = 0f,
            ),
        )
        advanceUntilIdle()
        vm.eraseStrokeParts(inkStroke(150f to 35f, 150f to 65f, sizeDp = 18f))
        advanceUntilIdle()

        assertTrue(vm.strokes.value.all { it.offsetX == 100f })
        assertFalse(vm.strokes.value.any { it.stroke.overlaps(centerBox()) })

        vm.openPage(pageId)
        advanceUntilIdle()

        assertTrue(vm.strokes.value.all { it.offsetX == 100f })
        assertFalse(vm.strokes.value.any { it.stroke.overlaps(centerBox()) })
    }

    // --- Draw-toolbar history -----------------------------------------------------------------

    /**
     * Which tool laid a stroke down decides how it is stored, so a highlighter stroke has to reach
     * the page as a highlighter — not as whatever pen was last in hand, which is what the old
     * `activePen() ?: return` would have made of it.
     */
    @Test
    fun aStrokeDrawnWithTheHighlighterIsStoredAsOne() = runTest(dispatcher) {
        val vm = seededViewModel()
        val pageId = vm.uiState.value.selectedPageId!!

        vm.selectTool(DrawTool.Pen(0))
        vm.onStrokeFinished(inkStroke(10f to 20f, 90f to 20f))
        advanceUntilIdle()

        vm.selectTool(DrawTool.Highlighter)
        vm.onStrokeFinished(inkStroke(10f to 50f, 90f to 50f))
        advanceUntilIdle()

        val families = vm.strokes.value.map { it.brushFamily }
        assertEquals(2, families.size)
        assertTrue("the highlighter stroke is missing: $families", "highlighter" in families)
        assertTrue("the pen stroke was restyled: $families", families.any { it != "highlighter" })

        // Reopening is the only proof the row was written rather than merely held in memory.
        vm.openPage(pageId)
        advanceUntilIdle()
        assertEquals(1, vm.strokes.value.count { it.brushFamily == "highlighter" })
    }

    /** A tool that is neither a pen nor the highlighter must not invent a stroke of its own. */
    @Test
    fun anEraserGestureIsNotRecordedAsInk() = runTest(dispatcher) {
        val vm = seededViewModel()

        vm.selectTool(DrawTool.Eraser)
        vm.onStrokeFinished(inkStroke(10f to 50f, 90f to 50f))
        advanceUntilIdle()

        assertTrue(vm.strokes.value.isEmpty())
    }

    @Test
    fun selectingAnyCanvasToolDeactivatesTextInput() = runTest(dispatcher) {
        val vm = seededViewModel()

        listOf(
            DrawTool.Pen(1),
            DrawTool.Highlighter,
            DrawTool.Eraser,
            DrawTool.Lasso,
        ).forEach { tool ->
            val nextCommand = async { vm.commands.first() }
            runCurrent()

            vm.selectTool(tool)

            assertEquals(FormatCommand.DeactivateTextInput, nextCommand.await())
        }
    }

    /**
     * Prime Object's fourth rule — `docs/diagram.md`: *"Selecting any other tool removes selection
     * of object."* The pane holds the selection, so what the ViewModel owes it is the command, on a
     * real change and not otherwise.
     */
    @Test
    fun pickingAnotherToolDropsTheObjectSelectionButRearmingTheSameOneDoesNot() =
        runTest(dispatcher) {
            val vm = seededViewModel()
            val seen = mutableListOf<FormatCommand>()
            val collector = launch { vm.commands.toList(seen) }
            runCurrent()

            vm.selectTool(DrawTool.Lasso)
            runCurrent()
            assertTrue(
                "picking another tool left the object selected",
                FormatCommand.ClearCanvasSelection in seen,
            )

            // Re-tapping the tool already in hand has not selected another one — and the ribbon does
            // exactly this, since tapping the armed tool is how its settings pane is opened.
            seen.clear()
            vm.selectTool(DrawTool.Lasso)
            runCurrent()
            assertFalse(
                "re-arming the tool in hand dropped the selection",
                FormatCommand.ClearCanvasSelection in seen,
            )

            collector.cancel()
        }

    /**
     * The other side of that rule, and the reason it is a command rather than an effect on [tool].
     *
     * Every insert path puts its own tool down — `insertShape`, `insertTable` and `insertEquation`
     * all set [DrawTool.None] — and `EditorPane` then selects what the call returned. A clear keyed
     * on the *armed tool* would fire on that disarm and wipe the selection the insert had just
     * handed over, so every placed object would arrive with no handles on it.
     */
    @Test
    fun placingAnObjectPutsTheToolDownWithoutDroppingWhatItJustMade() = runTest(dispatcher) {
        val vm = seededViewModel()
        vm.selectTool(DrawTool.Shape)
        runCurrent()

        val seen = mutableListOf<FormatCommand>()
        val collector = launch { vm.commands.toList(seen) }
        runCurrent()

        val created = vm.insertShape(ShapeSettings(), 20f, 20f, 120f, 120f)
        advanceUntilIdle()

        assertNotNull("no shape was placed", created)
        assertEquals("placing a shape did not put the tool down", DrawTool.None, vm.tool.value)
        assertFalse(
            "placing a shape emitted the clear that would deselect it on arrival",
            FormatCommand.ClearCanvasSelection in seen,
        )

        collector.cancel()
    }

    @Test
    fun drawingCanBeUndoneRedoneAndReloaded() = runTest(dispatcher) {
        val vm = seededViewModel()
        val pageId = vm.uiState.value.selectedPageId!!
        vm.selectTool(DrawTool.Pen(0))

        vm.onStrokeFinished(inkStroke(10f to 50f, 90f to 50f))
        advanceUntilIdle()
        assertEquals(1, vm.strokes.value.size)
        assertEquals(CanvasUndoState(canUndo = true), vm.canvasUndoState.value)

        vm.undoCanvas()
        assertTrue(vm.strokes.value.isEmpty())
        assertEquals(CanvasUndoState(canRedo = true), vm.canvasUndoState.value)
        advanceUntilIdle()
        vm.openPage(pageId)
        advanceUntilIdle()
        assertTrue("undoing a draw was not persisted", vm.strokes.value.isEmpty())

        vm.redoCanvas()
        assertEquals(1, vm.strokes.value.size)
        assertEquals(CanvasUndoState(canUndo = true), vm.canvasUndoState.value)
        advanceUntilIdle()
        vm.openPage(pageId)
        advanceUntilIdle()
        assertEquals("redoing a draw was not persisted", 1, vm.strokes.value.size)
    }

    @Test
    fun anEraseIsOneUndoableOperationAndItsUndoneStateReloads() = runTest(dispatcher) {
        val vm = seededViewModel()
        val pageId = vm.uiState.value.selectedPageId!!
        vm.selectTool(DrawTool.Pen(0))
        vm.onStrokeFinished(inkStroke(10f to 50f, 90f to 50f))
        advanceUntilIdle()

        vm.eraseStrokeParts(inkStroke(50f to 35f, 50f to 65f, sizeDp = 18f))
        assertEquals("history stayed active while erase geometry was unresolved", CanvasUndoState(), vm.canvasUndoState.value)
        advanceUntilIdle()
        assertFalse(vm.strokes.value.any { it.stroke.overlaps(centerBox()) })
        assertEquals(CanvasUndoState(canUndo = true), vm.canvasUndoState.value)

        vm.undoCanvas()
        assertTrue(vm.strokes.value.single().stroke.overlaps(centerBox()))
        advanceUntilIdle()
        vm.openPage(pageId)
        advanceUntilIdle()
        assertTrue("the undone erase still replayed after reload", vm.strokes.value.single().stroke.overlaps(centerBox()))

        vm.redoCanvas()
        assertFalse(vm.strokes.value.any { it.stroke.overlaps(centerBox()) })
        advanceUntilIdle()
    }

    @Test
    fun aLassoTranslationCanBeUndoneAndRedone() = runTest(dispatcher) {
        val vm = seededViewModel()
        val pageId = vm.uiState.value.selectedPageId!!
        vm.selectTool(DrawTool.Pen(0))
        vm.onStrokeFinished(inkStroke(10f to 50f, 90f to 50f))
        advanceUntilIdle()
        val original = vm.strokes.value.single()
        val move = InkLassoMove(
            path = listOf(
                InkPoint(0f, 30f),
                InkPoint(100f, 30f),
                InkPoint(100f, 70f),
                InkPoint(0f, 70f),
            ),
            targetIds = setOf(original.id),
            projections = setOf(original.projectionKey),
            dx = 100f,
            dy = 25f,
        )

        vm.moveInk(move)
        assertEquals(100f, vm.strokes.value.single().offsetX, 0.01f)
        assertEquals(25f, vm.strokes.value.single().offsetY, 0.01f)
        advanceUntilIdle()

        vm.undoCanvas()
        assertEquals(0f, vm.strokes.value.single().offsetX, 0.01f)
        assertEquals(0f, vm.strokes.value.single().offsetY, 0.01f)
        advanceUntilIdle()
        vm.openPage(pageId)
        advanceUntilIdle()
        assertEquals("the undone move still replayed after reload", 0f, vm.strokes.value.single().offsetX, 0.01f)

        vm.redoCanvas()
        assertEquals(100f, vm.strokes.value.single().offsetX, 0.01f)
        assertEquals(25f, vm.strokes.value.single().offsetY, 0.01f)
        advanceUntilIdle()
        vm.openPage(pageId)
        advanceUntilIdle()
        assertEquals("the redone move was not replayed after reload", 100f, vm.strokes.value.single().offsetX, 0.01f)
    }

    @Test
    fun selectedInkCanBeRecoloredGroupedAndReloaded() = runTest(dispatcher) {
        val vm = seededViewModel()
        val pageId = vm.uiState.value.selectedPageId!!
        vm.selectTool(DrawTool.Pen(0))
        vm.onStrokeFinished(inkStroke(10f to 10f, 20f to 20f))
        vm.onStrokeFinished(inkStroke(40f to 40f, 50f to 50f))
        advanceUntilIdle()
        val ids = vm.strokes.value.map { it.id }.toSet()
        val blue = 0xFF3B82F6.toInt()

        vm.groupInk(ids)
        vm.recolorInk(ids, blue)
        assertEquals(1, vm.strokes.value.mapNotNull { it.groupId }.distinct().size)
        assertTrue(vm.strokes.value.all { it.stroke.brush.colorIntArgb == blue })
        advanceUntilIdle()
        vm.openPage(pageId)
        advanceUntilIdle()

        assertEquals("group membership was not persisted", 1, vm.strokes.value.mapNotNull { it.groupId }.distinct().size)
        assertTrue("the selected colour was not persisted", vm.strokes.value.all { it.stroke.brush.colorIntArgb == blue })
    }

    @Test
    fun shapeEditsUndoAndRedoOneActionAtATime() = runTest(dispatcher) {
        // Insert, adjust, delete — each its own step, and each reversible. A shape lives in the
        // document rather than in `ink_strokes`, but where it is stored has nothing to do with what
        // the Undo button reverses.
        val vm = seededViewModel()
        val pageId = vm.uiState.value.selectedPageId!!
        val shapeId = vm.insertShape(ShapeSettings(), 40f, 40f, 160f, 120f)!!
        advanceUntilIdle()
        assertEquals(CanvasUndoState(canUndo = true), vm.canvasUndoState.value)

        vm.moveShape(shapeId, 60f, 20f)
        vm.deleteShapes(setOf(shapeId))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.shapes.isEmpty())

        vm.undoCanvas()
        assertEquals("undo did not bring the shape back", 1, vm.uiState.value.shapes.size)
        assertEquals("undo went back further than the delete", 100f, vm.uiState.value.shapes.single().x, 0.01f)

        vm.undoCanvas()
        assertEquals("undo did not take back the move", 40f, vm.uiState.value.shapes.single().x, 0.01f)

        vm.undoCanvas()
        assertTrue("undo did not take back the insert", vm.uiState.value.shapes.isEmpty())
        assertEquals(CanvasUndoState(canRedo = true), vm.canvasUndoState.value)
        advanceUntilIdle()
        vm.openPage(pageId)
        advanceUntilIdle()
        assertTrue("the undone insert came back after a reload", vm.uiState.value.shapes.isEmpty())

        vm.redoCanvas()
        vm.redoCanvas()
        assertEquals("redo did not replay the move", 100f, vm.uiState.value.shapes.single().x, 0.01f)
        advanceUntilIdle()
        vm.openPage(pageId)
        advanceUntilIdle()
        assertEquals("the redone edits were not persisted", 100f, vm.uiState.value.shapes.single().x, 0.01f)
    }

    @Test
    fun undoStepsBackThroughInkAndShapesInTheOrderTheyHappened() = runTest(dispatcher) {
        // One ring across kinds. Two rings could only guess which of them a press belonged to, and
        // would have got it wrong every time the two were interleaved — which is the normal case.
        val vm = seededViewModel()
        vm.selectTool(DrawTool.Pen(0))
        vm.onStrokeFinished(inkStroke(10f to 50f, 90f to 50f))
        advanceUntilIdle()
        vm.insertShape(ShapeSettings(), 40f, 40f, 160f, 120f)
        advanceUntilIdle()

        vm.undoCanvas()
        assertTrue("Undo took the stroke instead of the shape drawn after it", vm.uiState.value.shapes.isEmpty())
        assertEquals("Undo took the stroke as well", 1, vm.strokes.value.size)

        vm.undoCanvas()
        assertTrue("the second Undo did not reach the stroke", vm.strokes.value.isEmpty())
        assertEquals(CanvasUndoState(canRedo = true), vm.canvasUndoState.value)
        advanceUntilIdle()

        vm.redoCanvas()
        assertEquals("redo did not replay the stroke first", 1, vm.strokes.value.size)
        assertTrue("redo replayed the shape out of order", vm.uiState.value.shapes.isEmpty())
        advanceUntilIdle()
    }

    @Test
    fun aRunOfBorderWidthStepsIsOneUndo() = runTest(dispatcher) {
        // The slider reports every step it passes through. Thirty entries for one drag is not a
        // history, so a run of them folds into the entry it started.
        val vm = seededViewModel()
        val shapeId = vm.insertShape(ShapeSettings(), 40f, 40f, 160f, 120f)!!
        advanceUntilIdle()
        val original = vm.uiState.value.shapes.single().borderWidth

        // Against the declared range rather than literals, so tightening it stays a one-line change.
        val top = minOf(ShapeSettings.MIN_BORDER_WIDTH + 6, ShapeSettings.MAX_BORDER_WIDTH)
        ((ShapeSettings.MIN_BORDER_WIDTH + 1)..top)
            .forEach { vm.setShapeBorderWidth(setOf(shapeId), it.toFloat()) }
        advanceUntilIdle()
        assertEquals(top.toFloat(), vm.uiState.value.shapes.single().borderWidth, 0.01f)

        vm.undoCanvas()

        assertEquals("the run did not fold into one step", original, vm.uiState.value.shapes.single().borderWidth, 0.01f)
        assertEquals("folding the run also swallowed the insert", 1, vm.uiState.value.shapes.size)
    }

    @Test
    fun aTextBoxCopiesPastesDeletesAndUndoesWithItsText() = runTest(dispatcher) {
        // `docs/textBoxPlan.md` TD5. A container is two halves in two places — the box in `uiState`
        // and its blocks in a private map — so the thing worth asserting is the *text*, not the
        // rectangle: every way of getting this wrong produces a box of the right size and no words.
        val vm = seededViewModel()
        val pageId = vm.uiState.value.selectedPageId!!
        val id = vm.createOutline(120f, 140f)
        vm.onBlocksChanged(id, listOf(Block.of("carry me")))
        advanceUntilIdle()

        vm.copyOutline(id)
        assertTrue(vm.hasClipboard.value)
        assertEquals("copy is not duplicate: the page must not change", 2, vm.uiState.value.outlines.size)

        vm.pasteObjects(InkPoint(400f, 500f))
        advanceUntilIdle()
        assertEquals(3, vm.uiState.value.outlines.size)
        assertEquals(
            "the pasted box did not bring its text",
            2,
            storedText(pageId).split("carry me").size - 1,
        )

        vm.deleteOutlines(setOf(id))
        advanceUntilIdle()
        assertTrue("delete left the container behind", vm.uiState.value.outlines.none { it.id == id })

        vm.undoCanvas()
        assertTrue("undo did not bring the container back", vm.uiState.value.outlines.any { it.id == id })
        advanceUntilIdle()
        assertTrue(
            "the restored container came back blank",
            storedText(pageId).contains("carry me"),
        )

        vm.undoCanvas()
        assertEquals("undo did not take back the paste", 2, vm.uiState.value.outlines.size)
    }

    @Test
    fun deletingTheLastTextBoxIsAllowed() = runTest(dispatcher) {
        // The "last container always survives" rule guards against *stray taps* leaving empty boxes.
        // An explicit delete is not a stray tap, and a page with no container is not broken.
        val vm = seededViewModel()
        val ids = vm.uiState.value.outlines.map { it.id }.toSet()

        vm.deleteOutlines(ids)

        assertTrue(vm.uiState.value.outlines.isEmpty())
    }

    @Test
    fun oneClipboardHoldsAShapeAndAStrokeTogether() = runTest(dispatcher) {
        // The diagram's "shared prime object clipboard": one clipboard for every kind, not one each.
        val vm = seededViewModel()
        vm.selectTool(DrawTool.Pen(0))
        vm.onStrokeFinished(inkStroke(10f to 10f, 20f to 20f))
        advanceUntilIdle()
        val strokeId = vm.strokes.value.single().id
        val shapeId = vm.insertShape(ShapeSettings(), 40f, 40f, 80f, 80f)!!
        advanceUntilIdle()

        vm.copySelection(selectionOf(inkIds = setOf(strokeId), shapeIds = setOf(shapeId)))
        assertTrue(vm.hasClipboard.value)
        assertEquals("copy is not duplicate: the page must not change", 1, vm.uiState.value.shapes.size)
        assertEquals(1, vm.strokes.value.size)

        vm.pasteObjects(InkPoint(300f, 300f))
        advanceUntilIdle()

        assertEquals("the shape half of the clipboard did not paste", 2, vm.uiState.value.shapes.size)
        assertEquals("the ink half of the clipboard did not paste", 2, vm.strokes.value.size)
        val pasted = vm.uiState.value.shapes.first { it.id != shapeId }
        assertTrue("the pasted shape kept the original's segment ids",
            pasted.segments.map { it.id }.none { id -> id in vm.uiState.value.shapes.first { s -> s.id == shapeId }.segments.map { it.id } })
    }

    /**
     * The paste point is the *top* of what arrives, not its middle.
     *
     * Centring it vertically put half the pasted content above the tap, so a paste aimed just below
     * some existing ink overlapped it, and one near the top of the page landed with its head off the
     * sheet. Horizontally it stays centred, which is what makes the tap feel aimed rather than
     * hung from a corner.
     */
    @Test
    fun pasteHangsTheContentFromTheTapRatherThanCentringItOnIt() = runTest(dispatcher) {
        val vm = seededViewModel()
        val shapeId = vm.insertShape(ShapeSettings(), 40f, 100f, 140f, 300f)!!
        advanceUntilIdle()
        val source = vm.uiState.value.shapes.single { it.id == shapeId }

        vm.copySelection(selectionOf(shapeIds = setOf(shapeId)))
        vm.pasteObjects(InkPoint(400f, 500f))
        advanceUntilIdle()

        val pasted = vm.uiState.value.shapes.single { it.id != shapeId }
        assertEquals("the paste did not start at the tap", 500f, pasted.y, 0.5f)
        assertEquals(
            "the paste was not centred horizontally on the tap",
            400f,
            pasted.x + pasted.width / 2f,
            0.5f,
        )
        assertEquals("the paste changed size", source.width, pasted.width, 0.5f)
        assertEquals("the paste changed size", source.height, pasted.height, 0.5f)
    }

    @Test
    fun copyingAShapeLeavesThePageAloneUntilItIsPasted() = runTest(dispatcher) {
        // It used to drop a duplicate 16dp down and right the moment Copy was pressed.
        val vm = seededViewModel()
        val shapeId = vm.insertShape(ShapeSettings(), 40f, 40f, 80f, 80f)!!
        advanceUntilIdle()

        vm.copySelection(selectionOf(shapeIds = setOf(shapeId)))

        assertEquals("Copy duplicated the shape instead of filling the clipboard", 1, vm.uiState.value.shapes.size)
        assertTrue(vm.hasClipboard.value)
    }

    @Test
    fun copyingInkChangesNothingUntilPasteThenCreatesAnUndoableObjectAtTheTap() = runTest(dispatcher) {
        val vm = seededViewModel()
        val pageId = vm.uiState.value.selectedPageId!!
        vm.selectTool(DrawTool.Pen(0))
        vm.onStrokeFinished(inkStroke(10f to 10f, 20f to 20f))
        advanceUntilIdle()
        val originalId = vm.strokes.value.single().id

        vm.copySelection(selectionOf(inkIds = setOf(originalId)))
        assertEquals("Copy must not clone ink onto the page", 1, vm.strokes.value.size)
        assertTrue(vm.hasClipboard.value)

        vm.pasteObjects(InkPoint(100f, 120f))
        assertEquals(2, vm.strokes.value.size)
        val pastedBounds = vm.strokes.value.first { it.id != originalId }.stroke.shape.computeBoundingBox()!!
        // Centred across the tap, hung from it vertically — see
        // [pasteHangsTheContentFromTheTapRatherThanCentringItOnIt] for why the two axes differ.
        assertEquals(100f, (pastedBounds.xMin + pastedBounds.xMax) / 2f, 1f)
        assertEquals(120f, pastedBounds.yMin, 1f)
        vm.undoCanvas()
        assertEquals(1, vm.strokes.value.size)
        vm.redoCanvas()
        assertEquals(2, vm.strokes.value.size)
        advanceUntilIdle()
        vm.openPage(pageId)
        advanceUntilIdle()
        assertEquals("the copied object did not survive reload", 2, vm.strokes.value.size)
    }

    @Test
    fun resizingInkIsUndoableAndReplaysAfterReload() = runTest(dispatcher) {
        val vm = seededViewModel()
        val pageId = vm.uiState.value.selectedPageId!!
        vm.selectTool(DrawTool.Pen(0))
        vm.onStrokeFinished(inkStroke(10f to 10f, 30f to 30f))
        advanceUntilIdle()
        val original = vm.strokes.value.single()

        vm.resizeInk(
            InkLassoResize(
                path = listOf(
                    InkPoint(0f, 0f),
                    InkPoint(40f, 0f),
                    InkPoint(40f, 40f),
                    InkPoint(0f, 40f),
                ),
                targetIds = setOf(original.id),
                projections = setOf(original.projectionKey),
                anchor = InkPoint(0f, 0f),
                scaleX = 2f,
                scaleY = 1.5f,
            ),
        )
        assertEquals(2f, vm.strokes.value.single().scaleX, 0.001f)
        vm.undoCanvas()
        assertEquals(1f, vm.strokes.value.single().scaleX, 0.001f)
        vm.redoCanvas()
        assertEquals(2f, vm.strokes.value.single().scaleX, 0.001f)
        advanceUntilIdle()
        vm.openPage(pageId)
        advanceUntilIdle()

        assertEquals("the resize did not replay", 2f, vm.strokes.value.single().scaleX, 0.001f)
        assertEquals(1.5f, vm.strokes.value.single().scaleY, 0.001f)
    }

    @Test
    fun aNewDrawAfterUndoClearsRedoOnlyOnThatPage() = runTest(dispatcher) {
        val vm = seededViewModel()
        val firstPage = vm.uiState.value.selectedPageId!!
        val sectionId = vm.uiState.value.selectedSectionId!!
        vm.selectTool(DrawTool.Pen(0))
        vm.onStrokeFinished(inkStroke(10f to 10f, 20f to 20f))
        advanceUntilIdle()

        val secondPage = repository.createPage(sectionId, "second")
        vm.openPage(secondPage)
        advanceUntilIdle()
        assertEquals(CanvasUndoState(), vm.canvasUndoState.value)
        vm.onStrokeFinished(inkStroke(30f to 30f, 40f to 40f))
        vm.undoCanvas()
        assertEquals(CanvasUndoState(canRedo = true), vm.canvasUndoState.value)

        vm.onStrokeFinished(inkStroke(50f to 50f, 60f to 60f))
        assertEquals(CanvasUndoState(canUndo = true, canRedo = false), vm.canvasUndoState.value)
        val branched = vm.strokes.value
        vm.redoCanvas()
        assertEquals("redo survived a new branch", branched, vm.strokes.value)

        advanceUntilIdle()
        vm.openPage(firstPage)
        advanceUntilIdle()
        assertEquals("the first page lost its independent undo ring", CanvasUndoState(canUndo = true), vm.canvasUndoState.value)
    }

    /**
     * Regression: [NotesViewModel.persist] rebuilds `PageDoc.outlines` out of the text containers it
     * tracks, so an image or an ink layer — neither of which it manages — was written out of
     * existence by the next autosave. Nothing produces those variants yet, which is what would have
     * made the loss silent when something does: the write succeeds, and the page looks right until
     * the drawing is gone.
     */
    @Test
    fun autosaveKeepsOutlinesTheViewModelDoesNotManage() = runTest(dispatcher) {
        val vm = seededViewModel()
        val sectionId = vm.uiState.value.selectedSectionId!!
        // A page of its own rather than the seeded one, because openPage persists the outgoing page
        // first and would write the seeded state back over this document before reading it.
        val pageId = repository.createPage(sectionId, "mixed")
        repository.saveDoc(
            pageId,
            PageDoc(
                outlines = listOf(
                    Outline.Ink(id = "ink", y = 40f),
                    Outline.Text(id = "text", y = 140f, blocks = listOf(Block.of("body"))),
                    Outline.Image(id = "image", y = 420f, attachmentId = "att-1", height = 200f),
                ),
            ),
        )

        vm.openPage(pageId)
        advanceUntilIdle()
        vm.onBlocksChanged("text", listOf(Block.of("edited body")))
        advanceUntilIdle()

        val stored = (runBlocking { repository.loadDoc(pageId) } as PageLoad.Loaded).doc
        // Position as well as survival: the ink was before the text and the image after it, and an
        // outline that comes back somewhere else is a different page.
        assertEquals(
            "an autosave lost or reordered the outlines the view model does not manage",
            listOf("ink", "text", "image"),
            stored.outlines.map { it.id },
        )
        assertEquals("edited body", stored.plainText())
    }

    private fun inkStroke(
        vararg points: Pair<Float, Float>,
        sizeDp: Float = 6f,
    ): Stroke {
        val inputs = MutableStrokeInputBatch().apply {
            points.forEachIndexed { index, (x, y) ->
                add(InputToolType.UNKNOWN, x, y, index * 10L)
            }
        }.toImmutable()
        return InkCodec.eraseMask(inputs, sizeDp)
    }

    private fun centerBox(): ImmutableBox = ImmutableBox.fromTwoPoints(
        ImmutableVec(48f, 48f),
        ImmutableVec(52f, 52f),
    )

    private fun leftBox(): ImmutableBox = ImmutableBox.fromTwoPoints(
        ImmutableVec(20f, 48f),
        ImmutableVec(30f, 52f),
    )

    private fun rightBox(): ImmutableBox = ImmutableBox.fromTwoPoints(
        ImmutableVec(70f, 48f),
        ImmutableVec(80f, 52f),
    )

    private fun Stroke.overlaps(area: ImmutableBox): Boolean =
        shape.computeCoverageIsGreaterThan(area, 0f)

    // --- Insert Space (E2) ---------------------------------------------------------------------

    /**
     * A page with something of every movable kind on both sides of a line at y = 200.
     *
     * Returns the ids of the things below it, which are the ones the gesture has to move. The
     * strokes are named by row id and identified by where they are, because [inkStroke] hands the
     * page a mask whose exact bounds are the brush's business rather than the test's.
     */
    private suspend fun pageStraddling(vm: NotesViewModel): Map<String, String> {
        vm.selectTool(DrawTool.Pen(0))
        vm.onStrokeFinished(inkStroke(10f to 40f, 90f to 40f))
        vm.onStrokeFinished(inkStroke(10f to 300f, 90f to 300f))
        scheduler.advanceUntilIdle()
        val keptShape = vm.insertShape(ShapeSettings(), 20f, 20f, 120f, 100f)!!
        val movedShape = vm.insertShape(ShapeSettings(), 20f, 400f, 120f, 480f)!!
        val movedText = vm.createOutline(40f, 500f)
        vm.onBlocksChanged(movedText, listOf(Block.of("below the line")))
        scheduler.advanceUntilIdle()
        return mapOf(
            "keptInk" to vm.strokes.value.first { it.pageBounds!!.top < 200f }.id,
            "movedInk" to vm.strokes.value.first { it.pageBounds!!.top >= 200f }.id,
            "keptShape" to keptShape,
            "movedShape" to movedShape,
            "movedText" to movedText,
        )
    }

    private fun NotesViewModel.offsetOf(id: String): Float =
        strokes.value.first { it.id == id }.offsetY

    private fun NotesViewModel.shapeY(id: String): Float =
        uiState.value.shapes.first { it.id == id }.y

    private fun NotesViewModel.outlineY(id: String): Float =
        uiState.value.outlines.first { it.id == id }.y

    /**
     * The whole promise of the tool, and the reason it is not five separate features: one line, and
     * every kind past it moves by the same amount while every kind before it stays exactly where it
     * was.
     */
    @Test
    fun insertSpaceMovesEveryKindPastTheLineAndNothingBeforeIt() = runTest(dispatcher) {
        val vm = seededViewModel()
        val ids = pageStraddling(vm)

        vm.insertSpace(SpaceCut(PageSpace.Axis.Vertical, at = 200f, amount = 120f))
        advanceUntilIdle()

        assertEquals(0f, vm.offsetOf(ids.getValue("keptInk")), 0.01f)
        assertEquals(120f, vm.offsetOf(ids.getValue("movedInk")), 0.01f)
        assertEquals(20f, vm.shapeY(ids.getValue("keptShape")), 0.01f)
        assertEquals(520f, vm.shapeY(ids.getValue("movedShape")), 0.01f)
        assertEquals(620f, vm.outlineY(ids.getValue("movedText")), 0.01f)
    }

    /** The horizontal half of the same gesture — `PageSpace.Axis.Horizontal` reads x, not y. */
    @Test
    fun insertSpacePushesSidewaysToo() = runTest(dispatcher) {
        val vm = seededViewModel()
        val kept = vm.insertShape(ShapeSettings(), 20f, 300f, 60f, 340f)!!
        val moved = vm.insertShape(ShapeSettings(), 400f, 300f, 460f, 340f)!!
        advanceUntilIdle()

        vm.insertSpace(SpaceCut(PageSpace.Axis.Horizontal, at = 200f, amount = 80f))
        advanceUntilIdle()

        assertEquals(20f, vm.uiState.value.shapes.first { it.id == kept }.x, 0.01f)
        assertEquals(480f, vm.uiState.value.shapes.first { it.id == moved }.x, 0.01f)
        assertEquals("a sideways cut moved something vertically", 300f, vm.shapeY(kept), 0.01f)
        assertEquals("a sideways cut moved something vertically", 300f, vm.shapeY(moved), 0.01f)
    }

    /**
     * The one thing this gesture must never do is leave the page half-shifted, so its several kinds
     * are one entry on the history ring rather than one each — `CanvasHistoryEntry.Composite`.
     */
    @Test
    fun insertSpaceIsOneUndoAcrossEveryKindItMoved() = runTest(dispatcher) {
        val vm = seededViewModel()
        val ids = pageStraddling(vm)

        vm.insertSpace(SpaceCut(PageSpace.Axis.Vertical, at = 200f, amount = 120f))
        advanceUntilIdle()
        vm.undoCanvas()

        assertEquals(0f, vm.offsetOf(ids.getValue("movedInk")), 0.01f)
        assertEquals(400f, vm.shapeY(ids.getValue("movedShape")), 0.01f)
        assertEquals(500f, vm.outlineY(ids.getValue("movedText")), 0.01f)
        // One press, not four: everything that was on the page before the gesture is still there.
        assertEquals("the single undo reached past the gesture", 2, vm.strokes.value.size)
        assertEquals("the single undo reached past the gesture", 2, vm.uiState.value.shapes.size)

        vm.redoCanvas()
        assertEquals(120f, vm.offsetOf(ids.getValue("movedInk")), 0.01f)
        assertEquals(520f, vm.shapeY(ids.getValue("movedShape")), 0.01f)
        assertEquals(620f, vm.outlineY(ids.getValue("movedText")), 0.01f)
        advanceUntilIdle()
    }

    /**
     * The ink half rides the existing `ink_moves` log, so the proof it was stored rather than merely
     * applied in memory is that reopening the page replays it to the same place.
     */
    @Test
    fun insertSpaceSurvivesReopeningThePage() = runTest(dispatcher) {
        val vm = seededViewModel()
        val pageId = vm.uiState.value.selectedPageId!!
        val ids = pageStraddling(vm)

        vm.insertSpace(SpaceCut(PageSpace.Axis.Vertical, at = 200f, amount = 120f))
        advanceUntilIdle()
        vm.openPage(pageId)
        advanceUntilIdle()

        assertEquals(120f, vm.offsetOf(ids.getValue("movedInk")), 0.01f)
        assertEquals("replay swept up the stroke above the line", 0f, vm.offsetOf(ids.getValue("keptInk")), 0.01f)
        assertEquals(520f, vm.shapeY(ids.getValue("movedShape")), 0.01f)
        assertEquals(620f, vm.outlineY(ids.getValue("movedText")), 0.01f)
    }

    /**
     * A stroke drawn *after* the gesture is not caught by it on the next load. The stored operation
     * names its targets by row id, so the box it carries is a description of what moved rather than a
     * standing rule about that part of the page.
     */
    @Test
    fun inkDrawnAfterInsertSpaceIsNotMovedByItOnReload() = runTest(dispatcher) {
        val vm = seededViewModel()
        val pageId = vm.uiState.value.selectedPageId!!
        vm.selectTool(DrawTool.Pen(0))
        vm.onStrokeFinished(inkStroke(10f to 300f, 90f to 300f))
        advanceUntilIdle()
        val first = vm.strokes.value.single().id

        vm.insertSpace(SpaceCut(PageSpace.Axis.Vertical, at = 200f, amount = 120f))
        advanceUntilIdle()
        vm.onStrokeFinished(inkStroke(10f to 500f, 90f to 500f))
        advanceUntilIdle()
        val second = vm.strokes.value.first { it.id != first }.id

        vm.openPage(pageId)
        advanceUntilIdle()

        assertEquals(120f, vm.offsetOf(first), 0.01f)
        assertEquals("the later stroke was swept up by the earlier gesture", 0f, vm.offsetOf(second), 0.01f)
    }

    /** Dragging back closes the gap, and stops at the line rather than pulling content through it. */
    @Test
    fun closingSpaceStopsAtTheLine() = runTest(dispatcher) {
        val vm = seededViewModel()
        vm.selectTool(DrawTool.Pen(0))
        vm.onStrokeFinished(inkStroke(10f to 300f, 90f to 300f))
        advanceUntilIdle()
        val top = vm.strokes.value.single().pageBounds!!.top

        vm.insertSpace(SpaceCut(PageSpace.Axis.Vertical, at = 200f, amount = -1000f))
        advanceUntilIdle()

        assertEquals(200f, vm.strokes.value.single().pageBounds!!.top, 0.01f)
        assertEquals(200f - top, vm.strokes.value.single().offsetY, 0.01f)
    }

    /**
     * The limit is computed across the kinds before any of them moves. Asking each to stop itself
     * would let the ink slide the full drag while the shape beside it stopped short, which is exactly
     * the half-shifted page the gesture exists to avoid.
     */
    @Test
    fun aClosingDragIsLimitedByTheNearestObjectOfAnyKind() = runTest(dispatcher) {
        val vm = seededViewModel()
        vm.selectTool(DrawTool.Pen(0))
        vm.onStrokeFinished(inkStroke(10f to 600f, 90f to 600f))
        advanceUntilIdle()
        // 40 dp below the line, and far above the ink — so it, not the ink, decides the limit.
        val shapeId = vm.insertShape(ShapeSettings(), 20f, 240f, 120f, 300f)!!
        advanceUntilIdle()
        val inkTop = vm.strokes.value.single().pageBounds!!.top

        vm.insertSpace(SpaceCut(PageSpace.Axis.Vertical, at = 200f, amount = -500f))
        advanceUntilIdle()

        assertEquals(200f, vm.shapeY(shapeId), 0.01f)
        assertEquals("the ink outran the shape it was moving with", inkTop - 40f, vm.strokes.value.single().pageBounds!!.top, 0.01f)
    }

    /** The near edge decides, so something that begins before the line stays whole and stays put. */
    @Test
    fun anObjectStraddlingTheLineStaysPut() = runTest(dispatcher) {
        val vm = seededViewModel()
        vm.selectTool(DrawTool.Pen(0))
        vm.onStrokeFinished(inkStroke(50f to 150f, 50f to 400f))
        advanceUntilIdle()
        val tall = vm.insertShape(ShapeSettings(), 200f, 150f, 300f, 400f)!!
        advanceUntilIdle()

        vm.insertSpace(SpaceCut(PageSpace.Axis.Vertical, at = 200f, amount = 120f))
        advanceUntilIdle()

        assertEquals(0f, vm.strokes.value.single().offsetY, 0.01f)
        assertEquals(150f, vm.shapeY(tall), 0.01f)
    }

    /**
     * A group is one thing on the page, so a group crossing the line is a thing that straddles it.
     * Judging its members one at a time would push the bottom of a drawing out from under its top.
     */
    @Test
    fun aGroupCrossingTheLineIsNotTornInHalf() = runTest(dispatcher) {
        val vm = seededViewModel()
        vm.selectTool(DrawTool.Pen(0))
        vm.onStrokeFinished(inkStroke(10f to 40f, 90f to 40f))
        vm.onStrokeFinished(inkStroke(10f to 300f, 90f to 300f))
        advanceUntilIdle()
        vm.groupInk(vm.strokes.value.mapTo(mutableSetOf()) { it.id })
        advanceUntilIdle()

        vm.insertSpace(SpaceCut(PageSpace.Axis.Vertical, at = 200f, amount = 120f))
        advanceUntilIdle()

        assertTrue(
            "half the group moved: ${vm.strokes.value.map { it.offsetY }}",
            vm.strokes.value.all { it.offsetY == 0f },
        )
    }

    /** Nothing past the line means nothing to do, and in particular nothing on the history ring. */
    @Test
    fun anInsertSpaceThatMovesNothingIsNotAnAction() = runTest(dispatcher) {
        val vm = seededViewModel()
        val shapeId = vm.insertShape(ShapeSettings(), 20f, 20f, 120f, 100f)!!
        advanceUntilIdle()
        assertEquals(CanvasUndoState(canUndo = true), vm.canvasUndoState.value)

        vm.insertSpace(SpaceCut(PageSpace.Axis.Vertical, at = 900f, amount = 120f))
        advanceUntilIdle()

        // One press reaches the insert, which is only true if the gesture recorded nothing.
        vm.undoCanvas()
        assertTrue("an empty gesture left an entry on the ring", vm.uiState.value.shapes.isEmpty())

        vm.redoCanvas()
        assertEquals(20f, vm.shapeY(shapeId), 0.01f)
        advanceUntilIdle()
    }

    // --- free-form containers ------------------------------------------------------------------

    @Test
    fun containersNeverTypedIntoAreNotPersisted() = runTest(dispatcher) {
        val vm = seededViewModel()
        val pageId = vm.uiState.value.selectedPageId!!
        vm.onBlocksChanged(vm.uiState.value.outlines.first().id, listOf(Block.of("real content")))

        // Tapping empty canvas creates a container; never typing in it must leave no trace.
        vm.createOutline(400f, 400f)
        advanceUntilIdle()

        val stored = runBlocking { repository.loadDoc(pageId) } as PageLoad.Loaded
        assertEquals(1, stored.doc.outlines.size)
    }

    @Test
    fun aSecondContainerIsPersistedOnceItHasText() = runTest(dispatcher) {
        val vm = seededViewModel()
        val pageId = vm.uiState.value.selectedPageId!!
        vm.onBlocksChanged(vm.uiState.value.outlines.first().id, listOf(Block.of("first box")))

        val newId = vm.createOutline(500f, 600f)
        vm.onBlocksChanged(newId, listOf(Block.of("second box")))
        advanceUntilIdle()

        val stored = (runBlocking { repository.loadDoc(pageId) } as PageLoad.Loaded).doc
        assertEquals(2, stored.outlines.size)
        val second = stored.outlines.filterIsInstance<Outline.Text>().single { it.id == newId }
        assertEquals(500f, second.x, 0.01f)
        assertEquals(600f, second.y, 0.01f)
        assertTrue(stored.plainText().contains("first box"))
        assertTrue(stored.plainText().contains("second box"))
    }

    @Test
    fun blurringAnEmptyContainerDiscardsIt() = runTest(dispatcher) {
        val vm = seededViewModel()
        val newId = vm.createOutline(300f, 300f)
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.outlines.size)

        vm.onOutlineBlurred(newId)
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.outlines.size)
    }

    @Test
    fun blurringAContainerWithTextKeepsIt() = runTest(dispatcher) {
        val vm = seededViewModel()
        val newId = vm.createOutline(300f, 300f)
        vm.onBlocksChanged(newId, listOf(Block.of("keep me")))
        advanceUntilIdle()

        vm.onOutlineBlurred(newId)
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.outlines.size)
    }

    @Test
    fun theLastContainerIsNeverDiscarded() = runTest(dispatcher) {
        val vm = seededViewModel()
        val only = vm.uiState.value.outlines.single().id
        vm.onBlocksChanged(only, listOf(Block.empty()))
        advanceUntilIdle()

        vm.onOutlineBlurred(only)
        advanceUntilIdle()

        assertEquals("the page was left with nothing to type into", 1, vm.uiState.value.outlines.size)
    }

    /** An absent entry means "content unknown", and must never be read as "container is empty". */
    @Test
    fun aContainerWithNoRecordedContentIsNeverDiscarded() = runTest(dispatcher) {
        val vm = seededViewModel()
        val before = vm.uiState.value.outlines.size

        vm.onOutlineBlurred("an-id-the-view-model-has-never-seen")
        advanceUntilIdle()

        assertEquals(before, vm.uiState.value.outlines.size)
    }

    // --- notebooks -------------------------------------------------------------------------------

    /**
     * Deleting a notebook only tombstones its own row, so nothing downstream would notice that the
     * open section has gone out of reach — the editor would keep showing a page of a notebook the
     * navigation no longer lists. The view model has to move the selection itself.
     */
    @Test
    fun deletingTheOpenNotebookOpensAnotherOne() = runTest(dispatcher) {
        val vm = seededViewModel()
        val seeded = vm.uiState.value.tree.single().notebook.id
        vm.createNotebook("Second")
        advanceUntilIdle()
        val second = vm.uiState.value.tree.single { it.notebook.id != seeded }
        assertTrue(
            "the new notebook's section should be the open one",
            second.liveSections.any { it.id == vm.uiState.value.selectedSectionId },
        )

        vm.deleteNotebook(second.notebook.id)
        advanceUntilIdle()

        assertEquals(listOf(seeded), vm.uiState.value.tree.map { it.notebook.id })
        val open = vm.uiState.value.selectedSectionId
        assertNotNull("the editor was left with no section open", open)
        assertTrue(
            "the selection stayed inside the deleted notebook",
            vm.uiState.value.tree.single().liveSections.any { it.id == open },
        )
        assertNotNull("no page was opened in the surviving notebook", vm.uiState.value.selectedPageId)
    }

    @Test
    fun deletingAnotherNotebookLeavesTheOpenPageAlone() = runTest(dispatcher) {
        val vm = seededViewModel()
        val seeded = vm.uiState.value.tree.single().notebook.id
        vm.createNotebook("Second")
        advanceUntilIdle()
        // A new notebook's section starts empty, so give it a page for the assertion to be about
        // something: the editor's contents must survive a delete that is not about them.
        vm.addPage()
        advanceUntilIdle()
        val openSection = vm.uiState.value.selectedSectionId
        val openPage = vm.uiState.value.selectedPageId!!

        vm.deleteNotebook(seeded)
        advanceUntilIdle()

        assertEquals(openSection, vm.uiState.value.selectedSectionId)
        assertEquals(openPage, vm.uiState.value.selectedPageId)
    }

    @Test
    fun deletingAndRestoringTheOpenNotebookPublishesAnAppWideRecoveryAction() =
        runTest(dispatcher) {
            val vm = seededViewModel()
            val notebookId = vm.uiState.value.tree.single().notebook.id
            val notice = async { vm.deletionNotices.first() }

            vm.deleteNotebook(notebookId)
            advanceUntilIdle()

            val deletion = notice.await()
            assertEquals(DeletedItemKind.Notebook, deletion.key.kind)
            assertEquals(notebookId, deletion.key.id)
            assertEquals(listOf(notebookId), vm.deletedItems.value.items.map { it.key.id })

            vm.restoreDeletedItem(deletion.key)
            advanceUntilIdle()

            assertTrue(vm.deletedItems.value.items.isEmpty())
            assertEquals(listOf(notebookId), vm.uiState.value.tree.map { it.notebook.id })
        }

    /** What the confirmation reads out before the user agrees to it. */
    @Test
    fun notebookContentsCountsLiveSectionsAndPages() = runTest(dispatcher) {
        val vm = seededViewModel()
        val notebookId = vm.uiState.value.tree.single().notebook.id
        val before = vm.notebookContents(notebookId)
        val keptSection = repository.createSection(notebookId, "Kept section")
        repository.createPage(keptSection, "kept")
        val gonePage = repository.createPage(keptSection, "gone")
        repository.deletePage(gonePage)
        val goneSection = repository.createSection(notebookId, "Deleted section")
        // Never tombstoned itself, only stranded by its section's tombstone — the case that decides
        // whether the count is of rows or of pages the user can still reach.
        repository.createPage(goneSection, "unreachable")
        repository.deleteSection(goneSection)
        advanceUntilIdle()

        val after = vm.notebookContents(notebookId)

        assertEquals(before.sections + 1, after.sections)
        assertEquals(before.pages + 1, after.pages)
    }

    @Test
    fun deletingAndRestoringTheOpenPageKeepsEditsStillInsideTheAutosaveWindow() =
        runTest(dispatcher) {
            val vm = seededViewModel()
            val pageId = vm.uiState.value.selectedPageId!!
            val outlineId = vm.uiState.value.outlines.first().id
            val notice = async { vm.deletionNotices.first() }

            vm.onBlocksChanged(outlineId, listOf(Block.of("typed immediately before delete")))
            vm.deletePage(pageId)
            advanceUntilIdle()

            val deletion = notice.await()
            assertEquals(pageId, deletion.key.id)
            assertEquals(DeletedItemKind.Page, deletion.key.kind)
            assertEquals(listOf(pageId), vm.deletedItems.value.items.map { it.key.id })

            vm.restoreDeletedItem(deletion.key)
            advanceUntilIdle()

            assertTrue(vm.deletedItems.value.items.isEmpty())
            assertEquals("typed immediately before delete", storedText(pageId))
        }

    // --- page appearance -------------------------------------------------------------------------

    /**
     * The View tab writes into the document, so its settings ride the same autosave path as text —
     * and must come back with the page, not with the app.
     */
    @Test
    fun pageAppearanceIsSavedAndRestored() = runTest(dispatcher) {
        val vm = seededViewModel()
        val pageId = vm.uiState.value.selectedPageId!!

        vm.setRuleLines(RuleLines.College)
        vm.setPaperSize(PaperSize.A4)
        vm.setOrientation(Orientation.Landscape)
        vm.setPageColor(0xFF102030.toInt())
        vm.setHideTitle(true)
        advanceUntilIdle()

        vm.openPage(pageId)
        advanceUntilIdle()

        val style = vm.uiState.value.pageStyle
        assertEquals(RuleLines.College, style.ruleLines)
        assertEquals(PaperSize.A4, style.paper)
        assertEquals(Orientation.Landscape, style.orientation)
        assertEquals(0xFF102030.toInt(), style.backgroundArgb)
        assertEquals(true, style.hideTitle)
    }

    /** Appearance belongs to one page, so styling it must not follow the user to the next. */
    @Test
    fun pageAppearanceDoesNotLeakToAnotherPage() = runTest(dispatcher) {
        val vm = seededViewModel()
        val sectionId = vm.uiState.value.selectedSectionId!!
        vm.setRuleLines(RuleLines.Wide)
        advanceUntilIdle()

        vm.openPage(repository.createPage(sectionId, "another"))
        advanceUntilIdle()

        assertEquals(PageStyle().ruleLines, vm.uiState.value.pageStyle.ruleLines)
    }

    /** A page can be styled before a word is typed in it, and that is worth persisting on its own. */
    @Test
    fun appearanceIsPersistedOnAPageWithNoText() = runTest(dispatcher) {
        val vm = seededViewModel()
        val sectionId = vm.uiState.value.selectedSectionId!!
        val pageId = repository.createPage(sectionId, "blank")
        vm.openPage(pageId)
        advanceUntilIdle()

        vm.setRuleLines(RuleLines.GridLarge)
        advanceUntilIdle()

        val stored = (runBlocking { repository.loadDoc(pageId) } as PageLoad.Loaded).doc
        assertEquals(RuleLines.GridLarge, stored.style.ruleLines)
    }

    /** The read-only rule covers appearance too: it is written into the same document. */
    @Test
    fun stylingAnUnreadablePageDoesNotOverwriteIt() = runTest(dispatcher) {
        val vm = seededViewModel()
        val sectionId = vm.uiState.value.selectedSectionId!!
        val pageId = repository.createPage(sectionId, "corrupt")
        val garbage = """{"outlines":[{"t":"text","id":"x","blocks":"not-an-array"}]}"""
        db.pageContentDao().upsert(PageContentEntity(pageId, garbage, 1L))
        vm.openPage(pageId)
        advanceUntilIdle()

        vm.setRuleLines(RuleLines.Narrow)
        vm.setHideTitle(true)
        advanceUntilIdle()

        assertEquals("the unreadable document was overwritten", garbage, storedJson(pageId))
    }

    @Test
    fun editingOneContainerLeavesTheOtherIntact() = runTest(dispatcher) {
        val vm = seededViewModel()
        val pageId = vm.uiState.value.selectedPageId!!
        val first = vm.uiState.value.outlines.first().id
        vm.onBlocksChanged(first, listOf(Block.of("alpha")))
        val second = vm.createOutline(600f, 100f)
        vm.onBlocksChanged(second, listOf(Block.of("beta")))
        advanceUntilIdle()

        vm.onBlocksChanged(second, listOf(Block.of("beta edited")))
        advanceUntilIdle()

        val text = storedText(pageId)
        assertTrue("the untouched container was affected", text.contains("alpha"))
        assertTrue(text.contains("beta edited"))
    }

    // --- editor defaults -------------------------------------------------------------------------

    /**
     * The far end of the ribbon's press-and-hold: the size has to reach the preferences file.
     *
     * Deliberately not on the test dispatcher. DataStore runs a write in *its caller's* context, so
     * a view model launched on a paused scheduler writes only while that scheduler is being turned —
     * and waiting on the result blocks the very thread that would turn it. Running Main unconfined
     * puts the write on the calling thread, which is what it has in the app.
     */
    @Test
    fun holdingASizeStoresItAsTheDefault() = runBlocking<Unit> {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val vm = NotesViewModel(repository, attachments, editorDefaults, viewSettings, penSettings)

        vm.setDefaultFont(Mark.FontSize(36))

        withTimeout(STORE_TIMEOUT_MS) { editorDefaults.defaults.first { it.fontSize == 36 } }
        assertEquals("the flow the ribbon renders from", 36, vm.editorDefaults.value.fontSize)
    }

    @Test
    fun holdingAFontStoresItAsTheDefault() = runBlocking<Unit> {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val vm = NotesViewModel(repository, attachments, editorDefaults, viewSettings, penSettings)

        vm.setDefaultFont(Mark.FontFamily("lora"))

        withTimeout(STORE_TIMEOUT_MS) { editorDefaults.defaults.first { it.fontFamily == "lora" } }
    }

    /**
     * Picking a size is an edit; only holding one is a preference. The two used to be a single
     * path, so choosing 28 to write one heading in changed what every page opened afterwards
     * started at. Arming now moves the readout and nothing else.
     */
    @Test
    fun armingASizeMovesTheReadoutAndNothingElse() = runTest(dispatcher) {
        val vm = seededViewModel()

        vm.onMarkArmed(Mark.FontSize(48))
        advanceUntilIdle()

        assertEquals("the ribbon must show the pick", 48, vm.selection.value.fontSize)
        assertTrue(
            "arming must not touch the store at all",
            runBlocking { editorDefaults.defaults.first().fontSize } != 48,
        )
    }

    // --- zoom ------------------------------------------------------------------------------------

    /**
     * A pinch reports a zoom every frame, and a preferences file is not where something changing at
     * that rate belongs — one gesture would be sixty writes, with the canvas waiting on a disk round
     * trip to redraw each time. So the canvas follows immediately and the store hears about it once,
     * from [NotesViewModel.commitZoom].
     */
    @Test
    fun aPinchMovesTheCanvasWithoutTouchingTheStore() = runTest(dispatcher) {
        val vm = seededViewModel()
        val before = runBlocking { viewSettings.settings.first().zoom }

        vm.pinchZoom(1.37f)
        advanceUntilIdle()

        assertEquals("the canvas has to follow the fingers", 1.37f, vm.viewSettings.value.zoom)
        assertEquals(
            "a pinch in flight must not touch the store at all",
            before,
            runBlocking { viewSettings.settings.first().zoom },
        )
    }

    /** Fingers do not stop at 400%, so the clamp has to be on this path and not only the ribbon's. */
    @Test
    fun aPinchCannotLeaveTheZoomRange() = runTest(dispatcher) {
        val vm = seededViewModel()

        vm.pinchZoom(99f)
        advanceUntilIdle()
        assertEquals(ViewSettings.MAX_ZOOM, vm.viewSettings.value.zoom)

        vm.pinchZoom(0.01f)
        advanceUntilIdle()
        assertEquals(ViewSettings.MIN_ZOOM, vm.viewSettings.value.zoom)
    }

    /** Unconfined for the reason [holdingASizeStoresItAsTheDefault] is: this one actually writes. */
    @Test
    fun liftingTheFingersWritesTheZoomDown() = runBlocking<Unit> {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val vm = NotesViewModel(repository, attachments, editorDefaults, viewSettings, penSettings)

        vm.pinchZoom(1.75f)
        vm.commitZoom()

        withTimeout(STORE_TIMEOUT_MS) { viewSettings.settings.first { it.zoom == 1.75f } }
    }

    // --- tables — `docs/tablePlan.md` ----------------------------------------------------------

    /** The cell at [row], [column] of the only table on the page. */
    private fun NotesViewModel.cellId(row: Int, column: Int): String =
        uiState.value.tables.single().cellAt(row, column)!!.id

    /**
     * A table with editors in its cells.
     *
     * Spelled out rather than left to `TableSettings()`, whose default is the ruling to write in —
     * the button that reads it lives on the Draw tab. Every test below that types into a cell needs
     * the other kind, and needs it to stay the other kind if that default ever moves again.
     */
    private fun typed(
        columns: Int = TableSettings.DEFAULT_COLUMNS,
        rows: Int = TableSettings.DEFAULT_ROWS,
    ) = TableSettings(inkOnly = false, columns = columns, rows = rows)

    /**
     * The whole cycle a table has to survive: placed, typed in, saved, reopened.
     *
     * The trap this guards is TA2's: a cell's live text lives in the ViewModel's block map and the
     * cells carried on `uiState.tables` go stale the moment anything is typed. A save that read the
     * stale copy would store an empty grid, and nothing before the reload would show it.
     */
    @Test
    fun aTableIsSavedWithWhatWasTypedInIt() = runTest(dispatcher) {
        val vm = seededViewModel()
        val pageId = vm.uiState.value.selectedPageId!!

        vm.insertTable(typed(columns = 2, rows = 2), 40f, 40f)
        advanceUntilIdle()
        vm.onBlocksChanged(vm.cellId(0, 1), listOf(Block.of("in a cell")))
        advanceUntilIdle()

        assertTrue(storedText(pageId).contains("in a cell"))

        vm.openPage(pageId)
        advanceUntilIdle()
        val reloaded = vm.uiState.value.tables.single()
        assertEquals(2, reloaded.columnCount)
        assertEquals("in a cell", vm.initialBlocksFor(reloaded.cellAt(0, 1)!!.id).single().text)
    }

    /**
     * A blank cell is written where a blank container is skipped — TA12.
     *
     * The two rules look contradictory and are not: an empty container is a caret position nobody
     * typed in, and an empty cell is part of the grid's shape. Dropping one would resize the table
     * on reload, which is the failure this pins.
     */
    @Test
    fun aTableWithNothingInItStillHasItsShapeAfterAReload() = runTest(dispatcher) {
        val vm = seededViewModel()
        val pageId = vm.uiState.value.selectedPageId!!

        vm.insertTable(typed(columns = 3, rows = 2), 40f, 40f)
        advanceUntilIdle()

        vm.openPage(pageId)
        advanceUntilIdle()
        val reloaded = vm.uiState.value.tables.single()
        assertEquals(3, reloaded.columnCount)
        assertEquals(2, reloaded.rowCount)
        assertEquals(6, reloaded.cellIds().size)
    }

    /**
     * TR3, the risk this feature's history was written around: a deleted row's *text* has to come
     * back, not merely its cells.
     *
     * Asserted on the text rather than on the row count, because a row restored with blank cells
     * passes every count.
     */
    @Test
    fun deletingARowTakesItsTextAndUndoBringsItBack() = runTest(dispatcher) {
        val vm = seededViewModel()

        vm.insertTable(typed(columns = 1, rows = 3), 40f, 40f)
        advanceUntilIdle()
        val doomed = vm.cellId(1, 0)
        vm.onBlocksChanged(doomed, listOf(Block.of("second row")))
        advanceUntilIdle()

        vm.deleteTableRow(vm.uiState.value.tables.single().id, 1)
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.tables.single().rowCount)

        vm.undoCanvas()
        val restored = vm.uiState.value.tables.single()
        assertEquals(3, restored.rowCount)
        assertEquals(
            "the row came back empty",
            "second row",
            vm.initialBlocksFor(restored.cellAt(1, 0)!!.id).single().text,
        )
    }

    @Test
    fun addingAColumnGivesEveryRowACellAndUndoTakesThemAway() = runTest(dispatcher) {
        val vm = seededViewModel()

        vm.insertTable(typed(columns = 2, rows = 3), 40f, 40f)
        advanceUntilIdle()
        val tableId = vm.uiState.value.tables.single().id

        vm.insertTableColumn(tableId, 0)
        advanceUntilIdle()
        val grown = vm.uiState.value.tables.single()
        assertEquals(3, grown.columnCount)
        grown.rows.forEach { assertEquals(3, it.cells.size) }

        vm.undoCanvas()
        assertEquals(2, vm.uiState.value.tables.single().columnCount)
    }

    /** The last row is not removable, so the action must leave no history entry behind either. */
    @Test
    fun deletingTheLastRowIsRefusedAndCostsNoUndoStep() = runTest(dispatcher) {
        val vm = seededViewModel()

        vm.insertTable(typed(columns = 1, rows = 1), 40f, 40f)
        advanceUntilIdle()
        val tableId = vm.uiState.value.tables.single().id

        vm.deleteTableRow(tableId, 0)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.tables.single().rowCount)

        // One press of Undo takes back the insert, which is the only thing that happened.
        vm.undoCanvas()
        assertTrue(vm.uiState.value.tables.isEmpty())
    }

    /** TR6: a pasted table without its text is a grid of lines, and a copy is nothing without one. */
    @Test
    fun copyingATableAndPastingItCarriesTheText() = runTest(dispatcher) {
        val vm = seededViewModel()

        vm.insertTable(typed(columns = 2, rows = 1), 40f, 40f)
        advanceUntilIdle()
        val source = vm.uiState.value.tables.single()
        vm.onBlocksChanged(vm.cellId(0, 0), listOf(Block.of("copied")))
        advanceUntilIdle()

        vm.copySelection(
            CanvasSelection(tableIds = setOf(source.id), bounds = InkBounds(0f, 0f, 0f, 0f)),
        )
        vm.pasteObjects(InkPoint(400f, 400f))
        advanceUntilIdle()

        val tables = vm.uiState.value.tables
        assertEquals(2, tables.size)
        val pasted = tables.first { it.id != source.id }
        assertEquals("copied", vm.initialBlocksFor(pasted.cellAt(0, 0)!!.id).single().text)
        // Fresh ids all the way down, or typing in one would appear in the other.
        assertTrue(pasted.cellIds().none { it in source.cellIds() })
    }

    /** Undo is one ring across kinds (SD10), and a table is the third thing to join it. */
    @Test
    fun undoReachesBackPastATableToTheShapeBeforeIt() = runTest(dispatcher) {
        val vm = seededViewModel()

        vm.insertShape(ShapeSettings(), 40f, 40f, 160f, 120f)
        advanceUntilIdle()
        vm.insertTable(typed(), 200f, 200f)
        advanceUntilIdle()

        vm.undoCanvas()
        assertTrue("Undo took the shape instead of the table placed after it", vm.uiState.value.tables.isEmpty())
        assertEquals("Undo took the shape as well", 1, vm.uiState.value.shapes.size)

        vm.undoCanvas()
        assertTrue("the second Undo did not reach the shape", vm.uiState.value.shapes.isEmpty())
    }

    /**
     * A cell that happens to be blank must not be swept up by the container sweep.
     *
     * `onOutlineBlurred` discards empty *containers*, and since TA2 it is handed ids out of a map
     * that now also holds cells. Without its guard, tabbing through a blank cell would delete that
     * cell's entry — and the next save writes a table with a hole in it.
     */
    @Test
    fun blurringABlankCellDoesNotDiscardIt() = runTest(dispatcher) {
        val vm = seededViewModel()

        vm.insertTable(typed(columns = 2, rows = 1), 40f, 40f)
        advanceUntilIdle()
        val cell = vm.cellId(0, 1)

        vm.onOutlineBlurred(cell)
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.tables.single().columnCount)
        assertEquals(1, vm.initialBlocksFor(cell).size)
    }

    /**
     * The Draw tab's table — TA15. **A page holding only a ruling still saves.**
     *
     * This is the regression the ink table can cause and nothing else can: `persist` refuses to write
     * while any content box's blocks are unknown, and an ink table's cells have no blocks by design.
     * Route them through the same map as a typed cell's and the guard is never satisfied, so the page
     * silently stops saving — no error, no crash, just work that is gone the next time it opens.
     */
    @Test
    fun aPageHoldingOnlyAnInkTableIsStillWritten() = runTest(dispatcher) {
        val vm = seededViewModel()
        val pageId = vm.uiState.value.selectedPageId!!

        vm.insertTable(TableSettings(inkOnly = true, columns = 3, rows = 4), 40f, 40f)
        advanceUntilIdle()

        vm.openPage(pageId)
        advanceUntilIdle()
        val reloaded = vm.uiState.value.tables.single()
        assertTrue("it came back as a grid of text fields", reloaded.inkOnly)
        assertEquals(3, reloaded.columnCount)
        assertEquals(4, reloaded.rowCount)
    }

    /** Rows and columns work the same on either kind; only what is *in* a cell differs. */
    @Test
    fun anInkTableGrowsWithoutEverTouchingTheBlockMap() = runTest(dispatcher) {
        val vm = seededViewModel()

        vm.insertTable(TableSettings(inkOnly = true, columns = 2, rows = 2), 40f, 40f)
        advanceUntilIdle()
        val table = vm.uiState.value.tables.single()
        assertTrue(table.contentCellIds().isEmpty())

        vm.insertTableRow(table.id, 0)
        advanceUntilIdle()
        val grown = vm.uiState.value.tables.single()
        assertEquals(3, grown.rowCount)

        vm.undoCanvas()
        assertEquals(2, vm.uiState.value.tables.single().rowCount)
    }

    private companion object {
        const val STORE_TIMEOUT_MS = 5_000L
    }
}
