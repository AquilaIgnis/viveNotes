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
    ],
    version = 2,
    // Turn this on together with the androidx.room Gradle plugin and room.schemaLocation before
    // the first release, since the exported schemas are what migration tests assert against.
    exportSchema = false,
)
abstract class NotesDatabase : RoomDatabase() {

    abstract fun notebookDao(): NotebookDao
    abstract fun sectionDao(): SectionDao
    abstract fun pageDao(): PageDao
    abstract fun pageContentDao(): PageContentDao

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

        fun create(context: Context): NotesDatabase =
            Room.databaseBuilder(context, NotesDatabase::class.java, "notes.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
