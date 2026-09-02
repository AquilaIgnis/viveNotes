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
import com.vivenotes.model.ink.LineType
import com.vivenotes.model.ink.ShapeKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private val Context.penPreferences: DataStore<Preferences> by preferencesDataStore("pens")

/**
 * Pen types from `memory/references/pen-tooltip.jpeg`.
 *
 * The middle entry of that row is crossed out in the screenshot, so it is not here at all — the
 * same handling the View tab gives Dock to Desktop and the rest of the crossed-out list.
 */
@Serializable
enum class PenKind(val label: String) {
    Fountain("Fountain"),
    Calligraphy("Calligraphy"),
}

/** Whether the eraser cuts through ink or removes a complete stroke at the first contact. */
@Serializable
enum class EraserMode(val label: String) {
    Normal("Normal"),
    Object("Object"),
}

/** The two rulers — `memory/rulerPlan.md`. A straightedge, and a semicircle for arcs. */
@Serializable
enum class RulerKind(val label: String) {
    Straight("Straight"),
    Protractor("Semicircle"),
}

/**
 * The ruler you draw against — `memory/rulerPlan.md` RD2.
 *
 * Only what describes *the user*: which ruler they reach for and how big they like it. Whether it is
 * currently out, and where it is lying, are facts about this moment rather than about anyone, so they
 * are transient state in the ViewModel and in `EditorPane` — the same split [ShapeSettings] draws
 * between its [kind] and `DrawTool.Shape`.
 */
