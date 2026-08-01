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
}
