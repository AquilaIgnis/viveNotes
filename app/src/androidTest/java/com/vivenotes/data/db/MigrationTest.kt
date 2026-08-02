package com.vivenotes.data.db

import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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
}
