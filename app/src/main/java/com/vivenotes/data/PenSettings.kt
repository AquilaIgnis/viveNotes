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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
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

/**
 * The Draw tab's highlighter.
 *
 * Not a [PenPreset]. A highlighter answers a different set of questions: it has no line type, no
 * nib, and no pressure response — a real one lays down the same flat band however hard it is pressed
 * — so modelling it as a fourth pen would mean a pane of controls that do nothing. What is left is
 * the two things K2 asks for, colour and thickness, which is why this is its own small record beside
 * [EraserSettings] rather than a variant of the pen.
 *
 * Like both of those, it describes the user rather than a page (ID5), so it lives in DataStore.
 */
@Serializable
data class HighlighterSettings(
    /** Stored *with* its alpha: translucency is what the colour is, not how it is drawn. */
    val colorArgb: Int = DEFAULT_COLOR,
    /** Band width in page dp. */
    val thickness: Int = DEFAULT_THICKNESS,
) {
    companion object {
        /** Wide enough to cover a line of text at the default size, and no wider. */
        const val MIN_THICKNESS = 6
        const val MAX_THICKNESS = 40
        const val DEFAULT_THICKNESS = 18

        const val DEFAULT_COLOR = 0x66FFEB3B
    }
}

/**
 * The highlighter inks, the same hues at the same alpha as the Home tab's text highlight.
 *
 * Deliberately shared with that palette: a page can be highlighted either by marking up text or by
 * drawing over it, and the two producing different yellows would be a bug the user could see. The
 * "none" swatch that palette carries is absent here — an ink you cannot see is not a colour, it is
 * putting the highlighter down.
 *
 * `0x66` alpha keeps every literal inside `Int` range, so unlike [PEN_COLORS] these need no
 * conversion from `Long`.
 */
val HIGHLIGHTER_COLORS: List<Int> = listOf(
    0x66FFEB3B, 0x6676FF03, 0x6640C4FF,
    0x66FF4081, 0x66FF9100, 0x66B388FF,
)

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
 * The palette a pen starts with, from the swatch row in `docs/references/pen-tooltip.jpeg`.
 *
 * Only the starting state: the row is a rolling list, so a colour taken off the wheel takes the
 * front and the tail falls off. White and black lead it as shipped, but they are not pinned — nine
 * custom colours will push them out, and the wheel is how they come back.
 */
val PEN_COLORS: List<Int> = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFE53935, 0xFF00BCD4, 0xFF00C853,
    0xFFFFD600, 0xFFFF9100, 0xFF9C27B0, 0xFF2962FF,
).map { it.toInt() }

/** How many swatches the row shows. Fixed, because ten targets are what fit the panel's width. */
val PALETTE_SIZE: Int = PEN_COLORS.size

/**
 * The palette with [argb] at the front, one swatch longer at the head and one shorter at the tail.
 *
 * A colour already in the row moves rather than repeats, which is what keeps re-picking a colour
 * from costing the row an entry. That also means the length only ever changes when the colour is
 * genuinely new — the tail is evicted to make room, never to make a duplicate.
 */
fun List<Int>.withColorInFront(argb: Int): List<Int> =
    (listOf(argb) + filterNot { it == argb }).take(PALETTE_SIZE)

/**
 * Which drawing tool is armed.
 *
 * Deliberately not persisted, for the reason the open tool pane is not: it is where you are, not
 * what you have. Reopening the app should not leave an eraser in your hand.
 */
sealed interface DrawTool {

    data class Pen(val index: Int) : DrawTool

    /** One highlighter, not three: its colour is the only thing to swap and the row is short. */
    data object Highlighter : DrawTool

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

    val highlighter: Flow<HighlighterSettings> = store.data.map { prefs ->
        prefs[HIGHLIGHTER]?.let(::decodeHighlighter) ?: HighlighterSettings()
    }

    /**
     * The swatch row, which the wheel adds to.
     *
     * Stored beside the pens rather than on one of them: the row is the same in all three panes, so
     * a colour mixed while holding pen 2 is there when pen 1 is picked up. It is a property of the
     * user by ID5 — the colours someone reaches for — not of a device or of any page.
     */
    val palette: Flow<List<Int>> = store.data.map { prefs ->
        prefs[PALETTE]?.let(::decodePalette) ?: PEN_COLORS
    }

    /** Read-modify-write inside one `edit`, so two quick picks cannot lose each other. */
    suspend fun addPaletteColor(argb: Int) {
        store.edit { prefs ->
            val current = prefs[PALETTE]?.let(::decodePalette) ?: PEN_COLORS
            prefs[PALETTE] = json.encodeToString(
                ListSerializer(Int.serializer()),
                current.withColorInFront(argb),
            )
        }
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

    suspend fun setHighlighter(settings: HighlighterSettings) {
        store.edit {
            it[HIGHLIGHTER] = json.encodeToString(HighlighterSettings.serializer(), settings)
        }
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

    private fun decodeHighlighter(text: String): HighlighterSettings? =
        runCatching { json.decodeFromString(HighlighterSettings.serializer(), text) }.getOrNull()

    /**
     * A stored row shorter or longer than the current [PALETTE_SIZE] is trimmed rather than
     * rejected, so changing how many swatches fit is not a migration. An empty one is nothing to
     * show, which is the one case worth falling back to the shipped palette for.
     */
    private fun decodePalette(text: String): List<Int>? = runCatching {
        json.decodeFromString(ListSerializer(Int.serializer()), text)
            .take(PALETTE_SIZE)
            .ifEmpty { null }
    }.getOrNull()

    private companion object {
        fun key(index: Int) = stringPreferencesKey("pen_$index")

        val DRAW_WITH_FINGER = booleanPreferencesKey("draw_with_finger")
        val ERASER = stringPreferencesKey("eraser")
        val HIGHLIGHTER = stringPreferencesKey("highlighter")
        val PALETTE = stringPreferencesKey("palette")

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
