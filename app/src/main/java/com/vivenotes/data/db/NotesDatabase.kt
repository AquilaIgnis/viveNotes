package com.vivenotes.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * The app's database.
 *
 * **Version 1 is a consolidated baseline, not the first schema this project ever had.** Twenty-one
 * development migrations were collapsed into the entity definitions below once it was certain that
 * no installation outside this repository had ever run one: a migration is a cost paid to databases
 * that exist, and none did. Nothing about the tables changed in the collapse — `app/schemas/1.json`
 * describes what schema 22 described — but every earlier version is now unreachable, so a database
 * written by a pre-baseline build cannot be upgraded and has to be cleared instead.
 *
 * From here the ordinary rule applies again, and this is the last time it will not: every schema
 * change needs an explicit `Migration` registered in [create], the exported schema JSON committed
 * under `app/schemas/`, and a case in `MigrationTest` proving what happens to rows that already
 * exist. The migration's KDoc explains *why* each column is backfilled or deliberately left null —
 * the one-line `ALTER TABLE` never shows that, and it is the part that destroys notes when wrong.
 */
@Database(
    entities = [
        NotebookEntity::class,
        SectionEntity::class,
        PageEntity::class,
        PageContentEntity::class,
        PageRevisionEntity::class,
        InkStrokeEntity::class,
        InkEraseEntity::class,
        InkEraseTargetEntity::class,
        InkMoveEntity::class,
        InkMoveTargetEntity::class,
        AttachmentEntity::class,
        AttachmentTextEntity::class,
        InkTextEntity::class,
        InkTextGenerationEntity::class,
        LocalMetadataEntity::class,
        SyncStateEntity::class,
        SyncEntityStateEntity::class,
        SyncOutboxEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class NotesDatabase : RoomDatabase() {

    abstract fun notebookDao(): NotebookDao
    abstract fun sectionDao(): SectionDao
    abstract fun pageDao(): PageDao
    abstract fun pageContentDao(): PageContentDao
    abstract fun pageRevisionDao(): PageRevisionDao
    abstract fun inkStrokeDao(): InkStrokeDao
    abstract fun inkEraseDao(): InkEraseDao
    abstract fun inkMoveDao(): InkMoveDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun imageTextDao(): ImageTextDao
    abstract fun inkTextDao(): InkTextDao
    abstract fun localMetadataDao(): LocalMetadataDao
    abstract fun syncDao(): SyncDao
    abstract fun deletionRecoveryDao(): DeletionRecoveryDao
    abstract fun deletionPurgeDao(): DeletionPurgeDao

    companion object {

        /**
         * Installs the sync triggers on a database Room has just created.
         *
         * Triggers are not part of the entity schema, so Room neither creates nor validates them,
         * and `onCreate` is the only hook that fires on a database it has just built — which, since
         * the baseline, is every database this build opens. They stay dormant until `sync_state`
         * holds its singleton row, so an installation that has never connected an account queues
         * nothing. Anything that opens a `NotesDatabase` of its own — the transfer tests, the sync
         * tests — has to add this callback, or writes that should queue a push silently do not.
         */
        val SYNC_TRIGGER_CALLBACK = object : RoomDatabase.Callback() {
            override fun onCreate(connection: SQLiteConnection) {
                installSyncTriggers(connection)
            }
        }

        fun create(context: Context): NotesDatabase =
            Room.databaseBuilder(context, NotesDatabase::class.java, "notes.db")
                // No `addMigrations` yet: version 1 is the consolidated baseline, so there is no
                // older database to come from. The next schema change adds the call back here —
                // and never `fallbackToDestructiveMigration`, which answers a forgotten migration
                // by deleting the notes.
                .addCallback(SYNC_TRIGGER_CALLBACK)
                .build()

        private fun installSyncTriggers(connection: SQLiteConnection) {
            listOf(
                SyncedTable("notebook", "notebooks", "id"),
                SyncedTable("section", "sections", "id"),
                SyncedTable("page", "pages", "id"),
                SyncedTable("pageContent", "page_content", "pageId"),
                // Ink. The target tables get none: they are never an entity, they travel
                // inside their operation's payload, and they are written in the same transaction
                // as the operation whose insert already queued it.
                SyncedTable("inkStroke", "ink_strokes", "id"),
                SyncedTable("inkErase", "ink_erases", "id"),
                SyncedTable("inkMove", "ink_moves", "id"),
                // Attachments, and the one kind with no update trigger. Everything the protocol
                // carries about an attachment describes the bytes its id is the hash of, so the row
                // is immutable in every synced field. The only column that ever changes is
                // `refCount`, which is per-device reachability and deliberately not synced
                // (`viveCServer/memory/syncPlan.md` SD7), so an update trigger would re-push an
                // identical row every time a picture was pasted and every other device would pull
                // it back.
                SyncedTable("attachment", "attachments", "id", queueUpdates = false),
            ).forEach { (kind, table, entityIdColumn, queueUpdates) ->
                val events = if (queueUpdates) {
                    listOf("insert" to "INSERT", "update" to "UPDATE")
                } else {
                    listOf("insert" to "INSERT")
                }
                events.forEach { (suffix, event) ->
                    connection.execSQL(
                        """
                        CREATE TRIGGER IF NOT EXISTS sync_${table}_$suffix
                        AFTER $event ON $table
                        WHEN EXISTS (
                            SELECT 1 FROM sync_state
                            WHERE singleton = 0 AND applyingRemote = 0
                        )
                        BEGIN
                            INSERT INTO sync_outbox(kind, entityId, generation, changedAt)
                            VALUES(
                                '$kind',
                                NEW.$entityIdColumn,
                                1,
                                CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)
                            )
                            ON CONFLICT(kind, entityId) DO UPDATE
                            SET generation = generation + 1, changedAt = excluded.changedAt;
                        END
                        """.trimIndent(),
                    )
                }
            }
        }

        /**
         * One row of [installSyncTriggers]' table: which kind a table's rows are pushed as, where
         * the entity id lives on them, and whether an update to one is worth telling the server
         * about. A data class rather than a `Triple` because the fourth field is a boolean, and a
         * boolean in a tuple is unreadable at the call site.
         */
        private data class SyncedTable(
            val kind: String,
            val table: String,
            val entityIdColumn: String,
            val queueUpdates: Boolean = true,
        )
    }
}
