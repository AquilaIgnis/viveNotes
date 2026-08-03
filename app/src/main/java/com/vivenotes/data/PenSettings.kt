package com.vivenotes.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private val Context.penPreferences: DataStore<Preferences> by preferencesDataStore("pens")

/**
 * Pen types from `docs/references/pen-tooltip.jpeg`.
 *
 * The middle entry of that row is crossed out in the screenshot, so it is not here at all — the
 * same handling the View tab gives Dock to Desktop and the rest of the crossed-out list.
 */
@Serializable
enum class PenKind(val label: String) {
    Fountain("Fountain"),
    Calligraphy("Calligraphy"),
}

/** Solid, dashed or dotted ink. Maps to a brush family once strokes exist — `docs/inkPlan.md` §6. */
@Serializable
enum class LineType(val label: String) {
    Solid("Solid"),
    Dashed("Dashed"),
    Dotted("Dotted"),
}

/** Whether the eraser cuts through ink or removes a complete stroke at the first contact. */
@Serializable
enum class EraserMode(val label: String) {
    Normal("Normal"),
    Object("Object"),
}

/** User-level eraser preferences, shared by the ribbon eraser and a stylus's eraser end. */
@Serializable
data class EraserSettings(
    val mode: EraserMode = EraserMode.Normal,
    /** Diameter in page dp. */
    val size: Int = DEFAULT_SIZE,
) {
    companion object {
        const val MIN_SIZE = 4
        const val MAX_SIZE = 64
        const val DEFAULT_SIZE = 18
    }
}

/**
 * One of the Draw tab's pens.
 *
 * Every field here describes *the user*, not a page — which is why this is DataStore beside
 * [EditorDefaults] rather than anything in `PageDoc`. A pen is how someone likes to write; the brush
 * recorded on a stroke is a property of that stroke and travels with it to another device. Getting
 * that boundary wrong is a sync bug rather than a refactor, so it is worth stating twice:
 * `docs/inkPlan.md` ID5 has the full three-way rule.
 *
 * Defaults are the values the reference screenshot is showing.
 */
@Serializable
data class PenPreset(
    val kind: PenKind = PenKind.Fountain,
    val lineType: LineType = LineType.Solid,
    val colorArgb: Int = 0xFF000000.toInt(),
    /** True only while this pen is using the automatic high-contrast starter colour. */
    val colorFollowsTheme: Boolean = false,
    val thickness: Int = 5,
    val pressure: Int = 3,
    val stabilization: Int = 1,
    val holdToDrawShape: Boolean = true,
    val scribbleToErase: Boolean = true,
) {
    companion object {
        /** Three pens, per the Draw tab. They differ only by colour, which is their whole purpose. */
        const val COUNT = 3

        const val MIN_THICKNESS = 1
        const val MAX_THICKNESS = 12

        /** 0 is off in both cases: no pressure response, no smoothing. */
        const val MAX_PRESSURE = 5
        const val MAX_STABILIZATION = 5

        /** What each pen starts as. Different colours, because swapping colour is why there are three. */
        private val STARTING_COLORS = listOf(0xFF000000, 0xFFE53935, 0xFF1E88E5).map { it.toInt() }

        fun starting(index: Int): PenPreset = PenPreset(
            colorArgb = STARTING_COLORS[index % STARTING_COLORS.size],
            // Pen 1 is the notebook's automatic default. The red and blue pens are intentional
            // shortcuts, so changing the theme must not erase their identity.
            colorFollowsTheme = index == 0,
        )
    }
}

