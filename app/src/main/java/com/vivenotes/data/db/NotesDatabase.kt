package com.vivenotes.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

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
    version = 22,
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
         * Records which document codec wrote each row. Existing rows predate the column and were
         * all written as JSON, so they are backfilled with that codec's id rather than left null.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE page_content ADD COLUMN format TEXT NOT NULL DEFAULT 'json/1'",
                )
            }
        }

        /**
         * Adds the ink table. Nothing is backfilled — no build before this one could draw — so this
         * is a pure create, and a page with no ink simply has no rows.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ink_strokes (
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
                        FOREIGN KEY(pageId) REFERENCES pages(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ink_strokes_pageId ON ink_strokes(pageId)",
                )
            }
        }

        /** Adds replayable partial-erase masks and the strokes each gesture was allowed to affect. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ink_erases (
                        id TEXT NOT NULL PRIMARY KEY,
                        pageId TEXT NOT NULL,
                        sizeDp REAL NOT NULL,
                        points BLOB NOT NULL,
                        enc TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(pageId) REFERENCES pages(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ink_erases_pageId ON ink_erases(pageId)",
                )
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ink_erase_targets (
                        eraseId TEXT NOT NULL,
                        strokeId TEXT NOT NULL,
                        PRIMARY KEY(eraseId, strokeId),
                        FOREIGN KEY(eraseId) REFERENCES ink_erases(id) ON DELETE CASCADE,
                        FOREIGN KEY(strokeId) REFERENCES ink_strokes(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ink_erase_targets_strokeId " +
                        "ON ink_erase_targets(strokeId)",
                )
            }
        }

        /** Distinguishes ordinary subtraction masks from connected-component object erases. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE ink_erases ADD COLUMN mode TEXT NOT NULL DEFAULT 'Normal'",
                )
            }
        }

        /** Adds replayable lasso translations and the source rows each gesture selected. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ink_moves (
                        id TEXT NOT NULL PRIMARY KEY,
                        pageId TEXT NOT NULL,
                        dxDp REAL NOT NULL,
                        dyDp REAL NOT NULL,
                        points BLOB NOT NULL,
                        enc TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(pageId) REFERENCES pages(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ink_moves_pageId ON ink_moves(pageId)",
                )
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ink_move_targets (
                        moveId TEXT NOT NULL,
                        strokeId TEXT NOT NULL,
                        PRIMARY KEY(moveId, strokeId),
                        FOREIGN KEY(moveId) REFERENCES ink_moves(id) ON DELETE CASCADE,
                        FOREIGN KEY(strokeId) REFERENCES ink_strokes(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ink_move_targets_strokeId " +
                        "ON ink_move_targets(strokeId)",
                )
            }
        }

        /** Makes replayable erase and move operations reversible without hard-deleting sync rows. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE ink_erases ADD COLUMN deletedAt INTEGER")
                connection.execSQL("ALTER TABLE ink_moves ADD COLUMN deletedAt INTEGER")
            }
        }

        /** Adds durable logical grouping without changing immutable stroke geometry. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE ink_strokes ADD COLUMN groupId TEXT")
            }
        }

        /** Extends replayable lasso transforms with axis-aligned corner resizing. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE ink_moves ADD COLUMN scaleX REAL NOT NULL DEFAULT 1")
                connection.execSQL("ALTER TABLE ink_moves ADD COLUMN scaleY REAL NOT NULL DEFAULT 1")
                connection.execSQL("ALTER TABLE ink_moves ADD COLUMN anchorX REAL NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE ink_moves ADD COLUMN anchorY REAL NOT NULL DEFAULT 0")
            }
        }

        /**
         * Adds what is known about imported pictures. A pure create: `Outline.Image` has existed in
         * the model since the first schema, but nothing could produce one, so no row needs
         * backfilling and no document needs rewriting.
         *
         * The pixels are not in here — see [AttachmentEntity] for where they are and why.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS attachments (
                        id TEXT NOT NULL PRIMARY KEY,
                        mimeType TEXT NOT NULL,
                        pixelWidth INTEGER NOT NULL,
                        pixelHeight INTEGER NOT NULL,
                        byteCount INTEGER NOT NULL,
                        refCount INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Records whether a stroke's colour was the automatic one, so Switch Background can
         * re-resolve it instead of leaving white ink on white paper.
         *
         * **Deliberately left NULL rather than backfilled.** Every row written before this column
         * existed baked its resolved colour and recorded nothing about where that colour came from,
         * so no `UPDATE` here could do better than guess. The guess is worth making, but it belongs
         * at the point of use where it can be explained and changed —
         * [com.vivenotes.data.automaticColorOr] reads null as "infer from the colour" — rather than
         * written irreversibly over ink the user has already drawn.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE ink_strokes ADD COLUMN colorFollowsTheme INTEGER")
            }
        }

        /** Adds bounded, compressed page checkpoints without rewriting any current document. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS page_revisions (
                        id TEXT NOT NULL PRIMARY KEY,
                        pageId TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        format TEXT NOT NULL,
                        encoding TEXT NOT NULL,
                        byteCount INTEGER NOT NULL,
                        sha256 TEXT NOT NULL,
                        payload BLOB NOT NULL,
                        FOREIGN KEY(pageId) REFERENCES pages(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_page_revisions_pageId_createdAt " +
                        "ON page_revisions(pageId, createdAt)",
                )
            }
        }

        /** Adds exact active ink state; prerelease document-only history is not retained. */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE page_revisions ADD COLUMN " +
                        "inkFormat TEXT NOT NULL DEFAULT 'none/1'",
                )
                connection.execSQL(
                    "ALTER TABLE page_revisions ADD COLUMN " +
                        "inkEncoding TEXT NOT NULL DEFAULT 'none'",
                )
                connection.execSQL(
                    "ALTER TABLE page_revisions ADD COLUMN inkByteCount INTEGER NOT NULL DEFAULT 0",
                )
                connection.execSQL(
                    "ALTER TABLE page_revisions ADD COLUMN inkSha256 TEXT NOT NULL DEFAULT ''",
                )
                connection.execSQL(
                    "ALTER TABLE page_revisions ADD COLUMN inkPayload BLOB NOT NULL DEFAULT X''",
                )
                connection.execSQL("DELETE FROM page_revisions")
            }
        }

        /** Tracks installation-only UUIDs without putting device state into portable notebooks. */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS local_metadata (" +
                        "`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
                )
            }
        }

        /**
         * Adds the recognized-text cache for pictures.
         *
         * A pure create with **nothing backfilled**, and the reason is the point of the table: every
         * row is derived from an attachment's pixels by a named engine, so there is no prior value
         * to carry forward — the first indexing pass reads every picture that has one. A row can
         * therefore be absent (never tried), present with an old `engine` (stale, re-read) or
         * present and current, and those three states are the whole schedule.
         *
         * The foreign key is what keeps the cache from outliving its subject: `attachments` rows are
         * deleted by `AttachmentStore.release` once the last outline pointing at a picture is gone
         * for good, and this cascades with them.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS attachment_text (
                        attachmentId TEXT NOT NULL PRIMARY KEY,
                        text TEXT NOT NULL,
                        lineCount INTEGER NOT NULL,
                        confidence REAL NOT NULL,
                        engine TEXT NOT NULL,
                        status TEXT NOT NULL,
                        durationMs INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(attachmentId) REFERENCES attachments(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        /** Adds the regenerable, per-page handwriting recognition cache. */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ink_text (
                        pageId TEXT NOT NULL PRIMARY KEY,
                        regionsJson TEXT NOT NULL,
                        regionCount INTEGER NOT NULL,
                        confidence REAL NOT NULL,
                        layoutHash TEXT NOT NULL,
                        engine TEXT NOT NULL,
                        status TEXT NOT NULL,
                        durationMs INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(pageId) REFERENCES pages(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ink_text_generation (
                        pageId TEXT NOT NULL PRIMARY KEY,
                        generation INTEGER NOT NULL,
                        FOREIGN KEY(pageId) REFERENCES pages(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Adds the hierarchy sync cursor, per-row server versions, and durable local outbox.
         *
         * The triggers are dormant until `sync_state` has its singleton row. Connecting an existing
         * installation seeds the complete hierarchy explicitly, so migration itself does not guess
         * whether an account in a separate DataStore is still usable.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_state (
                        singleton INTEGER NOT NULL PRIMARY KEY,
                        accountId TEXT NOT NULL,
                        cursor INTEGER NOT NULL,
                        applyingRemote INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_entity_states (
                        kind TEXT NOT NULL,
                        entityId TEXT NOT NULL,
                        serverVersion INTEGER NOT NULL,
                        serverJson TEXT NOT NULL,
                        PRIMARY KEY(kind, entityId)
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_outbox (
                        kind TEXT NOT NULL,
                        entityId TEXT NOT NULL,
                        generation INTEGER NOT NULL,
                        changedAt INTEGER NOT NULL,
                        PRIMARY KEY(kind, entityId)
                    )
                    """.trimIndent(),
                )

                installSyncTriggers(connection)
            }
        }

        /** Adds document bodies to the durable sync outbox without changing their stored shape. */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(connection: SQLiteConnection) {
                installSyncTriggers(connection)
                // An account activated under schema 17 will take activateLocked's fast path after
                // upgrade, so it would never run the new page-content backfill there. Queue the
                // existing bodies during migration when sync is active; offline-only databases
                // remain untouched until the owner connects, exactly as in 16 -> 17.
                connection.execSQL(
                    """
                    INSERT OR IGNORE INTO sync_outbox(kind, entityId, generation, changedAt)
                    SELECT 'pageContent', pageId, 1, updatedAt FROM page_content
                    WHERE EXISTS (SELECT 1 FROM sync_state WHERE singleton = 0)
                    """.trimIndent(),
                )
            }
        }

        /**
         * Makes ink safe to receive from another device, and cheaper to write while doing it.
         *
         * Two changes, both prerequisites for `memory/inkSyncPlan.md` rather than features of it:
         *
         * **Operation targets stop being foreign keys.** `ink_erase_targets.strokeId` and
         * `ink_move_targets.strokeId` referenced `ink_strokes` with a cascade. Replicated, that is a
         * wedge: a target stroke recoloured after the operation was made carries a higher
         * `change_seq` and therefore lands in a *later* delta page than the operation naming it, and
         * a target already removed by the seven-day purge can never arrive at all — so the insert
         * fails, the transaction rolls back with the sync cursor uncommitted, and the device
         * re-pulls the same delta for ever. An unknown target id is instead inert, which is what
         * replay already does with it. The cascade also silently rewrote an operation's payload
         * whenever a purge fired, and operations are meant to be immutable.
         *
         * The `strokeId` indexes go with the keys they served. Nothing queries a target by its
         * stroke; they existed because Room asks for an index on a foreign key's child column.
         *
         * **`ink_strokes` swaps its `pageId` index for `(pageId, seq, id)`.** A replacement, not an
         * addition, so a stroke insert still maintains one index and the foreign key to `pages` is
         * still covered by the leading column. It removes the sorter from the page-load query — the
         * old plan pushed whole rows, `points` blobs and all, through SQLite's sorter — and turns
         * `nextSeq`, which runs on every stroke commit, from a walk of the page's rows into a single
         * seek to the end of its range.
         *
         * Nothing is backfilled and no data moves: the target rows are copied verbatim, ids and all,
         * including any whose stroke was already purged.
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(connection: SQLiteConnection) {
                // Rebuilding a table is the only way to drop a constraint in SQLite, and
                // `PRAGMA foreign_keys` cannot be changed inside the transaction a migration runs
                // in. Deferring the checks to its commit can be, and is what the documented
                // twelve-step ALTER procedure asks for.
                connection.execSQL("PRAGMA defer_foreign_keys = TRUE")

                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ink_erase_targets_new (
                        eraseId TEXT NOT NULL,
                        strokeId TEXT NOT NULL,
                        PRIMARY KEY(eraseId, strokeId),
                        FOREIGN KEY(eraseId) REFERENCES ink_erases(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "INSERT INTO ink_erase_targets_new(eraseId, strokeId) " +
                        "SELECT eraseId, strokeId FROM ink_erase_targets",
                )
                connection.execSQL("DROP TABLE ink_erase_targets")
                connection.execSQL("ALTER TABLE ink_erase_targets_new RENAME TO ink_erase_targets")

                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ink_move_targets_new (
                        moveId TEXT NOT NULL,
                        strokeId TEXT NOT NULL,
                        PRIMARY KEY(moveId, strokeId),
                        FOREIGN KEY(moveId) REFERENCES ink_moves(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "INSERT INTO ink_move_targets_new(moveId, strokeId) " +
                        "SELECT moveId, strokeId FROM ink_move_targets",
                )
                connection.execSQL("DROP TABLE ink_move_targets")
                connection.execSQL("ALTER TABLE ink_move_targets_new RENAME TO ink_move_targets")

                connection.execSQL("DROP INDEX IF EXISTS index_ink_strokes_pageId")
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ink_strokes_pageId_seq_id " +
                        "ON ink_strokes (pageId, seq, id)",
                )
            }
        }

        /**
         * Puts ink in the sync outbox — the change that makes a drawn page cross between devices.
         *
         * Nothing about the ink tables changes; this adds their insert/update triggers and queues
         * what is already on disk. The backfill is guarded on an existing `sync_state` row for the
         * reason 17 -> 18's was: an account activated under an older schema takes `activateLocked`'s
         * fast path after the upgrade and would never run the new seeding there, while a database
         * that has never been connected must stay untouched until its owner connects.
         *
         * On a drawn corpus this is the largest thing the outbox has ever held — one row per stroke,
         * up to the 500,000 a notebook may carry — which is why it is one `INSERT … SELECT` per
         * table rather than a walk, and why the first sync after upgrading is the one that takes a
         * while rather than every sync after it.
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(connection: SQLiteConnection) {
                installSyncTriggers(connection)
                listOf(
                    "inkStroke" to "ink_strokes",
                    "inkErase" to "ink_erases",
                    "inkMove" to "ink_moves",
                ).forEach { (kind, table) ->
                    connection.execSQL(
                        """
                        INSERT OR IGNORE INTO sync_outbox(kind, entityId, generation, changedAt)
                        SELECT '$kind', id, 1, COALESCE(deletedAt, createdAt) FROM $table
                        WHERE EXISTS (SELECT 1 FROM sync_state WHERE singleton = 0)
                        """.trimIndent(),
                    )
                }
            }
        }

        /**
         * Puts pictures in the sync outbox — the metadata half of attachments (`syncPlan.md` S5).
         *
         * Nothing about the `attachments` table changes. It gains an **insert** trigger and no
         * update trigger, which is the one asymmetry in `installSyncTriggers` and is worth the
         * paragraph: everything the protocol carries about an attachment describes the bytes its id
         * is the hash of, so the row is immutable in every synced field. The only column that ever
         * changes is `refCount`, which is per-device reachability and deliberately not synced
         * (`viveCServer/memory/syncPlan.md` SD7) — an update trigger would therefore re-push an
         * identical row every time a picture was pasted, and every other device would pull it.
         *
         * The backfill is guarded on an existing `sync_state` row exactly as 17 -> 18 and 19 -> 20
         * were: an account activated under an older schema takes `activateLocked`'s fast path after
         * the upgrade and would never run the new seeding there, while a database that has never
         * been connected must stay untouched until its owner connects.
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(connection: SQLiteConnection) {
                installSyncTriggers(connection)
                connection.execSQL(
                    """
                    INSERT OR IGNORE INTO sync_outbox(kind, entityId, generation, changedAt)
                    SELECT 'attachment', id, 1, createdAt FROM attachments
                    WHERE EXISTS (SELECT 1 FROM sync_state WHERE singleton = 0)
                    """.trimIndent(),
                )
                // And the bodies that place a picture, which is the half that is easy to miss: a
                // page already accepted by the server is not dirty, so it would never be pushed
                // again and its `blobRefs` — added to the protocol by this same phase — would never
                // reach the server at all. The account would then have pictures kept alive only by
                // their `attachments` rows, and the day a client learns to tombstone one, the
                // sweeper would free bytes a page still shows.
                //
                // `LIKE '%attachmentId%'` is the same test `HierarchySync.pictureIdsIn` makes
                // before decoding a document: it is a field name of `Outline.Image` and of nothing
                // else. A false positive costs one re-push of an unchanged body.
                connection.execSQL(
                    """
                    INSERT OR IGNORE INTO sync_outbox(kind, entityId, generation, changedAt)
                    SELECT 'pageContent', pageId, 1, updatedAt FROM page_content
                    WHERE docJson LIKE '%attachmentId%'
                      AND EXISTS (SELECT 1 FROM sync_state WHERE singleton = 0)
                    """.trimIndent(),
                )
            }
        }

        /**
         * The closed-notebook shelf: `closedAt`, and `cloudOnlyAt` for one whose contents now live
         * only on the server.
         *
         * Nothing is backfilled and nothing is queued. No build before this one could close a
         * notebook, so every existing row is open and on this device — which is precisely what null
         * says in both columns — and the notebook triggers installed since schema 17 already queue
         * a push the first time either column is written.
         *
         * Both are added to a table the `.vive` format pins the columns of, so
         * `NotebookTransferManager.prepareNotebookDatabase` drops them from its snapshot rather than
         * `EXPECTED_COLUMNS` growing. Which shelf a notebook sits on is this account's business, not
         * the notebook's content, and an imported notebook should always arrive open.
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE notebooks ADD COLUMN closedAt INTEGER")
                connection.execSQL("ALTER TABLE notebooks ADD COLUMN cloudOnlyAt INTEGER")
            }
        }

        /** Fresh databases do not run migrations, so they install the same triggers after create. */
        val SYNC_TRIGGER_CALLBACK = object : RoomDatabase.Callback() {
            override fun onCreate(connection: SQLiteConnection) {
                installSyncTriggers(connection)
            }
        }

        fun create(context: Context): NotesDatabase =
            Room.databaseBuilder(context, NotesDatabase::class.java, "notes.db")
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                    MIGRATION_21_22,
                )
                .addCallback(SYNC_TRIGGER_CALLBACK)
                .build()

        private fun installSyncTriggers(connection: SQLiteConnection) {
            listOf(
                SyncedTable("notebook", "notebooks", "id"),
                SyncedTable("section", "sections", "id"),
                SyncedTable("page", "pages", "id"),
                SyncedTable("pageContent", "page_content", "pageId"),
                // Ink, since schema 20. The target tables get none: they are never an entity, they
                // travel inside their operation's payload, and they are written in the same
                // transaction as the operation whose insert already queued it.
                SyncedTable("inkStroke", "ink_strokes", "id"),
                SyncedTable("inkErase", "ink_erases", "id"),
                SyncedTable("inkMove", "ink_moves", "id"),
                // Attachments, since schema 21, and the one kind with no update trigger — see
                // MIGRATION_20_21 for why an update here would only ever be a `refCount` the
                // protocol excludes.
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
