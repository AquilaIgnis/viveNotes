package com.vivenotes.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The committed schemas, every migration between them, and the table guarantees that live in SQL
 * rather than in Kotlin.
 *
 * [NotesDatabase] version 1 is a consolidated baseline, the twenty-one development migrations before
 * it having been collapsed into the entity definitions. Each schema change since puts its test here:
 * seed a database at the old version with `helper.createDatabase`, apply the migration through
 * `helper.runMigrationsAndValidate`, and assert on the rows that already existed. Room validates
 * the new shape by itself.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NotesDatabase::class.java,
    )

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        context.deleteDatabase(BASELINE_DB)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(BASELINE_DB)
    }

    /**
     * The newest committed schema is the schema this build expects.
     *
     * `createDatabase` builds a database from `app/schemas/<version>.json` and nothing else,
     * including the identity hash it writes into `room_master_table`; opening the real
     * [NotesDatabase] over that file makes Room compare that hash against the one compiled from the
     * entities. It therefore fails both ways round — an entity changed without re-exporting, or an
     * export never committed — and either would leave the next migration test unwritable, because
     * seeding an old version is exactly this call reading exactly that file.
     */
    @Test
    fun theNewestCommittedSchemaIsTheSchemaTheEntitiesCompileTo() {
        helper.createDatabase(BASELINE_DB, 2).close()

        val database = Room.databaseBuilder(context, NotesDatabase::class.java, BASELINE_DB)
            .addMigrations(NotesDatabase.MIGRATION_1_2)
            .addCallback(NotesDatabase.SYNC_TRIGGER_CALLBACK)
            .build()
        try {
            runBlocking { assertEquals(emptyList<String>(), database.attachmentDao().allIds()) }
        } finally {
            database.close()
        }
    }

    /**
     * Version 2 adds the picture release and the saved-version picture index.
     *
     * Every existing picture comes through unreleased, because nothing had ever been released, and
     * every existing saved version comes through unindexed rather than as "places nothing", which
     * would let the first sweep delete a file a version still shows. The release trigger exists
     * afterwards — `onCreate` never runs again for a database that already exists — and fires on a
     * release but not on the `refCount` updates every import makes.
     */
    @Test
    fun versionOneMigratesWithEveryPictureUnreleasedAndEveryVersionUnindexed() {
        helper.createDatabase(BASELINE_DB, 1).apply {
            execSQL(
                "INSERT INTO attachments(id, mimeType, pixelWidth, pixelHeight, byteCount, refCount, createdAt) " +
                    "VALUES('sha-one', 'image/webp', 8, 8, 10, 1, 42)",
            )
            execSQL("INSERT INTO sync_state(singleton, accountId, cursor, applyingRemote) VALUES(0, 'account', 0, 0)")
            close()
        }

        val migrated = helper.runMigrationsAndValidate(BASELINE_DB, 2, true, NotesDatabase.MIGRATION_1_2)
        try {
            migrated.query("SELECT releasedAt FROM attachments WHERE id = 'sha-one'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue("an existing picture starts unreleased", cursor.isNull(0))
            }

            migrated.execSQL("UPDATE attachments SET refCount = 2 WHERE id = 'sha-one'")
            assertEquals(0, outboxRows(migrated))

            migrated.execSQL("UPDATE attachments SET releasedAt = 99 WHERE id = 'sha-one'")
            assertEquals(1, outboxRows(migrated))
        } finally {
            migrated.close()
        }
    }

    private fun outboxRows(database: SupportSQLiteDatabase): Int =
        database.query("SELECT COUNT(*) FROM sync_outbox WHERE kind = 'attachment' AND entityId = 'sha-one'")
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }

    /**
     * A double release must not drive an attachment's reference count negative.
     *
     * A negative count reads as "sweepable" everywhere it is asked, so the floor is what stands
     * between a mistimed release and deleting the bytes of a picture a page still shows. It is
     * `MAX(refCount - 1, 0)` in one DAO query and asserting it needs a real table.
     */
    @Test
    fun anAttachmentStartsUnreferencedAndCannotBeReleasedBelowZero() = runBlocking {
        withDatabase { database ->
            val attachments = database.attachmentDao()
            attachments.insert(
                AttachmentEntity(
                    id = "sha-one",
                    mimeType = "image/jpeg",
                    pixelWidth = 100,
                    pixelHeight = 80,
                    byteCount = 2048,
                    createdAt = 42L,
                ),
            )
            assertEquals(0, attachments.byId("sha-one")?.refCount)

            attachments.retain("sha-one")
            attachments.release("sha-one")
            attachments.release("sha-one")

            assertEquals(0, attachments.byId("sha-one")?.refCount)
        }
    }

    /**
     * A picture's recognized text dies with the picture.
     *
     * `attachment_text` is derived from bytes that an `AttachmentStore.release` can delete for
     * good, and the foreign key is the only thing that removes the reading with them —
     * `ImageTextDao.deleteOrphans` exists to notice if it ever stops firing, and this is where it
     * is proved to fire at all.
     */
    @Test
    fun deletingAnAttachmentDeletesItsRecognizedText() = runBlocking {
        withDatabase { database ->
            database.attachmentDao().insert(
                AttachmentEntity(
                    id = "sha-one",
                    mimeType = "image/webp",
                    pixelWidth = 800,
                    pixelHeight = 600,
                    byteCount = 1024,
                    createdAt = 10L,
                ),
            )
            database.imageTextDao().upsert(
                AttachmentTextEntity(
                    attachmentId = "sha-one",
                    text = "hello",
                    lineCount = 1,
                    confidence = 0.9f,
                    engine = "ppocrv5-en/1",
                    status = ImageTextStatus.Read,
                    durationMs = 42L,
                    updatedAt = 10L,
                ),
            )
            assertEquals(1, database.imageTextDao().byIds(listOf("sha-one")).size)

            database.attachmentDao().deleteIfUnreferenced("sha-one")

            assertEquals(emptyList<AttachmentTextEntity>(), database.imageTextDao().byIds(listOf("sha-one")))
        }
    }

    private inline fun withDatabase(block: (NotesDatabase) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java)
            .addCallback(NotesDatabase.SYNC_TRIGGER_CALLBACK)
            .build()
        try {
            block(database)
        } finally {
            database.close()
        }
    }

    private companion object {
        const val BASELINE_DB = "baseline-schema.db"
    }
}
