package com.vivenotes.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.data.db.PageContentEntity
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.newId
import com.vivenotes.model.plainText

@RunWith(AndroidJUnit4::class)
class NotesRepositoryTest {

    private lateinit var db: NotesDatabase
    private lateinit var repository: NotesRepository
    private var now = 1_000_000L

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        now = 1_000_000L
        repository = NotesRepository(db, clock = { now })
    }

    @After
    fun tearDown() = db.close()

    private fun newPage(): String = runBlocking {
        val notebookId = repository.createNotebook("nb")
        val sectionId = repository.createSection(notebookId, "sec")
        repository.createPage(sectionId, "page")
    }

    @Test
    fun roundTripsADocumentThroughStorage() = runBlocking {
        val pageId = newPage()
        val doc = PageDoc(
            outlines = listOf(
                Outline.Text(id = newId(), x = 30f, y = 90f, width = 500f, blocks = listOf(Block.of("kept"))),
                Outline.Text(id = newId(), x = 400f, y = 10f, width = 300f, blocks = listOf(Block.of("second"))),
            ),
        )

        repository.saveDoc(pageId, doc)
        val load = repository.loadDoc(pageId)

        assertTrue("expected a clean load, got $load", load is PageLoad.Loaded)
        assertEquals(doc, (load as PageLoad.Loaded).doc)
    }

    /**
     * Regression: content that cannot be decoded used to come back as an empty document, which
     * autosave then wrote over the original. A read failure must never look like an empty page.
     */
    @Test
    fun reportsUnreadableContentRatherThanReturningAnEmptyDocument() = runBlocking {
        val pageId = newPage()
        val garbage = """{"outlines":[{"t":"text","id":"x","blocks":"not-an-array"}]}"""
        db.pageContentDao().upsert(PageContentEntity(pageId, garbage, System.currentTimeMillis()))

        val load = repository.loadDoc(pageId)

        assertTrue("unreadable content was reported as $load", load is PageLoad.Unreadable)
        assertEquals(garbage, (load as PageLoad.Unreadable).rawJson)
    }

    @Test
    fun storedRowsRecordTheCodecThatWroteThem() = runBlocking {
        val pageId = newPage()
        repository.saveDoc(pageId, PageDoc(outlines = listOf(Outline.Text(id = "o", blocks = listOf(Block.of("x"))))))

        assertEquals("json/1", db.pageContentDao().byId(pageId)!!.format)
    }

    /**
     * A row written by a format this build does not know must be reported, not guessed at. Without
     * this, switching the sync protocol to MessagePack would silently corrupt older rows.
     */
    @Test
    fun aRowInAnUnknownFormatIsReportedRatherThanDecodedAnyway() = runBlocking {
        val pageId = newPage()
        db.pageContentDao().upsert(
            PageContentEntity(pageId, """{"outlines":[]}""", 0L, format = "msgpack/1"),
        )

        val load = repository.loadDoc(pageId)

        assertTrue("an unknown format decoded anyway: $load", load is PageLoad.Unreadable)
    }

    @Test
    fun aFreshPageLoadsAsAnEmptyDocument() = runBlocking {
        val load = repository.loadDoc(newPage())

        assertTrue("expected a clean load, got $load", load is PageLoad.Loaded)
        assertTrue((load as PageLoad.Loaded).doc.plainText().isBlank())
    }

    @Test
    fun autosavesBecomeCompressedCheckpointsRatherThanOneRevisionPerWrite() = runBlocking {
        val pageId = newPage()
        fun doc(text: String) = PageDoc(
            outlines = listOf(Outline.Text(id = "text", blocks = listOf(Block.of(text)))),
        )

        repository.saveDoc(pageId, doc("one"))
        now += 1_000
        repository.saveDoc(pageId, doc("two"))

        var history = repository.revisionHistory(pageId)
        assertEquals("the 400 ms autosaves were not coalesced", 1, history.size)
        assertTrue(history.single().byteCount > 0)
        val first = repository.loadRevision(pageId, history.single().id)
        assertTrue(first is PageRevisionLoad.Loaded)
        assertTrue((first as PageRevisionLoad.Loaded).doc.plainText().isBlank())

        now += NotesRepository.REVISION_CHECKPOINT_INTERVAL_MS
        repository.saveDoc(pageId, doc("three"))

        history = repository.revisionHistory(pageId)
        assertEquals(2, history.size)
        val newest = repository.loadRevision(pageId, history.first().id)
        assertEquals("two", (newest as PageRevisionLoad.Loaded).doc.plainText())
    }

    @Test
    fun restoringARevisionCheckpointsTheStateItReplaces() = runBlocking {
        val pageId = newPage()
        fun doc(text: String) = PageDoc(
            outlines = listOf(Outline.Text(id = "text", blocks = listOf(Block.of(text)))),
        )

        repository.saveDoc(pageId, doc("one"))
        now += NotesRepository.REVISION_CHECKPOINT_INTERVAL_MS
        repository.saveDoc(pageId, doc("two"))
        val revisionOfOne = repository.revisionHistory(pageId).first()

        now += 1
        val restored = repository.restoreRevision(pageId, revisionOfOne.id)

        assertTrue(restored is PageRevisionLoad.Loaded)
        assertEquals("one", ((repository.loadDoc(pageId) as PageLoad.Loaded).doc.plainText()))
        val safetyRevision = repository.revisionHistory(pageId).first()
        assertEquals(
            "two",
            (repository.loadRevision(pageId, safetyRevision.id) as PageRevisionLoad.Loaded)
                .doc
                .plainText(),
        )
    }

    @Test
    fun alternatingBetweenTwoRevisionsDoesNotDuplicateEitherOne() = runBlocking {
        val pageId = newPage()
        fun doc(text: String) = PageDoc(
            outlines = listOf(Outline.Text(id = "text", blocks = listOf(Block.of(text)))),
        )

        repository.saveDoc(pageId, doc("one"))
        now += NotesRepository.REVISION_CHECKPOINT_INTERVAL_MS
        repository.saveDoc(pageId, doc("two"))
        val revisionOfOne = repository.revisionHistory(pageId).first { revision ->
            (repository.loadRevision(pageId, revision.id) as PageRevisionLoad.Loaded)
                .doc
                .plainText() == "one"
        }
        // Simulate the duplicate rows produced by an older build. The original is newer so cleanup
        // keeps the id this test uses for the subsequent back-and-forth restores.
        val storedOne = db.pageRevisionDao().byId(pageId, revisionOfOne.id)!!
        db.pageRevisionDao().insert(
            storedOne.copy(id = newId(), createdAt = storedOne.createdAt - 1),
        )

        // The first restore legitimately creates the only safety copy of "two".
        repository.restoreRevision(pageId, revisionOfOne.id)
        val revisionOfTwo = repository.revisionHistory(pageId).first { revision ->
            (repository.loadRevision(pageId, revision.id) as PageRevisionLoad.Loaded)
                .doc
                .plainText() == "two"
        }

        repeat(4) {
            repository.restoreRevision(pageId, revisionOfTwo.id)
            repository.restoreRevision(pageId, revisionOfOne.id)
        }

        assertEquals("legacy duplicate rows were not healed", 3, repository.revisionHistory(pageId).size)
        assertEquals("one", (repository.loadDoc(pageId) as PageLoad.Loaded).doc.plainText())
    }

    @Test
    fun strokesSharingADrawOrderPaintTheSameWayOnEveryDevice() = runBlocking {
        val pageId = newPage()
        // What two devices drawing on one page while offline produce: both allocate the same seq
        // from their own copy of the page. Whichever row this device happens to store first is the
        // one the other device stores second, so an order settled by rowid renders two pages.
        db.inkStrokeDao().insert(
            listOf(
                inkRow("f0000000-0000-7000-8000-000000000000", pageId, seq = 7),
                inkRow("10000000-0000-7000-8000-000000000000", pageId, seq = 7),
            ),
        )
        // Dropped so the query has to sort. `(pageId, seq, id)` hands back exactly the order this
        // test asserts, so with the index in place `ORDER BY seq` alone passes too and the test
        // proves nothing: it would be measuring the planner's choice rather than the guarantee.
        // Draw order has to survive any plan, including the one a later index changes.
        db.openHelper.writableDatabase.execSQL("DROP INDEX index_ink_strokes_pageId_seq_id")

        assertEquals(
            listOf(
                "10000000-0000-7000-8000-000000000000",
                "f0000000-0000-7000-8000-000000000000",
            ),
            repository.inkFor(pageId).map { it.id },
        )
    }

    @Test
    fun aStrokeIsNumberedAboveEveryStrokeThisDeviceHasSeen() = runBlocking {
        val pageId = newPage()
        // Written the way a pull writes one: another device's number, kept as it arrived.
        db.inkStrokeDao().insert(inkRow(newId(), pageId, seq = 41))

        assertEquals(42, repository.addStroke(inkRow(newId(), pageId, seq = 0)).seq)

        // An erase must not free a number either, or the next stroke would land underneath ink the
        // other device can still restore.
        db.inkStrokeDao().insert(inkRow(newId(), pageId, seq = 99, deletedAt = now))

        assertEquals(100, repository.addStroke(inkRow(newId(), pageId, seq = 0)).seq)
    }

    private fun inkRow(id: String, pageId: String, seq: Int, deletedAt: Long? = null) =
        InkStrokeEntity(
            id = id,
            pageId = pageId,
            seq = seq,
            brushFamily = "marker",
            brushVersion = 1,
            sizeDp = 3f,
            colorArgb = 0xFF000000.toInt(),
            epsilon = 0.1f,
            stabilization = 0,
            minX = 0f,
            minY = 0f,
            maxX = 1f,
            maxY = 1f,
            points = byteArrayOf(1),
            enc = "test/1",
            createdAt = now,
            deletedAt = deletedAt,
        )

    @Test
    fun restoringBackAndForwardRestoresTheExactInkRows() = runBlocking {
        val pageId = newPage()
        fun stroke(id: String, color: Int) = InkStrokeEntity(
            id = id,
            pageId = pageId,
            seq = 0,
            brushFamily = "pressure-pen",
            brushVersion = 1,
            sizeDp = 3f,
            colorArgb = color,
            colorFollowsTheme = false,
            epsilon = 0.1f,
            stabilization = 1,
            minX = 1f,
            minY = 2f,
            maxX = 3f,
            maxY = 4f,
            points = byteArrayOf(1, 2, 3),
            enc = "test/1",
            createdAt = now,
        )

        repository.addStroke(stroke("ink-a", 0xFF112233.toInt()))
        now += NotesRepository.REVISION_CHECKPOINT_INTERVAL_MS
        repository.addStroke(stroke("ink-b", 0xFF445566.toInt()))
        val revisionWithA = repository.revisionHistory(pageId).first()
        repository.setInkColors(
            mapOf("ink-a" to com.vivenotes.data.db.StrokeColor(0xFF778899.toInt(), false)),
        )

        repository.restoreRevision(pageId, revisionWithA.id)

        assertEquals(listOf("ink-a"), repository.inkFor(pageId).map { it.id })
        assertEquals(0xFF112233.toInt(), repository.inkFor(pageId).single().colorArgb)
        val revisionWithBoth = repository.revisionHistory(pageId).first { revision ->
            revision.id != revisionWithA.id && revision.createdAt >= now
        }

        repository.restoreRevision(pageId, revisionWithBoth.id)

        assertEquals(listOf("ink-a", "ink-b"), repository.inkFor(pageId).map { it.id })
        assertEquals(0xFF778899.toInt(), repository.inkFor(pageId).first().colorArgb)
    }

    @Test
    fun restoringInkAlsoRestoresItsActiveReplayOperations() = runBlocking {
        val pageId = newPage()
        val stroke = InkStrokeEntity(
            id = "ink-a",
            pageId = pageId,
            seq = 0,
            brushFamily = "pressure-pen",
            brushVersion = 1,
            sizeDp = 3f,
            colorArgb = 0xFF112233.toInt(),
            colorFollowsTheme = false,
            epsilon = 0.1f,
            stabilization = 1,
            minX = 1f,
            minY = 2f,
            maxX = 3f,
            maxY = 4f,
            points = byteArrayOf(1),
            enc = "test/1",
            createdAt = now,
        )
        repository.addStroke(stroke)
        repository.addPartialErase(
            InkEraseEntity(
                id = "erase-a",
                pageId = pageId,
                mode = EraserMode.Normal,
                sizeDp = 12f,
                points = byteArrayOf(2),
                enc = "test/1",
                createdAt = now + 1,
            ),
            listOf(stroke.id),
        )
        now += NotesRepository.REVISION_CHECKPOINT_INTERVAL_MS
        repository.addStroke(stroke.copy(id = "ink-b", createdAt = now))
        val revisionWithErase = repository.revisionHistory(pageId).first()

        now += 1
        repository.setPartialEraseActive("erase-a", active = false)
        repository.restoreRevision(pageId, revisionWithErase.id)

        assertEquals(listOf("erase-a"), repository.partialErasesFor(pageId).map { it.erase.id })
        val forward = repository.revisionHistory(pageId).first { it.createdAt == now }

        repository.restoreRevision(pageId, forward.id)

        assertTrue(repository.partialErasesFor(pageId).isEmpty())
        assertEquals(listOf("ink-a", "ink-b"), repository.inkFor(pageId).map { it.id })
    }

    @Test
    fun aCorruptRevisionIsReportedAndNeverRestored() = runBlocking {
        val pageId = newPage()
        repository.saveDoc(
            pageId,
            PageDoc(outlines = listOf(Outline.Text(id = "text", blocks = listOf(Block.of("kept"))))),
        )
        val revision = repository.revisionHistory(pageId).single()
        db.openHelper.writableDatabase.execSQL(
            "UPDATE page_revisions SET payload = X'00' WHERE id = ?",
            arrayOf(revision.id),
        )

        val load = repository.loadRevision(pageId, revision.id)

        assertTrue(load is PageRevisionLoad.Unreadable)
        assertEquals("kept", ((repository.loadDoc(pageId) as PageLoad.Loaded).doc.plainText()))
    }

    @Test
    fun corruptRevisionInkIsReportedWithoutChangingCurrentInk() = runBlocking {
        val pageId = newPage()
        repository.addStroke(
            InkStrokeEntity(
                id = "kept-ink",
                pageId = pageId,
                seq = 0,
                brushFamily = "pressure-pen",
                brushVersion = 1,
                sizeDp = 3f,
                colorArgb = 0xFF112233.toInt(),
                colorFollowsTheme = false,
                epsilon = 0.1f,
                stabilization = 1,
                minX = 1f,
                minY = 2f,
                maxX = 3f,
                maxY = 4f,
                points = byteArrayOf(1),
                enc = "test/1",
                createdAt = now,
            ),
        )
        val revision = repository.revisionHistory(pageId).single()
        db.openHelper.writableDatabase.execSQL(
            "UPDATE page_revisions SET inkPayload = X'00' WHERE id = ?",
            arrayOf(revision.id),
        )

        val load = repository.loadRevision(pageId, revision.id)
        val restore = repository.restoreRevision(pageId, revision.id)

        assertTrue(load is PageRevisionLoad.Unreadable)
        assertTrue(restore is PageRevisionLoad.Unreadable)
        assertEquals(listOf("kept-ink"), repository.inkFor(pageId).map { it.id })
    }

    @Test
    fun revisionHistoryIsBoundedPerPage() = runBlocking {
        val pageId = newPage()
        repeat(NotesRepository.MAX_REVISIONS_PER_PAGE + 5) { index ->
            repository.saveDoc(
                pageId,
                PageDoc(
                    outlines = listOf(
                        Outline.Text(id = "text", blocks = listOf(Block.of(index.toString()))),
                    ),
                ),
            )
            now += NotesRepository.REVISION_CHECKPOINT_INTERVAL_MS
        }

        val history = repository.revisionHistory(pageId)

        assertEquals(NotesRepository.MAX_REVISIONS_PER_PAGE, history.size)
        assertEquals(
            (NotesRepository.MAX_REVISIONS_PER_PAGE + 3).toString(),
            (repository.loadRevision(pageId, history.first().id) as PageRevisionLoad.Loaded)
                .doc
                .plainText(),
        )
    }

    @Test
    fun aFreshInstallSeedsTheBundledTabletInkAsPageTwo() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = StarterInkPageFixture.load(context)
        repository = NotesRepository(db, starterInkPage = fixture)

        repository.seedIfEmpty()

        val pages = db.query(
            "SELECT id, title FROM pages WHERE deletedAt IS NULL ORDER BY sortIndex",
            emptyArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))
            }
        }
        assertEquals(listOf("Welcome", "Recognition Test"), pages.map(Pair<String, String>::second))

        val fixturePageId = pages[1].first
        val strokes = repository.inkFor(fixturePageId)
        val erases = repository.partialErasesFor(fixturePageId)
        val moves = repository.inkMovesFor(fixturePageId)
        assertEquals(433, strokes.size)
        assertEquals(87, erases.size)
        assertEquals(330, erases.sumOf { it.targets.size })
        assertEquals(19, moves.size)
        assertEquals(589, moves.sumOf { it.targets.size })
        assertTrue(strokes.all { it.pageId == fixturePageId && it.deletedAt == null })
        assertTrue(erases.all { it.erase.pageId == fixturePageId && it.erase.deletedAt == null })
        assertTrue(moves.all { it.move.pageId == fixturePageId && it.move.deletedAt == null })

        val sourcesBySequence = fixture.strokes.associateBy { it.seq }
        strokes.forEach { stored ->
            val source = sourcesBySequence.getValue(stored.seq)
            assertNotEquals("source ids must be remapped per install", source.id, stored.id)
            assertEquals(source.brushFamily, stored.brushFamily)
            assertEquals(source.sizeDp, stored.sizeDp)
            assertEquals(source.minX, stored.minX)
            assertEquals(source.minY, stored.minY)
            assertEquals(source.maxX, stored.maxX)
            assertEquals(source.maxY, stored.maxY)
            assertArrayEquals(source.pointsHex.hexBytesForTest(), stored.points)
        }
        fixture.erases.sortedBy { it.createdAt }.zip(erases).forEach { (source, stored) ->
            assertNotEquals("source erase ids must be remapped per install", source.id, stored.erase.id)
            assertEquals(source.mode, stored.erase.mode)
            assertEquals(source.sizeDp, stored.erase.sizeDp)
            assertEquals(source.createdAt, stored.erase.createdAt)
            assertArrayEquals(source.pointsHex.hexBytesForTest(), stored.erase.points)
            assertEquals(
                fixture.eraseTargets.count { it.eraseId == source.id },
                stored.targets.size,
            )
        }
        // The transforms decide where the ink sits, so a fixture that seeds the strokes but loses
        // these seeds a scattered page that still passes every stroke assertion above.
        fixture.moves.sortedBy { it.createdAt }.zip(moves).forEach { (source, stored) ->
            assertNotEquals("source move ids must be remapped per install", source.id, stored.move.id)
            assertEquals(source.dxDp, stored.move.dxDp)
            assertEquals(source.dyDp, stored.move.dyDp)
            assertEquals(source.scaleX, stored.move.scaleX)
            assertEquals(source.scaleY, stored.move.scaleY)
            assertEquals(source.anchorX, stored.move.anchorX)
            assertEquals(source.anchorY, stored.move.anchorY)
            assertEquals(source.createdAt, stored.move.createdAt)
            assertArrayEquals(source.pointsHex.hexBytesForTest(), stored.move.points)
            assertEquals(
                fixture.moveTargets.count { it.moveId == source.id },
                stored.targets.size,
            )
        }

        val load = repository.loadDoc(fixturePageId)
        assertTrue(load is PageLoad.Loaded)
        assertTrue((load as PageLoad.Loaded).doc.plainText().isBlank())
    }

    /** Deletion is soft, so a future sync can replicate it rather than silently resurrecting rows. */
    @Test
    fun deletingAPageLeavesATombstone() = runBlocking {
        val notebookId = repository.createNotebook("nb")
        val sectionId = repository.createSection(notebookId, "sec")
        val pageId = repository.createPage(sectionId, "doomed")

        repository.deletePage(pageId)

        val rows = db.query("SELECT deletedAt FROM pages WHERE id = ?", arrayOf(pageId))
        rows.use {
            assertTrue("the row was hard-deleted", it.moveToFirst())
            assertTrue("deletedAt was not stamped", !it.isNull(0))
        }
    }

    @Test
    fun recoveryFollowsHierarchyRootsWithoutResurrectingOlderDeletes() = runBlocking {
        val notebookId = repository.createNotebook("Recovery notebook")
        val deletedSection = repository.createSection(notebookId, "Deleted section")
        repository.createPage(deletedSection, "Live child")
        val olderDeletedPage = repository.createPage(deletedSection, "Older deleted page")
        repository.saveDoc(
            olderDeletedPage,
            PageDoc(
                outlines = listOf(
                    Outline.Text(id = "kept", blocks = listOf(Block.of("recover me"))),
                ),
            ),
        )
        val liveSection = repository.createSection(notebookId, "Live section")
        repository.createPage(liveSection, "Live page")

        repository.deletePage(olderDeletedPage)
        repository.deleteSection(deletedSection)
        repository.deleteNotebook(notebookId)

        val notebookRoot = repository.observeDeletedItems().first().single()
        assertEquals(DeletedItemKind.Notebook, notebookRoot.key.kind)
        assertEquals(1, notebookRoot.sectionCount)
        assertEquals(1, notebookRoot.pageCount)

        assertTrue(repository.restoreDeletedItem(notebookRoot.key))
        val sectionRoot = repository.observeDeletedItems().first().single()
        assertEquals(DeletedItemKind.Section, sectionRoot.key.kind)
        assertEquals(1, sectionRoot.pageCount)

        assertTrue(repository.restoreDeletedItem(sectionRoot.key))
        val pageRoot = repository.observeDeletedItems().first().single()
        assertEquals(DeletedItemKind.Page, pageRoot.key.kind)
        assertEquals(olderDeletedPage, pageRoot.key.id)

        assertTrue(repository.restoreDeletedItem(pageRoot.key))
        assertTrue(repository.observeDeletedItems().first().isEmpty())
        val restored = repository.loadDoc(olderDeletedPage)
        assertTrue(restored is PageLoad.Loaded)
        assertEquals("recover me", (restored as PageLoad.Loaded).doc.plainText())
    }

    @Test
    fun childRestoreIsGuardedWhileItsParentIsDeleted() = runBlocking {
        val notebookId = repository.createNotebook("Notebook")
        val sectionId = repository.createSection(notebookId, "Section")
        val pageId = repository.createPage(sectionId, "Page")
        val pageKey = DeletedItemKey(pageId, DeletedItemKind.Page)
        repository.deletePage(pageId)
        repository.deleteNotebook(notebookId)

        assertTrue(!repository.restoreDeletedItem(pageKey))
        assertTrue(db.notebookDao().byId(notebookId)!!.deletedAt != null)
        assertTrue(db.pageDao().byId(pageId)!!.deletedAt != null)
    }

    @Test
    fun reorderingPagesRenumbersThemDenselyFromZero() = runBlocking {
        val sectionId = newSection()
        val ids = List(4) { repository.createPage(sectionId, "page $it") }

        repository.reorderPages(sectionId, listOf(ids[3], ids[0], ids[2], ids[1]))

        assertEquals(listOf(ids[3], ids[0], ids[2], ids[1]), db.pageIdsIn(sectionId))
        assertEquals(listOf(0, 1, 2, 3), db.sortIndicesIn("pages", "sectionId", sectionId))
    }

    /**
     * The list a finger let go of is not necessarily the list the table holds. A page created while
     * the drag was still running must not be renumbered on top of one that was.
     */
    @Test
    fun reorderingKeepsPagesTheCallerNeverSaw() = runBlocking {
        val sectionId = newSection()
        val known = List(3) { repository.createPage(sectionId, "page $it") }
        val arrivedMidDrag = repository.createPage(sectionId, "late")

        repository.reorderPages(sectionId, listOf(known[2], known[0], known[1]))

        assertEquals(
            "the unseen page should keep its place at the end, not collide",
            listOf(known[2], known[0], known[1], arrivedMidDrag),
            db.pageIdsIn(sectionId),
        )
        assertEquals(listOf(0, 1, 2, 3), db.sortIndicesIn("pages", "sectionId", sectionId))
    }

    /** A deleted page is gone from the order even if the caller was still showing it. */
    @Test
    fun reorderingIgnoresIdsThatAreNoLongerLive() = runBlocking {
        val sectionId = newSection()
        val ids = List(3) { repository.createPage(sectionId, "page $it") }
        repository.deletePage(ids[1])

        repository.reorderPages(sectionId, listOf(ids[1], ids[2], ids[0]))

        assertEquals(listOf(ids[2], ids[0]), db.pageIdsIn(sectionId))
    }

    /**
     * `updatedAt` is what the page list shows as "date modified" and what `PageSort.Recent` orders
     * by, so moving a row must not touch it. See `PageDao.setSortIndex`.
     */
    @Test
    fun reorderingPagesLeavesTheirModifiedTimeAlone() = runBlocking {
        val sectionId = newSection()
        val ids = List(2) { repository.createPage(sectionId, "page $it") }
        val before = db.updatedAtOf(ids[0])

        now += 60_000
        repository.reorderPages(sectionId, listOf(ids[1], ids[0]))

        assertEquals(before, db.updatedAtOf(ids[0]))
    }

    @Test
    fun reorderingSectionsRenumbersWithinOneNotebook() = runBlocking {
        val notebookId = repository.createNotebook("nb")
        val other = repository.createNotebook("untouched")
        val otherSection = repository.createSection(other, "elsewhere")
        val ids = List(3) { repository.createSection(notebookId, "sec $it") }

        repository.reorderSections(notebookId, listOf(ids[2], ids[1], ids[0]))

        assertEquals(listOf(ids[2], ids[1], ids[0]), db.sectionIdsIn(notebookId))
        assertEquals(listOf(0, 1, 2), db.sortIndicesIn("sections", "notebookId", notebookId))
        assertEquals(listOf(otherSection), db.sectionIdsIn(other))
    }

    private suspend fun newSection(): String =
        repository.createSection(repository.createNotebook("nb"), "sec")
}

