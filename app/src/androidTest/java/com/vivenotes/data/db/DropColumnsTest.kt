package com.vivenotes.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Both ways of dropping a column, on whatever SQLite this device has.
 *
 * The rebuild is what Android 12 and 13 get, so it is forced here on every device rather than only
 * run where `DROP COLUMN` is missing; the native statement is checked against it where it exists.
 */
@RunWith(AndroidJUnit4::class)
class DropColumnsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(context.cacheDir, "drop-columns.db")
    private lateinit var db: SQLiteDatabase

    @Before
    fun setUp() {
        file.delete()
        db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(
            "CREATE TABLE `parents` (`id` TEXT NOT NULL, `name` TEXT NOT NULL COLLATE NOCASE, " +
                "`local` INTEGER, `rank` INTEGER NOT NULL DEFAULT 5, PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX `index_parents_name` ON `parents` (`name`)")
        db.execSQL(
            "CREATE TABLE `children` (`id` TEXT NOT NULL, `parentId` TEXT NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`parentId`) REFERENCES `parents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL("INSERT INTO parents (id, name, local, rank) VALUES ('a', 'Alpha', 1, 2), ('b', 'beta', NULL, 3)")
        db.execSQL("INSERT INTO children (id, parentId) VALUES ('c1', 'a'), ('c2', 'a'), ('c3', 'b')")
    }

    @After
    fun tearDown() {
        db.close()
        file.delete()
    }

    /**
     * Dropping a parent table with foreign keys on would delete every child through the cascade, so
     * the rebuild only works if it switches them off, and leaving them off would be its own bug.
     */
    @Test
    fun theRebuildKeepsRowsKeysIndexesAndEveryChild() {
        db.dropColumns("parents", setOf("local"), rebuild = true)

        assertEquals(listOf("id", "name", "rank"), columns("parents"))
        assertEquals("the primary key survives", listOf(1, 0, 0), pragma("PRAGMA table_info(`parents`)", 5).map(String::toInt))
        assertEquals("the default survives", "5", pragma("PRAGMA table_info(`parents`)", 4)[2])
        assertEquals(listOf("a|Alpha|2", "b|beta|3"), rows("SELECT id || '|' || name || '|' || rank FROM parents ORDER BY id"))
        assertEquals("no child was cascaded away", 3, rows("SELECT id FROM children").size)
        assertEquals(listOf("index_parents_name"), rows("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'parents' AND sql IS NOT NULL"))
        assertEquals("the collation survives", listOf("a"), rows("SELECT id FROM parents WHERE name = 'ALPHA'"))
        assertEquals("foreign keys are back on", listOf("1"), rows("PRAGMA foreign_keys"))
        assertEquals("and still enforced", 0, rows("PRAGMA foreign_key_check").size)
        db.execSQL("DELETE FROM parents WHERE id = 'b'")
        assertEquals("the cascade still runs", listOf("c1", "c2"), rows("SELECT id FROM children ORDER BY id"))
    }

    @Test
    fun theNativeStatementLeavesTheSameTable() {
        assumeTrue("this SQLite has no DROP COLUMN", db.canDropColumns())
        db.dropColumns("parents", setOf("local"), rebuild = false)

        assertEquals(listOf("id", "name", "rank"), columns("parents"))
        assertEquals(3, rows("SELECT id FROM children").size)
        assertEquals(0, rows("PRAGMA foreign_key_check").size)
    }

    @Test
    fun theVersionCheckMatchesWhatThisSqliteAccepts() {
        val accepted = runCatching { db.execSQL("ALTER TABLE `parents` DROP COLUMN `local`") }.isSuccess
        assertEquals(accepted, db.canDropColumns())
    }

    private fun columns(table: String): List<String> = pragma("PRAGMA table_info(`$table`)", 1)

    private fun pragma(sql: String, column: Int): List<String> = db.rawQuery(sql, null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(column) ?: "null") }
    }

    private fun rows(sql: String): List<String> = pragma(sql, 0)
}
