package st.unamedtba.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        NotebookEntity::class,
        SectionEntity::class,
        PageEntity::class,
        PageContentEntity::class,
    ],
    version = 1,
    // No migrations exist yet. Turn this on together with the androidx.room Gradle plugin and
    // room.schemaLocation as soon as the schema ships to a real device, since the exported
    // schemas are what migration tests assert against.
    exportSchema = false,
)
abstract class NotesDatabase : RoomDatabase() {

    abstract fun notebookDao(): NotebookDao
    abstract fun sectionDao(): SectionDao
    abstract fun pageDao(): PageDao
    abstract fun pageContentDao(): PageContentDao

    companion object {
        fun create(context: Context): NotesDatabase =
            Room.databaseBuilder(context, NotesDatabase::class.java, "notes.db")
                .build()
    }
}
