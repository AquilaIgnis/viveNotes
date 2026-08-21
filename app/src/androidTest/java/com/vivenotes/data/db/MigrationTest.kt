package com.vivenotes.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The schema baseline, and the place every future migration gets its case.
 *
 * **There is no migration to exercise yet.** [NotesDatabase] version 1 is a consolidated baseline:
 * the twenty-one development migrations before it were collapsed into the entity definitions once
 * it was certain that no database outside this repository had ever run one. What survives from the
 * suite they had is what nothing else covers — that the schema the entities compile to is the
 * schema committed under `app/schemas/`, and the two table guarantees that live in SQL rather than
 * in Kotlin and would otherwise be believed rather than known.
 *
 * The next schema change puts its test back here, shaped like the ones that were removed: seed a
 * database at the old version with `helper.createDatabase`, apply the migration through
 * `helper.runMigrationsAndValidate`, and assert on the rows that already existed. Room validates
 * the new shape by itself; what no one can see by reading a one-line `ALTER TABLE` is what became
 * of the rows, and that is the half that silently destroys notes.
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
     * The committed baseline is the schema this build expects.
     *
     * `createDatabase` builds a database from `app/schemas/1.json` and nothing else, including the
     * identity hash it writes into `room_master_table`; opening the real [NotesDatabase] over that
     * file makes Room compare that hash against the one compiled from the entities. It therefore
     * fails both ways round — an entity changed without re-exporting, or an export never committed
     * — and either would leave the first real migration test unwritable, because seeding an old
     * version is exactly this call reading exactly that file.
     */
    @Test
    fun theCommittedBaselineIsTheSchemaTheEntitiesCompileTo() {
        helper.createDatabase(BASELINE_DB, 1).close()

        val database = Room.databaseBuilder(context, NotesDatabase::class.java, BASELINE_DB)
            .addCallback(NotesDatabase.SYNC_TRIGGER_CALLBACK)
            .build()
        try {
            runBlocking { assertEquals(emptyList<String>(), database.attachmentDao().allIds()) }
        } finally {
            database.close()
        }
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
