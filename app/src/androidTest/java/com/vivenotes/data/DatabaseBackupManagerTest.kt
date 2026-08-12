package com.vivenotes.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DatabaseBackupManagerTest {

    private lateinit var root: File
    private lateinit var db: NotesDatabase
    private lateinit var repository: NotesRepository
    private lateinit var backups: DatabaseBackupManager
    private var now = 1_000_000L

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "database-backup-manager-test")
        root.deleteRecursively()
        root.mkdirs()
        db = Room.databaseBuilder(
            context,
            NotesDatabase::class.java,
            File(root, "source.db").absolutePath,
        ).allowMainThreadQueries().build()
        repository = NotesRepository(db, clock = { now })
        backups = DatabaseBackupManager(
            context = context,
            database = db,
            directory = File(root, "backups"),
            clock = { now },
            intervalMs = 1_000,
            maxBackups = 2,
        )
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    @Test
    fun vacuumIntoCreatesAValidatedSnapshotAndRotatesOldCopies() = runBlocking {
        val notebookId = repository.createNotebook("nb")
        val sectionId = repository.createSection(notebookId, "sec")
        val pageId = repository.createPage(sectionId)
        repository.saveDoc(
            pageId,
            PageDoc(outlines = listOf(Outline.Text(id = "text", blocks = listOf(Block.of("first"))))),
        )

        val first = backups.createIfDue(force = true)
        assertNotNull(first)
        assertEquals("first", textInBackup(first!!, pageId))
        assertNull("a fresh backup did not satisfy its interval", backups.createIfDue())

        now += 1_000
        repository.saveDoc(
            pageId,
            PageDoc(outlines = listOf(Outline.Text(id = "text", blocks = listOf(Block.of("second"))))),
        )
        val second = backups.createIfDue()
        assertEquals("second", textInBackup(second!!, pageId))

        now += 1_000
        val third = backups.createIfDue(force = true)
        assertNotNull(third)
        assertEquals(2, backups.validBackups().size)
        assertEquals(setOf(second.name, third!!.name), backups.validBackups().map { it.name }.toSet())
    }

    private fun textInBackup(file: File, pageId: String): String {
        val snapshot = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        return snapshot.use { database ->
            database.rawQuery(
                "SELECT docJson FROM page_content WHERE pageId = ?",
                arrayOf(pageId),
            ).use { cursor ->
                check(cursor.moveToFirst())
                val json = cursor.getString(0)
                val marker = "\"text\":\""
                json.substringAfter(marker).substringBefore('"')
            }
        }
    }
}