@Serializable
data class RulerSettings(
    val kind: RulerKind = RulerKind.Straight,
    /**
     * How far across the semicircle is, in page dp.
     *
     * **The straightedge has no length here**, and that is the point: it always spans the viewport
     * (RD3a), so its length is a layout fact rather than a preference. It was a setting until the
     * reference plate settled the question — the ruler in `memory/references/ruler.png` runs off both
     * edges of the frame, which is what a straightedge you lay across your work does.
     */
    val diameterDp: Int = DEFAULT_DIAMETER,
) {
    companion object {
        const val MIN_DIAMETER = 240
        const val MAX_DIAMETER = 1200

        /** Four inches across, so the whole arc is in view when it arrives. */
        const val DEFAULT_DIAMETER = 640
    }
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
 * The Insert Shape tool — `memory/inkPlan.md` §5.4.
 *
 * Not a [PenPreset], for the reason [HighlighterSettings] is not: the questions differ. A shape has
 * no nib, no pressure response and no stabilization — it is traced along an ideal path, so there is
 * no shake to smooth — and it has two things a pen has no use for, a border width measured
 * separately from a pen's thickness and, eventually, a fill.
 *
 * Every field here describes *the user*, per ID5: how you like to draw shapes. What the traced
 * strokes carry is a property of the shape you drew and travels with the page. Getting that boundary
 * wrong is a sync bug rather than a refactor.
 *
 * [kind] lives here rather than on [DrawTool.Shape] deliberately. Which shape is armed is a setting
 * the way a highlighter's ink is a setting: the tool in your hand is not persisted, but what it is
 * set to is.
 */
@Serializable
data class ShapeSettings(
    val kind: ShapeKind = ShapeKind.DEFAULT,
    val lineType: LineType = LineType.Solid,
    /** Border width in page dp. */
    val borderWidth: Int = DEFAULT_BORDER_WIDTH,
    val borderColorArgb: Int = 0xFF000000.toInt(),
    /**
     * Null for no fill, which is what the reference pane shows and what a shape starts with.
     * Distinct from a transparent colour: "none" is the absence of a fill, not a see-through one.
     */
    val fillArgb: Int? = null,
    /** True only while the border is using the automatic high-contrast starter colour. */
    val colorFollowsTheme: Boolean = true,
) {
    companion object {
        const val MIN_BORDER_WIDTH = 1
        const val MAX_BORDER_WIDTH = 12

        /** The value the reference pane is showing. */
        const val DEFAULT_BORDER_WIDTH = 2
    }
}

/**
 * The Insert Table tool — `memory/tablePlan.md` TA7, and `memory/references/table-opts.jpeg` field for
 * field.
 *
 * Every value here describes *the user*, per ID5: how you like a table to start. What the table
 * carries in the document is a property of that table and travels with the page — the same boundary
 * [ShapeSettings] draws against `Outline.Shape`, and with the same warning attached. The two panes
 * look alike deliberately and must never be merged: one is a preference, the other is an edit, and
 * collapsing them is a sync bug rather than a refactor.
 *
 * The plate's fill reads "none", which is where a table starts and is not the same thing as a
 * transparent one.
 */
@Serializable
data class TableSettings(
    /**
     * Whether the next table is a **ruling to write in** rather than a grid of text fields — TA15,
     * which its cells carry as `Outline.Table.inkOnly`.
     *
     * **This was a second tool and is now a setting.** `DrawTool.InkTable` existed because the two
     * kinds place different objects; what that bought was two Table buttons on two tabs, differing
     * in a way neither button could show. The kind is a question about the table you are about to
     * make, which is what every other field here is, so it is asked in the same pane.
     *
     * Starts on because the one button that reads it sits on the Draw tab, among the things a stylus
     * uses.
     */
    val inkOnly: Boolean = true,
    val columns: Int = DEFAULT_COLUMNS,
    val rows: Int = DEFAULT_ROWS,
    val headerRow: Boolean = true,
    val headerColumn: Boolean = false,
    /** Border width in page dp. */
    val borderWidth: Int = DEFAULT_BORDER_WIDTH,
    val borderColorArgb: Int = 0xFF9AA0A6.toInt(),
    val fillArgb: Int? = null,
    /** True only while the border is using the automatic high-contrast starter colour. */
    val colorFollowsTheme: Boolean = true,
) {
    companion object {
        /** What the reference panel is showing. */
        const val DEFAULT_COLUMNS = 3
        const val DEFAULT_ROWS = 3

        const val MIN_BORDER_WIDTH = 1
        const val MAX_BORDER_WIDTH = 8
        const val DEFAULT_BORDER_WIDTH = 1
    }
}

/** Resolves the automatic border colour, as a pen and a shape do — see [PenPreset.forCanvasTheme]. */
fun TableSettings.forCanvasTheme(isDark: Boolean): TableSettings =
    if (colorFollowsTheme) {
        // Not pure white or black: a table is a grid of hairlines, and at full contrast the rules
        // shout over the text they are there to organise.
        copy(borderColorArgb = if (isDark) 0xFF9AA0A6.toInt() else 0xFF80868B.toInt())
    } else {
        this
    }

/** Resolves the automatic border colour without changing one the user explicitly picked. */
fun ShapeSettings.forCanvasTheme(isDark: Boolean): ShapeSettings =
    if (colorFollowsTheme) {
        copy(borderColorArgb = if (isDark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
    } else {
        this
    }

/**
 * One of the Draw tab's pens.
 *
 * Every field here describes *the user*, not a page — which is why this is DataStore beside
 * [EditorDefaults] rather than anything in `PageDoc`. A pen is how someone likes to write; the brush
 * recorded on a stroke is a property of that stroke and travels with it to another device. Getting
 * that boundary wrong is a sync bug rather than a refactor, so it is worth stating twice:
 * `memory/inkPlan.md` ID5 has the full three-way rule.
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
    val thickness: Float = 1.5f,
    val pressure: Int = 3,
    val stabilization: Int = 1,
    /**
     * Whether pausing at the end of a straight-ish stroke replaces it with a line object.
     *
     * **Was `holdToDrawShape`, and the rename is the feature.** `memory/inkPlan.md` §5 planned a
     * classifier over line, circle and rectangle; what is built is the line alone, by request, so
     * the toggle says what it does. The old key is not read back — `penSettingsJson` has
     * `ignoreUnknownKeys`, so a preset saved with the old name arrives with this at its default —
     * which costs anyone who turned the old toggle off nothing, because the old toggle was wired to
     * a panel and to nothing else.
     *
     * On the pen rather than in one shared place, per ID5: a fine pen for handwriting and a thick
     * one kept for ruling lines want opposite answers, and this is a question about how you draw.
     */
    val holdForStraightLine: Boolean = true,
) {
    companion object {
        /** Three pens, per the Draw tab. They differ only by colour, which is their whole purpose. */
        const val COUNT = 3

        const val MIN_THICKNESS = 1f
        const val MAX_THICKNESS = 8f

        /** Half a dp, so the range is 1, 1.5, 2 … 8 rather than eight steps. */
        const val THICKNESS_STEP = 0.5f

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
 * The colour to actually paint with, for anything that recorded whether its colour was automatic.
 *
 * **The settings' `forCanvasTheme` family resolves a colour for the tool; this resolves it for the
 * mark already on the page**, and the two exist for opposite halves of the same rule. Resolving only
 * at the tool bakes the answer into the stroke, so Switch Background left every automatic mark at
 * whatever the canvas happened to be when it was drawn — white ink staying white on white paper,
 * while the text beside it flipped. Text never had the bug because it stores no colour at all and
 * reads [com.vivenotes.ui.theme.CanvasColors.text] at paint time; this is how ink and objects get to
 * say the same thing.
 *
 * [followsTheme] is deliberately tri-state:
 *  - `true` — automatic, drawn with the pen or shape that follows the canvas. Always resolves.
 *  - `false` — a colour the user picked. Never resolves; the codebase's standing rule is that a
 *    deliberate choice survives the theme changing under it.
 *  - `null` — **written before any of this was recorded.** Its intent is genuinely unknown, so it is
 *    inferred: pure white and pure black are what the automatic pen resolved to and nothing else, so
 *    they are read as automatic and every other colour is read as chosen. The inference is confined
 *    to null, so it applies to old marks only and never to anything drawn from now on.
 *
 * The inference can be wrong in exactly one way — a stroke where white or black was picked from the
 * palette by hand — and it fails safe: that mark flips instead of disappearing.
 */
fun automaticColorOr(stored: Int, followsTheme: Boolean?, canvasInk: Int): Int = when {
    followsTheme == true -> canvasInk
    followsTheme == false -> stored
    stored == AUTOMATIC_LIGHT || stored == AUTOMATIC_DARK -> canvasInk
    else -> stored
}

/** What the automatic pen resolves to on a dark canvas — see [PenPreset.forCanvasTheme]. */
const val AUTOMATIC_LIGHT: Int = 0xFFFFFFFF.toInt()

/** And on a light one. */
const val AUTOMATIC_DARK: Int = 0xFF000000.toInt()

/**
 * The colour an automatic pen or shape border is showing on this canvas.
 *
 * The mirror of [automaticColorOr], and the half a *picker* needs: that one asks whether a mark was
 * automatic, this asks whether a colour is the one automatic would have produced. Tapping it is
 * therefore not the same act as tapping any other swatch — see `PenPanel`, which is where the
 * distinction is made and why.
 */
fun automaticInkFor(isDark: Boolean): Int = if (isDark) AUTOMATIC_LIGHT else AUTOMATIC_DARK

val PEN_COLORS: List<Int> = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFE53935, 0xFF00BCD4, 0xFF00C853,
    0xFFFFD600, 0xFFFF9100, 0xFF9C27B0, 0xFF2962FF,
).map { it.toInt() }

/** How many swatches the row shows. Fixed, because ten targets are what fit the panel's width. */
val PALETTE_SIZE: Int = PEN_COLORS.size

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

    /**
     * Insert Shape: drag out a box and the chosen shape is traced into it.
     *
     * One tool rather than sixteen, because *which* shape it draws is [ShapeSettings.kind] — the
     * same split [Highlighter] has from [HighlighterSettings].
     */
    data object Shape : DrawTool

    /**
     * Insert Table: the next tap on bare canvas puts a table there — `memory/tablePlan.md` TA7.
     *
     * A tool rather than a button that drops one, for the reason [Shape] is one: a page is a canvas,
     * and what goes on it goes where you put it. How many rows and columns it arrives with is
     * [TableSettings], the same split [Shape] has from [ShapeSettings].
     *
     * **One tool for both kinds of table.** Whether it places a grid of text fields or a ruling to
     * write in is [TableSettings.inkOnly] — the same split again, and the reason `InkTable` is gone.
     */
    data object Table : DrawTool

    /**
     * Insert Equation **as an object**: the next tap on bare canvas puts the formula there.
     *
     * A tool rather than a drop, for the reason [Table] and [Shape] are: a page is a canvas and what
     * goes on it goes where you put it. *Which* formula is not a setting, though — it is content, so
     * unlike every other tool here what this one carries is held beside it, as the ViewModel's
     * pending equation, and is gone when the tool is put down.
     *
     * The Home tab's ƒ writes into text instead and arms nothing; see `Outline.Equation` for why the
     * two are different types rather than one with a flag.
     */
    data object Equation : DrawTool

    /** Free-form selection: circle ink, then drag the selected objects. */
    data object Lasso : DrawTool

    /**
     * Insert Space: draw a line across the page and drag, and everything past it moves — E2.
     *
     * A tool rather than a dialog asking for a number, because the thing being edited is a gap and a
     * gap is something you point at. It is also the one tool here that edits *no object*: what it
     * changes is where everything else sits, so unlike [Shape] or [Table] it carries no settings at
     * all — how much space, and in which direction, is the drag itself. See
     * `com.vivenotes.model.PageSpace`.
     */
    data object InsertSpace : DrawTool

    /**
     * Text: a tap on bare canvas opens a container and puts a caret in it —
     * `memory/textBoxPlan.md` TD2.
     *
     * This *was* [None], which is why the Home tab's **T** button could be pressed but never
     * unpressed: the name said "nothing armed" and the behaviour said "text armed", so there was
     * nothing to turn off to. Naming it is what makes the button a toggle.
     */
    data object Text : DrawTool

    /**
     * Nothing armed. Taps still reach the text containers — that is text *processing*, which no
     * tool owns — but bare canvas does nothing at all: no mark, and no new container.
     */
    data object None : DrawTool
}

/**
 * What one press of the stylus's barrel button does — `memory/stylusPlan.md` SB2.
 *
 * Every entry is something the ribbon can already do, reached through a view-model method that
 * already exists: this is a second way to reach a capability, never a new one. What is *not* here is
 * argued in SB2 — the placement tools are left out because a barrel button is pressed with the pen in
 * the air, and arming one by accident drops an object on the next touch.
 *
 * [TogglePenEraser] is not a tool but a rule, and has to stay an action of its own (SB2a): flattening
 * it into [Eraser] would hand the user a button that arms the eraser and then has nothing left to do.
 *
 * Serialized **by name**, so renaming an entry is a stored-value change: an old blob naming the
 * entry that used to exist decodes to the field's default rather than to its replacement.
 */
@Serializable
enum class StylusAction(val label: String) {
    /** Unbound. The keycode is left unclaimed so it falls through — SB5, and it is the default for a
     * press nothing has asked for. */
    None("Nothing"),
    TogglePenEraser("Pen / eraser"),
    CyclePens("Next pen"),
    Pen1("Pen 1"),
    Pen2("Pen 2"),
    Pen3("Pen 3"),
    Highlighter("Highlighter"),
    Eraser("Eraser"),
    Lasso("Lasso select"),
    Undo("Undo"),
    Redo("Redo"),
}

/**
 * What each click count does — `memory/stylusPlan.md` SB1 and SB3.
 *
 * **The unit is a completed click count, not "the button".** The pen reports one, two and three
 * clicks as three separate keycodes because its firmware has already done the timing, so there are
 * exactly three fields here and nothing anywhere that measures an interval.
 *
 * A property of **the user**, not of this device (SB3): "double click means highlighter" is a working
 * habit, the same kind of fact as "pen 2 is red", and it should follow its owner to another tablet.
 * Carrying it there is safe — a pen with no triple click simply never fires that binding, so a
 * mapping is never *wrong* on unfamiliar hardware, only unused.
 *
 * **The defaults are exactly the behaviour the hard-coded version shipped with** (SB4). Someone who
 * never opens the Hardware pane must not be able to tell that this became configurable.
 */
@Serializable
data class StylusButtonMap(
    val single: StylusAction = StylusAction.TogglePenEraser,
    val double: StylusAction = StylusAction.Lasso,
    /** Unbound, so a third click still falls through to whatever else wants it. */
    val triple: StylusAction = StylusAction.None,
)

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
     * Shares [palette] rather than carrying a swatch row of its own: the colours someone reaches for
     * are one property of the user (ID5), not one per tool. A colour mixed holding a pen is there
     * when a shape is drawn.
     */
    val shape: Flow<ShapeSettings> = store.data.map { prefs ->
        prefs[SHAPE]?.let(::decodeShape) ?: ShapeSettings()
    }

    /** Which ruler, and how big. Not whether it is out — see [RulerSettings]. */
    val ruler: Flow<RulerSettings> = store.data.map { prefs ->
        prefs[RULER]?.let(::decodeRuler) ?: RulerSettings()
    }

    /** How a table starts — see [TableSettings]. Shares [palette] for the reason [shape] does. */
    val table: Flow<TableSettings> = store.data.map { prefs ->
        prefs[TABLE]?.let(::decodeTable) ?: TableSettings()
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
            prefs[PALETTE] = penSettingsJson.encodeToString(
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

    /**
     * What the barrel button's click counts do — see [StylusButtonMap], which explains why this is a
     * property of the user rather than of the device it is configured on.
     */
    val stylusButtons: Flow<StylusButtonMap> = store.data.map { prefs ->
        prefs[STYLUS_BUTTONS]?.let(::decodeStylusButtons) ?: StylusButtonMap()
    }

    suspend fun setStylusButtons(map: StylusButtonMap) {
        store.edit { it[STYLUS_BUTTONS] = penSettingsJson.encodeToString(StylusButtonMap.serializer(), map) }
    }

    suspend fun setPen(index: Int, preset: PenPreset) {
        store.edit { it[key(index)] = penSettingsJson.encodeToString(PenPreset.serializer(), preset) }
    }

    suspend fun setEraser(settings: EraserSettings) {
        store.edit { it[ERASER] = penSettingsJson.encodeToString(EraserSettings.serializer(), settings) }
    }

    suspend fun setHighlighter(settings: HighlighterSettings) {
        store.edit {
            it[HIGHLIGHTER] = penSettingsJson.encodeToString(HighlighterSettings.serializer(), settings)
        }
    }

    suspend fun setShape(settings: ShapeSettings) {
        store.edit { it[SHAPE] = penSettingsJson.encodeToString(ShapeSettings.serializer(), settings) }
    }

    suspend fun setRuler(settings: RulerSettings) {
        store.edit { it[RULER] = penSettingsJson.encodeToString(RulerSettings.serializer(), settings) }
    }

    suspend fun setTable(settings: TableSettings) {
        store.edit { it[TABLE] = penSettingsJson.encodeToString(TableSettings.serializer(), settings) }
    }

    private fun decodePen(index: Int, text: String): PenPreset? = runCatching {
        val decoded = penSettingsJson.decodeFromString(PenPreset.serializer(), text)
        val hasThemeFlag = penSettingsJson.parseToJsonElement(text).jsonObject.containsKey("colorFollowsTheme")
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
        runCatching { penSettingsJson.decodeFromString(EraserSettings.serializer(), text) }.getOrNull()

    private fun decodeHighlighter(text: String): HighlighterSettings? =
        runCatching { penSettingsJson.decodeFromString(HighlighterSettings.serializer(), text) }.getOrNull()

    /**
     * A shape kind this build does not have — one added later, or one dropped from the picker —
     * falls back to the default rather than throwing away the width and colour beside it. That is
     * `coerceInputValues` doing its job; see the note on [penSettingsJson].
     */
    private fun decodeShape(text: String): ShapeSettings? =
        runCatching { penSettingsJson.decodeFromString(ShapeSettings.serializer(), text) }.getOrNull()

    /** A ruler kind this build does not have falls back to the default, as [decodeShape] does. */
    private fun decodeRuler(text: String): RulerSettings? =
        runCatching { penSettingsJson.decodeFromString(RulerSettings.serializer(), text) }.getOrNull()

    private fun decodeTable(text: String): TableSettings? =
        runCatching { penSettingsJson.decodeFromString(TableSettings.serializer(), text) }.getOrNull()

    /**
     * An action this build does not have — one added later, or one renamed — coerces to that field's
     * default and leaves the two bindings beside it alone. That is `coerceInputValues` again, and it
     * is why every field of [StylusButtonMap] carries a default: coercion has nothing to coerce *to*
     * without one.
     */
    private fun decodeStylusButtons(text: String): StylusButtonMap? =
        runCatching { penSettingsJson.decodeFromString(StylusButtonMap.serializer(), text) }.getOrNull()

    /**
     * A stored row shorter or longer than the current [PALETTE_SIZE] is trimmed rather than
     * rejected, so changing how many swatches fit is not a migration. An empty one is nothing to
     * show, which is the one case worth falling back to the shipped palette for.
     */
    private fun decodePalette(text: String): List<Int>? = runCatching {
        penSettingsJson.decodeFromString(ListSerializer(Int.serializer()), text)
            .take(PALETTE_SIZE)
            .ifEmpty { null }
    }.getOrNull()

    private companion object {
        fun key(index: Int) = stringPreferencesKey("pen_$index")

        val DRAW_WITH_FINGER = booleanPreferencesKey("draw_with_finger")
        val STYLUS_BUTTONS = stringPreferencesKey("stylus_buttons")
        val ERASER = stringPreferencesKey("eraser")
        val HIGHLIGHTER = stringPreferencesKey("highlighter")
        val SHAPE = stringPreferencesKey("shape")
        val RULER = stringPreferencesKey("ruler")
        val TABLE = stringPreferencesKey("table")
        val PALETTE = stringPreferencesKey("palette")
    }
}

/**
 * How every blob in this file is written and read.
 *
 * `ignoreUnknownKeys` covers a field this build does not have; `coerceInputValues` covers a *value* it
 * does not have — a pen kind added later, or a [StylusAction] renamed — which would otherwise throw and
 * lose the whole record over one setting. Same pair, for the same reason, as `DocumentJson`.
 *
 * At file level rather than in [PenSettingsStore]'s companion, and `internal` rather than private, so a
 * JVM test can exercise **this** configuration rather than a copy of it that would go on passing after
 * someone removed a flag from here. The store itself needs a `Context`, which puts it out of reach
 * until Robolectric lands (risk R10); this is the part of it that does not.
 */
internal val penSettingsJson: Json = Json {
    // The false value is meaningful: it distinguishes an explicitly chosen black/white from an old
    // preset that predates colorFollowsTheme, so it must be written as well.
    encodeDefaults = true
    ignoreUnknownKeys = true
    coerceInputValues = true
}
