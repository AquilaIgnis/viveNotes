package st.unamedtba.ui

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import st.unamedtba.data.NotesRepository
import st.unamedtba.data.PageLoad
import st.unamedtba.data.db.NotesDatabase
import st.unamedtba.data.db.PageContentEntity
import st.unamedtba.model.Block
import st.unamedtba.model.Outline
import st.unamedtba.model.plainText

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
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    /** Builds a settled view model sitting on the seeded Welcome page. */
    private suspend fun seededViewModel(): NotesViewModel {
        val vm = NotesViewModel(repository)
        scheduler.advanceUntilIdle()
        assertNotNull("expected the seeded page to be open", vm.uiState.value.selectedPageId)
        return vm
    }

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
}