/** Resolves the automatic starter colour without changing a colour the user explicitly picked. */
fun PenPreset.forCanvasTheme(isDark: Boolean): PenPreset =
    if (colorFollowsTheme) {
        copy(colorArgb = if (isDark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
    } else {
        this
    }

/**
 * The colours a pen can be set to, from the swatch row in `docs/references/pen-tooltip.jpeg`.
 */
val PEN_COLORS: List<Int> = listOf(
    // Keep the two neutral inks in fixed, predictable positions before every accent colour.
    0xFFFFFFFF, 0xFF000000, 0xFFE53935, 0xFF00BCD4, 0xFF00C853,
    0xFFFFD600, 0xFFFF9100, 0xFF9C27B0, 0xFF2962FF,
).map { it.toInt() }

/**
 * Which drawing tool is armed.
 *
 * Deliberately not persisted, for the reason the open tool pane is not: it is where you are, not
 * what you have. Reopening the app should not leave an eraser in your hand.
 */
sealed interface DrawTool {

    data class Pen(val index: Int) : DrawTool

    data object Eraser : DrawTool

    /** Free-form selection: circle ink, then drag the selected objects. */
    data object Lasso : DrawTool

    /** Nothing armed: taps reach the text containers, and the canvas behaves as it does today. */
    data object None : DrawTool
}

/**
 * Persists the three pens across launches.
 *
 * One JSON blob per pen rather than a preference key per field: a pen is nine values that are only
 * ever read and written together, and thirty flat keys would make adding a tenth a migration.
 * Decoding is failure-tolerant — a blob written by a build with a setting this one does not have
 * falls back to the default for that field rather than losing the whole pen.
 */
class PenSettingsStore(context: Context) {

    private val store = context.applicationContext.penPreferences

    val pens: Flow<List<PenPreset>> = store.data.map { prefs ->
        List(PenPreset.COUNT) { index ->
            prefs[key(index)]?.let { decodePen(index, it) } ?: PenPreset.starting(index)
        }
    }

    val eraser: Flow<EraserSettings> = store.data.map { prefs ->
        prefs[ERASER]?.let(::decodeEraser) ?: EraserSettings()
    }

    /**
     * Whether a finger may draw, or only a stylus.
     *
     * Off by default, which is the right answer on the device this is for: with it off a finger
     * scrolls the page and the pen draws, so both work at once and a resting palm cannot leave a
     * mark. Turning it on is what makes drawing possible on a device with no stylus — including an
     * emulator, where the mouse arrives as a direct touch.
     *
     * A property of this device rather than of any pen: whether you *have* a stylus is not a
     * setting on pen 2.
     */
    val drawWithFinger: Flow<Boolean> = store.data.map { it[DRAW_WITH_FINGER] ?: false }

    suspend fun setDrawWithFinger(enabled: Boolean) {
        store.edit { it[DRAW_WITH_FINGER] = enabled }
    }

    suspend fun setPen(index: Int, preset: PenPreset) {
        store.edit { it[key(index)] = json.encodeToString(PenPreset.serializer(), preset) }
    }

    suspend fun setEraser(settings: EraserSettings) {
        store.edit { it[ERASER] = json.encodeToString(EraserSettings.serializer(), settings) }
    }

    private fun decodePen(index: Int, text: String): PenPreset? = runCatching {
        val decoded = json.decodeFromString(PenPreset.serializer(), text)
        val hasThemeFlag = json.parseToJsonElement(text).jsonObject.containsKey("colorFollowsTheme")
        if (hasThemeFlag) {
            decoded
        } else {
            // Before the flag existed, an untouched first pen was stored as black. Preserve any
            // other saved colour as an explicit choice; only that old factory state is migrated.
            decoded.copy(
                colorFollowsTheme = index == 0 && decoded.colorArgb == 0xFF000000.toInt(),
            )
        }
    }.getOrNull()

    private fun decodeEraser(text: String): EraserSettings? =
        runCatching { json.decodeFromString(EraserSettings.serializer(), text) }.getOrNull()

    private companion object {
        fun key(index: Int) = stringPreferencesKey("pen_$index")

        val DRAW_WITH_FINGER = booleanPreferencesKey("draw_with_finger")
        val ERASER = stringPreferencesKey("eraser")

        /**
         * `ignoreUnknownKeys` covers a field this build does not have; `coerceInputValues` covers a
         * *value* it does not have — a pen kind added later, or one dropped from the list — which
         * would otherwise throw and lose the pen over one setting. Same pair, for the same reason,
         * as `DocumentJson`.
         */
        val json = Json {
            // The false value is meaningful: it distinguishes an explicitly chosen black/white
            // from an old preset that predates colorFollowsTheme, so it must be written as well.
            encodeDefaults = true
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }
}
