package st.unamedtba

import android.app.Application
import st.unamedtba.data.NotesRepository
import st.unamedtba.data.db.NotesDatabase
import st.unamedtba.richtext.FontRegistry

/**
 * Manual dependency container.
 *
 * A DI framework would be a second annotation processor for three objects. Revisit when the app
 * is split into modules and the graph stops fitting on one screen.
 */
class NotesApplication : Application() {

    val database: NotesDatabase by lazy { NotesDatabase.create(this) }
    val repository: NotesRepository by lazy { NotesRepository(database) }

    override fun onCreate() {
        super.onCreate()
        FontRegistry.init(this)
    }
}
