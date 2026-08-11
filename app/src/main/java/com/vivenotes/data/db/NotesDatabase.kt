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
        InkStrokeEntity::class,
        InkEraseEntity::class,
        InkEraseTargetEntity::class,
        InkMoveEntity::class,
        InkMoveTargetEntity::class,
        AttachmentEntity::class,
    ],
    version = 10,
    // Turn this on together with the androidx.room Gradle plugin and room.schemaLocation before
    // the first release, since the exported schemas are what migration tests assert against.
    exportSchema = false,
)
abstract class NotesDatabase : RoomDatabase() {

    abstract fun notebookDao(): NotebookDao
    abstract fun sectionDao(): SectionDao
    abstract fun pageDao(): PageDao
    abstract fun pageContentDao(): PageContentDao
    abstract fun inkStrokeDao(): InkStrokeDao
    abstract fun inkEraseDao(): InkEraseDao
    abstract fun inkMoveDao(): InkMoveDao
    abstract fun attachmentDao(): AttachmentDao

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
                )
                .build()
    }
}
