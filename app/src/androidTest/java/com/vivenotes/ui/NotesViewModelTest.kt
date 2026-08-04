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
import com.vivenotes.data.DrawTool
import com.vivenotes.data.EditorDefaults
import com.vivenotes.data.EditorDefaultsStore
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.PageLoad
import com.vivenotes.data.PenSettingsStore
import com.vivenotes.data.ShapeSettings
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
import com.vivenotes.model.PageStyle
import com.vivenotes.model.PaperSize
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
    private lateinit var editorDefaults: EditorDefaultsStore
    private lateinit var viewSettings: ViewSettingsStore
    private lateinit var penSettings: PenSettingsStore

    /** The device's own editor defaults, restored after the tests that overwrite them. */
    private var savedDefaults: EditorDefaults? = null

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
        // Backed by a real file, unlike the in-memory database — the preferences delegate memoises
        // per Context, so every test in this class shares one store rather than clashing over it.
        editorDefaults = EditorDefaultsStore(context)
        viewSettings = ViewSettingsStore(context)
        penSettings = PenSettingsStore(context)
        // That file belongs to the installed app, not to the test, so whatever the defaults tests
        // write to it has to be put back — otherwise running the suite silently changes the font
        // the user's own build types in.
        savedDefaults = runBlocking { editorDefaults.defaults.first() }
    }

    @After
    fun tearDown() {
        savedDefaults?.let { saved ->
            runBlocking {
                editorDefaults.setFontSize(saved.fontSize)
                editorDefaults.setFontFamily(saved.fontFamily)
            }
        }
        db.close()
        Dispatchers.resetMain()
    }

    /** Builds a settled view model sitting on the seeded Welcome page. */
    private suspend fun seededViewModel(): NotesViewModel {
        val vm = NotesViewModel(
            repository,
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

    @Test
    fun drawingCanBeUndoneRedoneAndReloaded() = runTest(dispatcher) {
        val vm = seededViewModel()
        val pageId = vm.uiState.value.selectedPageId!!
        vm.selectTool(DrawTool.Pen(0))

        vm.onStrokeFinished(inkStroke(10f to 50f, 90f to 50f))
        advanceUntilIdle()
        assertEquals(1, vm.strokes.value.size)
        assertEquals(InkUndoState(canUndo = true), vm.inkUndoState.value)

        vm.undoInk()
        assertTrue(vm.strokes.value.isEmpty())
        assertEquals(InkUndoState(canRedo = true), vm.inkUndoState.value)
        advanceUntilIdle()
        vm.openPage(pageId)
        advanceUntilIdle()
        assertTrue("undoing a draw was not persisted", vm.strokes.value.isEmpty())

        vm.redoInk()
        assertEquals(1, vm.strokes.value.size)
        assertEquals(InkUndoState(canUndo = true), vm.inkUndoState.value)
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
        assertEquals("history stayed active while erase geometry was unresolved", InkUndoState(), vm.inkUndoState.value)
        advanceUntilIdle()
        assertFalse(vm.strokes.value.any { it.stroke.overlaps(centerBox()) })
        assertEquals(InkUndoState(canUndo = true), vm.inkUndoState.value)

        vm.undoInk()
        assertTrue(vm.strokes.value.single().stroke.overlaps(centerBox()))
        advanceUntilIdle()
        vm.openPage(pageId)
        advanceUntilIdle()
        assertTrue("the undone erase still replayed after reload", vm.strokes.value.single().stroke.overlaps(centerBox()))

        vm.redoInk()
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

        vm.undoInk()
        assertEquals(0f, vm.strokes.value.single().offsetX, 0.01f)
        assertEquals(0f, vm.strokes.value.single().offsetY, 0.01f)
        advanceUntilIdle()
        vm.openPage(pageId)
        advanceUntilIdle()
        assertEquals("the undone move still replayed after reload", 0f, vm.strokes.value.single().offsetX, 0.01f)

        vm.redoInk()
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
        assertEquals(100f, (pastedBounds.xMin + pastedBounds.xMax) / 2f, 1f)
        assertEquals(120f, (pastedBounds.yMin + pastedBounds.yMax) / 2f, 1f)
        vm.undoInk()
        assertEquals(1, vm.strokes.value.size)
        vm.redoInk()
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
        vm.undoInk()
        assertEquals(1f, vm.strokes.value.single().scaleX, 0.001f)
        vm.redoInk()
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
        assertEquals(InkUndoState(), vm.inkUndoState.value)
        vm.onStrokeFinished(inkStroke(30f to 30f, 40f to 40f))
        vm.undoInk()
        assertEquals(InkUndoState(canRedo = true), vm.inkUndoState.value)

        vm.onStrokeFinished(inkStroke(50f to 50f, 60f to 60f))
        assertEquals(InkUndoState(canUndo = true, canRedo = false), vm.inkUndoState.value)
        val branched = vm.strokes.value
        vm.redoInk()
        assertEquals("redo survived a new branch", branched, vm.strokes.value)

        advanceUntilIdle()
        vm.openPage(firstPage)
        advanceUntilIdle()
        assertEquals("the first page lost its independent undo ring", InkUndoState(canUndo = true), vm.inkUndoState.value)
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
        val vm = NotesViewModel(repository, editorDefaults, viewSettings, penSettings)

        vm.setDefaultFont(Mark.FontSize(36))

        withTimeout(STORE_TIMEOUT_MS) { editorDefaults.defaults.first { it.fontSize == 36 } }
        assertEquals("the flow the ribbon renders from", 36, vm.editorDefaults.value.fontSize)
    }

    @Test
    fun holdingAFontStoresItAsTheDefault() = runBlocking<Unit> {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val vm = NotesViewModel(repository, editorDefaults, viewSettings, penSettings)

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

    private companion object {
        const val STORE_TIMEOUT_MS = 5_000L
    }
}
