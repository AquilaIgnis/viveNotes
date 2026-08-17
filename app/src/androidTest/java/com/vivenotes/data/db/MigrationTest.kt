package com.vivenotes.data.db

import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.testing.MigrationTestHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Rule
import java.io.File

/**
 * Exercises the migration SQL against a v1-shaped table.
 *
 * A migration that drops or fails to backfill a column silently destroys notes, and there is no
 * way to notice by reading the one-line `ALTER TABLE` — the interesting part is what happens to
 * rows that already exist.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NotesDatabase::class.java,
    )

    private lateinit var file: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.cacheDir, "migration-test.db")
        file.delete()
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun migration1To2AddsFormatAndBackfillsExistingRows() {
        val driver = AndroidSQLiteDriver()

        driver.open(file.absolutePath).use { connection ->
            // The v1 shape of page_content, before documents recorded their codec.
            connection.execSQL(
                """
                CREATE TABLE page_content (
                    pageId TEXT NOT NULL PRIMARY KEY,
                    docJson TEXT NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """INSERT INTO page_content VALUES ('p1', '{"outlines":[]}', 42)""",
            )

            NotesDatabase.MIGRATION_1_2.migrate(connection)

            connection.prepare("SELECT pageId, docJson, updatedAt, format FROM page_content").use {
                assertEquals(true, it.step())
                assertEquals("p1", it.getText(0))
                assertEquals("""{"outlines":[]}""", it.getText(1))
                assertEquals(42L, it.getLong(2))
                assertEquals("existing rows must be attributed to the codec that wrote them", "json/1", it.getText(3))
            }
        }
    }

    @Test
    fun migration3To5StoresTypedEraseMasksAndCascadesTheirTargets() {
        val driver = AndroidSQLiteDriver()

        driver.open(file.absolutePath).use { connection ->
            connection.execSQL("PRAGMA foreign_keys = ON")
            // Only the referenced keys are relevant to this migration's two new tables.
            connection.execSQL("CREATE TABLE pages (id TEXT NOT NULL PRIMARY KEY)")
            connection.execSQL("CREATE TABLE ink_strokes (id TEXT NOT NULL PRIMARY KEY)")

            NotesDatabase.MIGRATION_3_4.migrate(connection)

            connection.execSQL("INSERT INTO pages VALUES ('page')")
            connection.execSQL("INSERT INTO ink_strokes VALUES ('stroke')")
            connection.execSQL(
                "INSERT INTO ink_erases VALUES ('erase', 'page', 18.0, X'0102', 'ink/androidx1', 42)",
            )
            connection.execSQL("INSERT INTO ink_erase_targets VALUES ('erase', 'stroke')")

            NotesDatabase.MIGRATION_4_5.migrate(connection)

            connection.prepare(
                """
                SELECT ink_erase_targets.strokeId, ink_erases.mode
                FROM ink_erase_targets
                JOIN ink_erases ON ink_erases.id = ink_erase_targets.eraseId
                WHERE eraseId = 'erase'
                """.trimIndent(),
            ).use {
                assertEquals(true, it.step())
                assertEquals("stroke", it.getText(0))
                assertEquals("Normal", it.getText(1))
            }

            connection.execSQL("DELETE FROM ink_erases WHERE id = 'erase'")
            connection.prepare("SELECT COUNT(*) FROM ink_erase_targets").use {
                assertEquals(true, it.step())
                assertEquals(0L, it.getLong(0))
            }
        }
    }

    @Test
    fun migration5To6StoresLassoMovesAndCascadesTheirTargets() {
        val driver = AndroidSQLiteDriver()

        driver.open(file.absolutePath).use { connection ->
            connection.execSQL("PRAGMA foreign_keys = ON")
            connection.execSQL("CREATE TABLE pages (id TEXT NOT NULL PRIMARY KEY)")
            connection.execSQL("CREATE TABLE ink_strokes (id TEXT NOT NULL PRIMARY KEY)")

            NotesDatabase.MIGRATION_5_6.migrate(connection)

            connection.execSQL("INSERT INTO pages VALUES ('page')")
            connection.execSQL("INSERT INTO ink_strokes VALUES ('stroke')")
            connection.execSQL(
                "INSERT INTO ink_moves VALUES " +
                    "('move', 'page', 12.0, -4.0, X'0102', 'ink/lasso-f32le1', 42)",
            )
            connection.execSQL("INSERT INTO ink_move_targets VALUES ('move', 'stroke')")

            connection.prepare(
                """
                SELECT ink_move_targets.strokeId, ink_moves.dxDp, ink_moves.dyDp
                FROM ink_move_targets
                JOIN ink_moves ON ink_moves.id = ink_move_targets.moveId
                WHERE moveId = 'move'
                """.trimIndent(),
            ).use {
                assertEquals(true, it.step())
                assertEquals("stroke", it.getText(0))
                assertEquals(12.0, it.getDouble(1), 0.001)
                assertEquals(-4.0, it.getDouble(2), 0.001)
            }

            connection.execSQL("DELETE FROM ink_moves WHERE id = 'move'")
            connection.prepare("SELECT COUNT(*) FROM ink_move_targets").use {
                assertEquals(true, it.step())
                assertEquals(0L, it.getLong(0))
            }
        }
    }

    @Test
    fun migration6To7MakesExistingEraseAndMoveOperationsUndoableAndActive() {
        val driver = AndroidSQLiteDriver()

        driver.open(file.absolutePath).use { connection ->
            connection.execSQL(
                """
                CREATE TABLE ink_erases (
                    id TEXT NOT NULL PRIMARY KEY,
                    pageId TEXT NOT NULL,
                    mode TEXT NOT NULL,
                    sizeDp REAL NOT NULL,
                    points BLOB NOT NULL,
                    enc TEXT NOT NULL,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE TABLE ink_moves (
                    id TEXT NOT NULL PRIMARY KEY,
                    pageId TEXT NOT NULL,
                    dxDp REAL NOT NULL,
                    dyDp REAL NOT NULL,
                    points BLOB NOT NULL,
                    enc TEXT NOT NULL,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            connection.execSQL(
                "INSERT INTO ink_erases VALUES ('erase', 'page', 'Normal', 18.0, X'01', 'ink/androidx1', 41)",
            )
            connection.execSQL(
                "INSERT INTO ink_moves VALUES ('move', 'page', 4.0, 8.0, X'02', 'ink/lasso-f32le1', 42)",
            )

            NotesDatabase.MIGRATION_6_7.migrate(connection)

            connection.prepare(
                "SELECT COALESCE(deletedAt, -1) FROM ink_erases WHERE id = 'erase'",
            ).use {
                assertEquals(true, it.step())
                assertEquals("an existing erase did not remain active", -1L, it.getLong(0))
            }
            connection.prepare(
                "SELECT COALESCE(deletedAt, -1) FROM ink_moves WHERE id = 'move'",
            ).use {
                assertEquals(true, it.step())
                assertEquals("an existing move did not remain active", -1L, it.getLong(0))
            }
        }
    }

    @Test
    fun migration7To8LeavesExistingInkUngrouped() {
        val driver = AndroidSQLiteDriver()

        driver.open(file.absolutePath).use { connection ->
            connection.execSQL("CREATE TABLE ink_strokes (id TEXT NOT NULL PRIMARY KEY)")
            connection.execSQL("INSERT INTO ink_strokes VALUES ('stroke')")

            NotesDatabase.MIGRATION_7_8.migrate(connection)

            connection.prepare("SELECT groupId FROM ink_strokes WHERE id = 'stroke'").use {
                assertEquals(true, it.step())
                assertEquals(true, it.isNull(0))
            }
        }
    }

    @Test
    fun migration8To9KeepsExistingMovesAsTranslationOnly() {
        val driver = AndroidSQLiteDriver()

        driver.open(file.absolutePath).use { connection ->
            connection.execSQL(
                """
                CREATE TABLE ink_moves (
                    id TEXT NOT NULL PRIMARY KEY,
                    pageId TEXT NOT NULL,
                    dxDp REAL NOT NULL,
                    dyDp REAL NOT NULL,
                    points BLOB NOT NULL,
                    enc TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    deletedAt INTEGER
                )
                """.trimIndent(),
            )
            connection.execSQL(
                "INSERT INTO ink_moves VALUES ('move', 'page', 4.0, 8.0, X'01', 'ink/lasso-f32le1', 42, NULL)",
            )

            NotesDatabase.MIGRATION_8_9.migrate(connection)

            connection.prepare(
                "SELECT scaleX, scaleY, anchorX, anchorY FROM ink_moves WHERE id = 'move'",
            ).use {
                assertEquals(true, it.step())
                assertEquals(1.0, it.getDouble(0), 0.001)
                assertEquals(1.0, it.getDouble(1), 0.001)
                assertEquals(0.0, it.getDouble(2), 0.001)
                assertEquals(0.0, it.getDouble(3), 0.001)
            }
        }
    }

    @Test
    fun migration9To10AddsAttachmentsAndLeavesExistingContentAlone() {
        val driver = AndroidSQLiteDriver()

        driver.open(file.absolutePath).use { connection ->
            connection.execSQL(
                """
                CREATE TABLE page_content (
                    pageId TEXT NOT NULL PRIMARY KEY,
                    docJson TEXT NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    format TEXT NOT NULL DEFAULT 'json/1'
                )
                """.trimIndent(),
            )
            connection.execSQL(
                "INSERT INTO page_content VALUES ('page', '{\"outlines\":[]}', 42, 'json/1')",
            )

            NotesDatabase.MIGRATION_9_10.migrate(connection)

            // A pure create: nothing existed that could reference an attachment, so no document is
            // rewritten and no row is backfilled.
            connection.prepare("SELECT COUNT(*) FROM attachments").use {
                assertEquals(true, it.step())
                assertEquals(0, it.getLong(0).toInt())
            }
            connection.prepare("SELECT docJson FROM page_content WHERE pageId = 'page'").use {
                assertEquals(true, it.step())
                assertEquals("{\"outlines\":[]}", it.getText(0))
            }
        }
    }

    @Test
    fun anAttachmentStartsUnreferencedAndCannotBeReleasedBelowZero() {
        val driver = AndroidSQLiteDriver()

        driver.open(file.absolutePath).use { connection ->
            NotesDatabase.MIGRATION_9_10.migrate(connection)
            connection.execSQL(
                "INSERT INTO attachments VALUES ('abc', 'image/jpeg', 100, 80, 2048, 0, 42)",
            )

            // The guard that matters: a double release must not drive the count negative, because a
            // negative count reads as "sweepable" and would delete a file something still points at.
            connection.execSQL("UPDATE attachments SET refCount = MAX(refCount - 1, 0) WHERE id = 'abc'")
            connection.execSQL("UPDATE attachments SET refCount = MAX(refCount - 1, 0) WHERE id = 'abc'")

            connection.prepare("SELECT refCount FROM attachments WHERE id = 'abc'").use {
                assertEquals(true, it.step())
                assertEquals(0, it.getLong(0).toInt())
            }
        }
    }

    /**
     * Ink drawn before the automatic flag existed must come through as NULL, not as false.
     *
     * The distinction is the whole fix: false means "the user picked this colour, leave it alone",
     * and defaulting old rows to it would permanently freeze every stroke already on a page at the
     * colour the canvas happened to be when it was drawn — which is the bug. NULL means "never
     * recorded", which is what lets `automaticColorOr` infer it and let the ink follow the page.
     */
    @Test
    fun migration10To11LeavesExistingStrokesUnrecordedRatherThanDeliberate() {
        val driver = AndroidSQLiteDriver()

        driver.open(file.absolutePath).use { connection ->
            connection.execSQL(
                """
                CREATE TABLE ink_strokes (
                    id TEXT NOT NULL PRIMARY KEY,
                    pageId TEXT NOT NULL,
                    seq INTEGER NOT NULL,
                    brushFamily TEXT NOT NULL,
                    brushVersion INTEGER NOT NULL,
                    sizeDp REAL NOT NULL,
                    colorArgb INTEGER NOT NULL,
                    epsilon REAL NOT NULL,
                    stabilization INTEGER NOT NULL,
                    minX REAL NOT NULL,
                    minY REAL NOT NULL,
                    maxX REAL NOT NULL,
                    maxY REAL NOT NULL,
                    points BLOB NOT NULL,
                    enc TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    deletedAt INTEGER,
                    groupId TEXT
                )
                """.trimIndent(),
            )
            // -1 is 0xFFFFFFFF: what the automatic pen resolved to on a dark canvas, and what every
            // stroke drawn that way was stored as.
            connection.execSQL(
                "INSERT INTO ink_strokes VALUES ('s1', 'p1', 0, 'pressure-pen', 1, 2.0, -1, " +
                    "0.1, 0, 0.0, 0.0, 1.0, 1.0, X'00', 'v1', 42, NULL, NULL)",
            )

            NotesDatabase.MIGRATION_10_11.migrate(connection)

            connection.prepare(
                "SELECT colorArgb, colorFollowsTheme FROM ink_strokes WHERE id = 's1'",
            ).use {
                assertEquals(true, it.step())
                // The stored colour is untouched — the migration adds a column, it does not repaint
                // ink the user has already drawn.
                assertEquals(-1, it.getLong(0).toInt())
                assertEquals(true, it.isNull(1))
            }
        }
    }

    @Test
    fun migration11To12PreservesDocumentsAndMatchesTheExportedSchema() {
        helper.createDatabase(ROOM_MIGRATION_DB, 11).apply {
            execSQL(
                "INSERT INTO notebooks VALUES " +
                    "('notebook', 'Notebook', 1, 0, 1, 10, 10, NULL)",
            )
            execSQL(
                "INSERT INTO sections VALUES " +
                    "('section', 'notebook', 'Section', 1, 0, 10, 10, NULL)",
            )
            execSQL(
                "INSERT INTO pages VALUES " +
                    "('page', 'section', 'Page', 0, 'kept', 10, 10, NULL)",
            )
            execSQL(
                "INSERT INTO page_content VALUES " +
                    "('page', '{\"outlines\":[]}', 10, 'json/1')",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            ROOM_MIGRATION_DB,
            12,
            true,
            NotesDatabase.MIGRATION_11_12,
        ).use { migrated ->
            migrated.query("SELECT docJson FROM page_content WHERE pageId = 'page'").use {
                assertEquals(true, it.moveToFirst())
                assertEquals("{\"outlines\":[]}", it.getString(0))
            }
            migrated.query("SELECT COUNT(*) FROM page_revisions").use {
                assertEquals(true, it.moveToFirst())
                assertEquals(0L, it.getLong(0))
            }
        }
    }

    @Test
    fun migration12To13DropsPrereleaseDocumentOnlyRevisions() {
        helper.createDatabase(ROOM_MIGRATION_DB, 12).apply {
            execSQL(
                "INSERT INTO notebooks VALUES " +
                    "('notebook', 'Notebook', 1, 0, 1, 10, 10, NULL)",
            )
            execSQL(
                "INSERT INTO sections VALUES " +
                    "('section', 'notebook', 'Section', 1, 0, 10, 10, NULL)",
            )
            execSQL(
                "INSERT INTO pages VALUES " +
                    "('page', 'section', 'Page', 0, 'kept', 10, 10, NULL)",
            )
            execSQL(
                "INSERT INTO page_revisions VALUES " +
                    "('revision', 'page', 10, 'json/1', 'gzip/1', 2, 'abc', X'01')",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            ROOM_MIGRATION_DB,
            13,
            true,
            NotesDatabase.MIGRATION_12_13,
        ).use { migrated ->
            migrated.query("SELECT COUNT(*) FROM page_revisions").use {
                assertEquals(true, it.moveToFirst())
                assertEquals(0L, it.getLong(0))
            }
        }
    }

    @Test
    fun migration13To14AddsLocalMetadataWithoutChangingNotebooks() {
        helper.createDatabase(ROOM_MIGRATION_DB, 13).apply {
            execSQL(
                "INSERT INTO notebooks VALUES " +
                    "('notebook-uuid', 'Notebook', 1, 0, 1, 10, 10, NULL)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            ROOM_MIGRATION_DB,
            14,
            true,
            NotesDatabase.MIGRATION_13_14,
        ).use { migrated ->
            migrated.query("SELECT id FROM notebooks").use {
                assertEquals(true, it.moveToFirst())
                assertEquals("notebook-uuid", it.getString(0))
            }
            migrated.query("SELECT COUNT(*) FROM local_metadata").use {
                assertEquals(true, it.moveToFirst())
                assertEquals(0L, it.getLong(0))
            }
        }
    }

    @Test
    fun migration14To15AddsPictureTextKeyedByAttachment() {
        helper.createDatabase(ROOM_MIGRATION_DB, 14).apply {
            execSQL(
                "INSERT INTO attachments VALUES " +
                    "('sha-one', 'image/webp', 800, 600, 1024, 1, 10)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            ROOM_MIGRATION_DB,
            15,
            true,
            NotesDatabase.MIGRATION_14_15,
        ).use { migrated ->
            // Nothing is backfilled: every row is derived from pixels by a named engine, so the
            // first indexing pass is what fills this in.
            migrated.query("SELECT COUNT(*) FROM attachment_text").use {
                assertEquals(true, it.moveToFirst())
                assertEquals(0L, it.getLong(0))
            }
            migrated.execSQL(
                "INSERT INTO attachment_text VALUES " +
                    "('sha-one', 'hello', 1, 0.9, 'ppocrv5-en/1', 'Read', 42, 10)",
            )
            migrated.query("SELECT COUNT(*) FROM attachment_text").use {
                assertEquals(true, it.moveToFirst())
                assertEquals(1L, it.getLong(0))
            }
        }
    }

    /**
     * IO3, in the place it can actually be proved: recognized text must not outlive its picture.
     *
     * Run against the migrated database rather than a Room-built one so it covers the migration's
     * own `CREATE TABLE` — a cascade written into the entity but omitted from the migration SQL
     * would leave exactly this bug on every upgraded install and none of the fresh ones.
     */
    @Test
    fun deletingAnAttachmentDeletesItsRecognizedText() {
        helper.createDatabase(ROOM_MIGRATION_DB, 14).apply {
            execSQL(
                "INSERT INTO attachments VALUES " +
                    "('sha-one', 'image/webp', 800, 600, 1024, 0, 10)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            ROOM_MIGRATION_DB,
            15,
            true,
            NotesDatabase.MIGRATION_14_15,
        ).use { migrated ->
            migrated.execSQL("PRAGMA foreign_keys = ON")
            migrated.execSQL(
                "INSERT INTO attachment_text VALUES " +
                    "('sha-one', 'hello', 1, 0.9, 'ppocrv5-en/1', 'Read', 42, 10)",
            )
            migrated.execSQL("DELETE FROM attachments WHERE id = 'sha-one'")
            migrated.query("SELECT COUNT(*) FROM attachment_text").use {
                assertEquals(true, it.moveToFirst())
                assertEquals(0L, it.getLong(0))
            }
        }
    }

    @Test
    fun migration15To16AddsPageHandwritingCacheAndRaceGeneration() {
        helper.createDatabase(ROOM_MIGRATION_DB, 15).apply {
            execSQL(
                "INSERT INTO notebooks VALUES " +
                    "('notebook', 'Notebook', 0, 0, 1, 1, 1, NULL)",
            )
            execSQL(
                "INSERT INTO sections VALUES " +
                    "('section', 'notebook', 'Section', 0, 0, 1, 1, NULL)",
            )
            execSQL(
                "INSERT INTO pages VALUES " +
                    "('page', 'section', 'Page', 0, '', 1, 1, NULL)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            ROOM_MIGRATION_DB,
            16,
            true,
            NotesDatabase.MIGRATION_15_16,
        ).use { migrated ->
            migrated.execSQL("PRAGMA foreign_keys = ON")
            migrated.execSQL(
                "INSERT INTO ink_text VALUES " +
                    "('page', '[]', 0, 0, 'layout', 'ppocrv5-en-ink/1', 'Empty', 5, 10)",
            )
            migrated.execSQL("INSERT INTO ink_text_generation VALUES ('page', 3)")
            migrated.query("SELECT generation FROM ink_text_generation WHERE pageId = 'page'").use {
                assertEquals(true, it.moveToFirst())
                assertEquals(3L, it.getLong(0))
            }

            migrated.execSQL("DELETE FROM pages WHERE id = 'page'")
            migrated.query("SELECT COUNT(*) FROM ink_text").use {
                assertEquals(true, it.moveToFirst())
                assertEquals(0L, it.getLong(0))
            }
            migrated.query("SELECT COUNT(*) FROM ink_text_generation").use {
                assertEquals(true, it.moveToFirst())
                assertEquals(0L, it.getLong(0))
            }
        }
    }

    @Test
    fun migration16To17AddsDormantSyncTriggersAndGenerationSafeOutbox() {
        helper.createDatabase(ROOM_MIGRATION_DB, 16).apply {
            execSQL(
                "INSERT INTO notebooks VALUES " +
                    "('notebook', 'Before', 1, 0, 1, 10, 10, NULL)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            ROOM_MIGRATION_DB,
            17,
            true,
            NotesDatabase.MIGRATION_16_17,
        ).use { migrated ->
            // Migration does not infer whether a token held in another DataStore is still valid.
            migrated.query("SELECT COUNT(*) FROM sync_outbox").use {
                assertEquals(true, it.moveToFirst())
                assertEquals(0L, it.getLong(0))
            }

            migrated.execSQL(
                "INSERT INTO sync_state(singleton, accountId, cursor, applyingRemote) " +
                    "VALUES(0, 'account', 0, 0)",
            )
            migrated.execSQL("UPDATE notebooks SET name = 'One' WHERE id = 'notebook'")
            migrated.execSQL("UPDATE notebooks SET name = 'Two' WHERE id = 'notebook'")
            migrated.query(
                "SELECT kind, entityId, generation, changedAt FROM sync_outbox",
            ).use {
                assertEquals(true, it.moveToFirst())
                assertEquals("notebook", it.getString(0))
                assertEquals("notebook", it.getString(1))
                assertEquals(2L, it.getLong(2))
                assertTrue(it.getLong(3) > 0L)
            }

            // Remote application uses the same transaction-local switch and must not echo.
            migrated.execSQL("UPDATE sync_state SET applyingRemote = 1 WHERE singleton = 0")
            migrated.execSQL("UPDATE notebooks SET name = 'Remote' WHERE id = 'notebook'")
            migrated.query("SELECT generation FROM sync_outbox").use {
                assertEquals(true, it.moveToFirst())
                assertEquals(2L, it.getLong(0))
            }
        }
    }

    @Test
    fun migration17To18QueuesPageContentByItsPageId() {
        helper.createDatabase(ROOM_MIGRATION_DB, 17).apply {
            execSQL(
                "INSERT INTO notebooks VALUES " +
                    "('notebook', 'Notebook', 1, 0, 1, 10, 10, NULL)",
            )
            execSQL(
                "INSERT INTO sections VALUES " +
                    "('section', 'notebook', 'Section', 1, 0, 10, 10, NULL)",
            )
            execSQL(
                "INSERT INTO pages VALUES " +
                    "('page', 'section', 'Page', 0, '', 10, 10, NULL)",
            )
            execSQL("INSERT INTO page_content VALUES ('page', '{}', 10, 'json/1')")
            execSQL(
                "INSERT INTO sync_state(singleton, accountId, cursor, applyingRemote) " +
                    "VALUES(0, 'account', 0, 0)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            ROOM_MIGRATION_DB,
            18,
            true,
            NotesDatabase.MIGRATION_17_18,
        ).use { migrated ->
            migrated.query(
                "SELECT generation FROM sync_outbox WHERE kind = 'pageContent'",
            ).use {
                assertEquals(true, it.moveToFirst())
                assertEquals(1L, it.getLong(0))
            }

            migrated.execSQL("UPDATE page_content SET docJson = '{\"one\":1}' WHERE pageId = 'page'")
            migrated.execSQL("UPDATE page_content SET docJson = '{\"two\":2}' WHERE pageId = 'page'")
            migrated.query(
                "SELECT kind, entityId, generation FROM sync_outbox WHERE kind = 'pageContent'",
            ).use {
                assertEquals(true, it.moveToFirst())
                assertEquals("pageContent", it.getString(0))
                assertEquals("page", it.getString(1))
                assertEquals(3L, it.getLong(2))
            }
        }
    }

    companion object {
        private const val ROOM_MIGRATION_DB = "notes-room-migration-test"
    }
}
