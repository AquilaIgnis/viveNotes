package st.unamedtba.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.editorPreferences: DataStore<Preferences> by preferencesDataStore("editor")

/**
 * Font family and size for text that carries no mark of its own.
 *
 * Kept out of [st.unamedtba.model.PageDoc] on purpose: this is a preference about how the user
 * likes to write, not a property of any one document. Storing it in the document would mean a page
 * created before the preference changed keeps typing in the old font, which is the opposite of what
 * "default" means here.
 */
data class EditorDefaults(
    val fontFamily: String = FALLBACK_FONT_FAMILY,
    val fontSize: Int = FALLBACK_FONT_SIZE,
) {
    companion object {
        const val FALLBACK_FONT_FAMILY = "sans-serif"
        const val FALLBACK_FONT_SIZE = 15
    }
}

/**
 * Persists [EditorDefaults] across launches.
 *
 * Deliberately not a Room table. These are two scalars with no relation to the note graph, and
 * putting them in the database would drag them into the sync protocol, where one device's font
 * preference has no business overwriting another's.
 */
class EditorDefaultsStore(context: Context) {

    private val store = context.applicationContext.editorPreferences

    val defaults: Flow<EditorDefaults> = store.data.map { prefs ->
        EditorDefaults(
            fontFamily = prefs[FAMILY] ?: EditorDefaults.FALLBACK_FONT_FAMILY,
            fontSize = prefs[SIZE] ?: EditorDefaults.FALLBACK_FONT_SIZE,
        )
    }

    suspend fun setFontFamily(id: String) {
        store.edit { it[FAMILY] = id }
    }

    suspend fun setFontSize(sp: Int) {
        store.edit { it[SIZE] = sp }
    }

    private companion object {
        val FAMILY = stringPreferencesKey("default_font_family")
        val SIZE = intPreferencesKey("default_font_size")
    }
}