private fun NotesDatabase.pageIdsIn(sectionId: String): List<String> =
    idsFrom("SELECT id FROM pages WHERE sectionId = ? AND deletedAt IS NULL ORDER BY sortIndex", sectionId)

private fun NotesDatabase.sectionIdsIn(notebookId: String): List<String> =
    idsFrom("SELECT id FROM sections WHERE notebookId = ? AND deletedAt IS NULL ORDER BY sortIndex", notebookId)

private fun NotesDatabase.idsFrom(sql: String, argument: String): List<String> =
    query(sql, arrayOf(argument)).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

private fun NotesDatabase.sortIndicesIn(table: String, parent: String, id: String): List<Int> =
    query(
        "SELECT sortIndex FROM $table WHERE $parent = ? AND deletedAt IS NULL ORDER BY sortIndex",
        arrayOf(id),
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getInt(0)) }
    }

private fun NotesDatabase.updatedAtOf(pageId: String): Long =
    query("SELECT updatedAt FROM pages WHERE id = ?", arrayOf(pageId)).use { cursor ->
        cursor.moveToFirst()
        cursor.getLong(0)
    }

private fun String.hexBytesForTest(): ByteArray = ByteArray(length / 2) { index ->
    val offset = index * 2
    ((this[offset].digitToInt(16) shl 4) or this[offset + 1].digitToInt(16)).toByte()
}
