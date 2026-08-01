package st.unamedtba.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.viewPreferences: DataStore<Preferences> by preferencesDataStore("view")

/** Where the notebook and section navigation lives — the View tab's Tabs Layout control. */
enum class TabsLayout { Vertical, Horizontal }

/**
 * The View tab's settings that describe *this device's* view of a notebook rather than any page.
 *
 * Split from [st.unamedtba.model.PageStyle] along that line: ruling and page colour are properties
 * of the page and travel with it, while zoom, navigation layout and canvas brightness are how one
 * person happens to be looking at it right now. Syncing a phone's zoom level onto a tablet would be
 * actively wrong.
 */
data class ViewSettings(
    val zoom: Float = 1f,
    val tabsLayout: TabsLayout = TabsLayout.Vertical,
    /**
     * Canvas brightness override from Switch Background. Null follows the app theme, which is the
     * state a fresh install is in — the toggle only pins the canvas once it has been used.
     */
    val canvasDark: Boolean? = null,
) {
    companion object {
        /**
         * The zoom levels the ribbon offers, and the ladder the +/- buttons climb. Kept together so
         * stepping can never land on a value the combo box cannot display.
         */
        val ZOOM_STEPS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)

        val MIN_ZOOM = ZOOM_STEPS.first()
        val MAX_ZOOM = ZOOM_STEPS.last()

        /**
         * The next preset above [zoom].
         *
         * Zoom does not always sit on a preset — Page Width lands on whatever fits — so this looks
         * for the neighbouring step rather than indexing the list. The epsilon stops a value that
         * already *is* a step from selecting itself and leaving the button dead.
         */
        fun zoomStepUp(zoom: Float): Float =
            ZOOM_STEPS.firstOrNull { it > zoom + ZOOM_EPSILON } ?: MAX_ZOOM

        fun zoomStepDown(zoom: Float): Float =
            ZOOM_STEPS.lastOrNull { it < zoom - ZOOM_EPSILON } ?: MIN_ZOOM

        /**
         * The zoom that fits [contentWidthDp] into [viewportWidthDp], or null when the canvas has
         * not been measured yet — better to leave zoom alone than to divide by a guess.
         */
        fun fitZoom(viewportWidthDp: Float, contentWidthDp: Float): Float? {
            if (viewportWidthDp <= 0f || contentWidthDp <= 0f) return null
            return (viewportWidthDp / contentWidthDp).coerceIn(MIN_ZOOM, MAX_ZOOM)
        }

        private const val ZOOM_EPSILON = 0.001f
    }
}

/**
 * Persists [ViewSettings] across launches.
 *
 * Preferences rather than a Room table, for the same reason as [EditorDefaultsStore]: these are
 * scalars with no place in the note graph, and putting them in the database would drag them into
 * the sync protocol.
 */
class ViewSettingsStore(context: Context) {

    private val store = context.applicationContext.viewPreferences

    val settings: Flow<ViewSettings> = store.data.map { prefs ->
        ViewSettings(
            zoom = prefs[ZOOM]?.coerceIn(ViewSettings.MIN_ZOOM, ViewSettings.MAX_ZOOM) ?: 1f,
            // An unrecognised value means a build that wrote a layout this one does not have, so it
            // falls back rather than failing.
            tabsLayout = prefs[TABS_LAYOUT]
                ?.let { name -> TabsLayout.entries.firstOrNull { it.name == name } }
                ?: TabsLayout.Vertical,
            canvasDark = prefs[CANVAS_DARK],
        )
    }

    suspend fun setZoom(zoom: Float) {
        store.edit { it[ZOOM] = zoom.coerceIn(ViewSettings.MIN_ZOOM, ViewSettings.MAX_ZOOM) }
    }

    suspend fun setTabsLayout(layout: TabsLayout) {
        store.edit { it[TABS_LAYOUT] = layout.name }
    }

    suspend fun setCanvasDark(dark: Boolean) {
        store.edit { it[CANVAS_DARK] = dark }
    }

    private companion object {
        val ZOOM = floatPreferencesKey("zoom")
        val TABS_LAYOUT = stringPreferencesKey("tabs_layout")
        val CANVAS_DARK = booleanPreferencesKey("canvas_dark")
    }
}
