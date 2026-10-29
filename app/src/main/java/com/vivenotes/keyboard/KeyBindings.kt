package com.vivenotes.keyboard

import androidx.annotation.StringRes
import com.vivenotes.R

/**
 * A key a shortcut can use, named the way it is stored. `AndroidKeys.kt` maps the platform's key
 * codes onto these; a key missing here cannot be bound. [label] is how the key is printed on screen,
 * matching the desktop port's.
 */
enum class ShortcutKey(private val printed: String? = null) {
    A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z,
    Digit0("0"), Digit1("1"), Digit2("2"), Digit3("3"), Digit4("4"),
    Digit5("5"), Digit6("6"), Digit7("7"), Digit8("8"), Digit9("9"),
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    Tab, Space, Enter, Backspace, Delete, Insert, Home, End,
    PageUp("Page Up"), PageDown("Page Down"),
    Up("↑"), Down("↓"), Left("←"), Right("→"),
    Escape("Esc"),
    // A typographic minus, so Ctrl+− reads as the key and not as a dash between two names.
    Equals("="), Minus("−"), Plus("+"), Comma(","), Period("."), Slash("/"), Backslash("\\"),
    Semicolon(";"), Apostrophe("'"), Grave("`"), LeftBracket("["), RightBracket("]"),
    NumPad0("Num 0"), NumPad1("Num 1"), NumPad2("Num 2"), NumPad3("Num 3"), NumPad4("Num 4"),
    NumPad5("Num 5"), NumPad6("Num 6"), NumPad7("Num 7"), NumPad8("Num 8"), NumPad9("Num 9"),
    NumPadAdd("Num +"), NumPadSubtract("Num −"), NumPadMultiply("Num *"), NumPadDivide("Num /"),
    NumPadDot("Num ."), NumPadEnter("Num Enter");

    val label: String get() = printed ?: name

    /**
     * Whether this key needs Ctrl, Alt or Meta to be a shortcut. Alone, most keys type a character
     * or move the caret, and binding one would take that away from every text box. Function keys,
     * Delete, Esc and Tab may stand alone; a focused text box handles Delete before the canvas does.
     * A command that never runs while a text field has the keyboard may have any key: see
     * [ShortcutAction.accepts].
     */
    val needsModifier: Boolean
        get() = this != Tab && this != Escape && this != Delete && ordinal !in F1.ordinal..F12.ordinal
}

/** A key with the modifiers held for it: exactly these, so Ctrl+Shift+Z is not also Ctrl+Z. */
data class KeyChord(
    val key: ShortcutKey,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
) {
    /** "Ctrl+Shift+Z", as the Hardware pane prints it. Meta is the name Android's own helper uses. */
    val label: String get() = buildList {
        if (ctrl) add("Ctrl")
        if (shift) add("Shift")
        if (alt) add("Alt")
        if (meta) add("Meta")
        add(key.label)
    }.joinToString("+")

    /** Shift alone does not count: Shift+A types a capital. See [ShortcutKey.needsModifier]. */
    val usable: Boolean get() = !key.needsModifier || ctrl || alt || meta

    /**
     * The stored form, "Ctrl+Shift+Equals": the key by name, which a label such as "=" is not. Meta
     * is stored as the desktop port's "Super", so the two write one format.
     */
    fun encode(): String = buildString {
        if (ctrl) append("Ctrl+")
        if (shift) append("Shift+")
        if (alt) append("Alt+")
        if (meta) append("Super+")
        append(key.name)
    }

    companion object {
        /** Reads [encode]'s form; null for anything else. */
        fun decode(text: String): KeyChord? {
            val parts = text.trim().split('+')
            val key = ShortcutKey.entries.firstOrNull { it.name == parts.last() } ?: return null
            val modifiers = parts.dropLast(1)
            if (modifiers.any { it !in ModifierNames } || modifiers.toSet().size != modifiers.size) return null
            return KeyChord(key, ctrl = "Ctrl" in modifiers, shift = "Shift" in modifiers,
                alt = "Alt" in modifiers, meta = "Super" in modifiers)
        }

        private val ModifierNames = setOf("Ctrl", "Shift", "Alt", "Super")
    }
}

/** Where a shortcut is dispatched, which decides what may take the key before it. */
enum class ShortcutScope {
    /** Ahead of the focused view, wherever the focus is: `MainActivity.dispatchKeyEvent`. */
    Anywhere,

    /**
     * After the focused view leaves the key unused: `MainActivity.onKeyDown`. In the page title,
     * Ctrl+Z is the title's own undo.
     */
    Workspace,

    /** Only in the text box being edited, ahead of the text field's own keys: `OutlineEditText.onKeyDown`. */
    TextBox,

    /**
     * Where [Workspace] is, but only while no text field has the keyboard. Here a key that types
     * can be a shortcut: wherever it would be typed — a text box, the title, the search field — it
     * is a letter, and nothing else.
     */
    OutsideText,
}

/** The Hardware pane's headings, in its order. */
enum class ShortcutGroup(@param:StringRes val label: Int) {
    Pages(R.string.shortcut_group_pages),
    Edit(R.string.shortcut_group_edit),
    View(R.string.shortcut_group_view),
    Tools(R.string.shortcut_group_tools),
    Formatting(R.string.shortcut_group_formatting),
    Paragraph(R.string.shortcut_group_paragraph),
}

private fun ctrl(key: ShortcutKey) = KeyChord(key, ctrl = true)
private fun ctrlShift(key: ShortcutKey) = KeyChord(key, ctrl = true, shift = true)

/**
 * Every keyboard shortcut: what the Hardware pane lists, what the system's Meta + / helper lists and
 * what the app dispatches, from one table — the desktop port's, command for command. A shortcut that
 * works but is not listed is one nobody finds; one listed that does not work is worse.
 *
 * The first of [defaults] is the one shown; the rest are the same command as people also press it
 * (Ctrl+Shift+= is Ctrl++ on most keyboards, and the numpad has its own keys). [repeatable] says
 * whether holding the keys repeats the command: holding Ctrl+= should keep zooming, holding Ctrl+N
 * must not keep adding pages.
 */
enum class ShortcutAction(
    @param:StringRes val label: Int,
    val group: ShortcutGroup,
    val scope: ShortcutScope,
    val repeatable: Boolean,
    vararg chords: KeyChord,
) {
    Search(R.string.shortcut_search, ShortcutGroup.Pages, ShortcutScope.Anywhere, false, ctrl(ShortcutKey.F)),
    NewPage(R.string.shortcut_new_page, ShortcutGroup.Pages, ShortcutScope.Workspace, false, ctrl(ShortcutKey.N)),

    // A text box's own: its text and formatting are steps of the page's history, which the text
    // field's undo, knowing only characters, would garble. They run on the canvas as well.
    Undo(R.string.shortcut_undo, ShortcutGroup.Edit, ShortcutScope.TextBox, true, ctrl(ShortcutKey.Z)),
    // Ctrl+R is the desktop's own redo; Ctrl+Shift+Z is Android's, and works as well.
    Redo(R.string.shortcut_redo, ShortcutGroup.Edit, ShortcutScope.TextBox, true, ctrl(ShortcutKey.R), ctrlShift(ShortcutKey.Z)),
    DeleteSelection(R.string.shortcut_delete_selection, ShortcutGroup.Edit, ShortcutScope.Workspace, false, KeyChord(ShortcutKey.Delete)),
    Cut(R.string.shortcut_cut, ShortcutGroup.Edit, ShortcutScope.TextBox, false, ctrl(ShortcutKey.X)),
    Copy(R.string.shortcut_copy, ShortcutGroup.Edit, ShortcutScope.TextBox, false, ctrl(ShortcutKey.C)),
    Paste(R.string.shortcut_paste, ShortcutGroup.Edit, ShortcutScope.TextBox, true, ctrl(ShortcutKey.V)),
    PastePlainText(R.string.shortcut_paste_plain_text, ShortcutGroup.Edit, ShortcutScope.TextBox, true, ctrlShift(ShortcutKey.V)),
    SelectAll(R.string.shortcut_select_all, ShortcutGroup.Edit, ShortcutScope.TextBox, false, ctrl(ShortcutKey.A)),

    ZoomIn(R.string.shortcut_zoom_in, ShortcutGroup.View, ShortcutScope.Workspace, true, ctrl(ShortcutKey.Equals),
        ctrlShift(ShortcutKey.Equals), ctrl(ShortcutKey.Plus), ctrl(ShortcutKey.NumPadAdd)),
    ZoomOut(R.string.shortcut_zoom_out, ShortcutGroup.View, ShortcutScope.Workspace, true, ctrl(ShortcutKey.Minus),
        ctrl(ShortcutKey.NumPadSubtract)),
    ActualSize(R.string.shortcut_actual_size, ShortcutGroup.View, ShortcutScope.Workspace, false, ctrl(ShortcutKey.Digit0),
        ctrl(ShortcutKey.NumPad0)),

    SelectTool(R.string.shortcut_select_tool, ShortcutGroup.Tools, ShortcutScope.Anywhere, false, KeyChord(ShortcutKey.Escape)),
    // The Document tab's Text button. A bare letter, so it can only run where it would type nothing.
    TextTool(R.string.shortcut_text_tool, ShortcutGroup.Tools, ShortcutScope.OutsideText, false, KeyChord(ShortcutKey.I)),

    Bold(R.string.shortcut_bold, ShortcutGroup.Formatting, ShortcutScope.TextBox, false, ctrl(ShortcutKey.B)),
    Italic(R.string.shortcut_italic, ShortcutGroup.Formatting, ShortcutScope.TextBox, false, ctrl(ShortcutKey.I)),
    Underline(R.string.shortcut_underline, ShortcutGroup.Formatting, ShortcutScope.TextBox, false, ctrl(ShortcutKey.U)),

    // In a table cell these walk the grid, and indent only where the walk runs out.
    Indent(R.string.shortcut_indent, ShortcutGroup.Paragraph, ShortcutScope.TextBox, true, KeyChord(ShortcutKey.Tab)),
    Outdent(R.string.shortcut_outdent, ShortcutGroup.Paragraph, ShortcutScope.TextBox, true, KeyChord(ShortcutKey.Tab, shift = true)),
    // A new paragraph without the list's marker: Enter would make another item.
    ExitList(R.string.shortcut_exit_list, ShortcutGroup.Paragraph, ShortcutScope.TextBox, true, ctrl(ShortcutKey.Enter),
        ctrl(ShortcutKey.NumPadEnter));

    val defaults: List<KeyChord> = chords.toList()

    /**
     * Whether [chord] may run this command. A key that types needs Ctrl, Alt or Meta, except for a
     * command that never runs while a text field has the keyboard, where it types nothing.
     */
    fun accepts(chord: KeyChord): Boolean = chord.usable || scope == ShortcutScope.OutsideText

    /**
     * A text box command that also runs where [ShortcutScope.Workspace] is: Ctrl+Z undoes the last
     * step wherever it was. The clipboard's stay in the text box.
     */
    val alsoOnCanvas: Boolean get() = this == Undo || this == Redo

    companion object {
        /** Grouped as the Hardware pane shows them, groups and rows in declaration order. */
        val groups: List<Pair<ShortcutGroup, List<ShortcutAction>>>
            get() = entries.groupBy { it.group }.toList()
    }
}

/** What one key press means at one point of dispatch. */
sealed interface ShortcutDecision {
    /** Run the command; the key is used up if it runs. */
    data class Run(val action: ShortcutAction) : ShortcutDecision

    /** Use the key up and do nothing. */
    data object Swallow : ShortcutDecision

    /** Not a shortcut here: let the key go on. */
    data object Pass : ShortcutDecision
}

/**
 * Which keys run which [ShortcutAction]: the defaults, with the user's changes on top. A chord runs
 * at most one action — giving it to one takes it from any other — so the table never has to choose.
 */
class KeyBindings private constructor(private val overrides: Map<ShortcutAction, List<KeyChord>>) {

    /** The chords that run [action]: its defaults until changed, and none once disabled. */
    fun chords(action: ShortcutAction): List<KeyChord> = overrides[action] ?: action.defaults

    /** The chord shown for [action], or null when it is disabled. */
    fun primary(action: ShortcutAction): KeyChord? = chords(action).firstOrNull()

    fun isCustomized(action: ShortcutAction): Boolean = action in overrides

    val isCustomized: Boolean get() = overrides.isNotEmpty()

    /** The user's changes, for storing: each changed action's chords, empty when disabled. */
    val changes: Map<ShortcutAction, List<KeyChord>> get() = overrides

    fun actionFor(chord: KeyChord): ShortcutAction? = ShortcutAction.entries.firstOrNull { chord in chords(it) }

    /** The other action [chord] would be taken from if [action] were given it. */
    fun conflict(action: ShortcutAction, chord: KeyChord): ShortcutAction? =
        actionFor(chord)?.takeIf { it != action }

    /** [action] runs on [chord] alone from now on, or on nothing when [chord] is null. */
    fun rebind(action: ShortcutAction, chord: KeyChord?): KeyBindings = assign(action, listOfNotNull(chord))

    /** [action] gets its defaults back, taking them from any action that was given one since. */
    fun reset(action: ShortcutAction): KeyBindings = assign(action, action.defaults)

    fun resetAll(): KeyBindings = Default

    /**
     * What [chord] means where [at] dispatches, [repeat] being whether it is a held key's repeat.
     *
     * A text box claims more than its own commands. The text field has its own Ctrl+C and Tab, and
     * would take them the moment a text box command moved off them: a text box default that runs
     * nothing any more is used up rather than passed on, and one given to a workspace command runs
     * that command here, since it would never get past the text field to where it is dispatched.
     * One given to a command that never runs in a text field runs nothing here, so it is used up.
     */
    fun decide(chord: KeyChord, at: ShortcutScope, repeat: Boolean = false): ShortcutDecision {
        val action = actionFor(chord)
        val claimed = when (at) {
            ShortcutScope.TextBox -> {
                val textDefault = TextBoxDefaults.contains(chord)
                if (action == null || action.scope == ShortcutScope.OutsideText)
                    return if (textDefault) ShortcutDecision.Swallow else ShortcutDecision.Pass
                action.scope == ShortcutScope.TextBox || (textDefault && action.scope == ShortcutScope.Workspace)
            }
            ShortcutScope.Workspace -> action?.scope == at || action?.alsoOnCanvas == true
            else -> action?.scope == at
        }
        if (!claimed || action == null) return ShortcutDecision.Pass
        return if (repeat && !action.repeatable) ShortcutDecision.Swallow else ShortcutDecision.Run(action)
    }

    private fun assign(action: ShortcutAction, chords: List<KeyChord>): KeyBindings {
        val taken = chords.toSet()
        return of(ShortcutAction.entries.associateWith { entry ->
            if (entry == action) chords else chords(entry).filterNot { it in taken }
        })
    }

    override fun equals(other: Any?): Boolean = other is KeyBindings && other.overrides == overrides

    override fun hashCode(): Int = overrides.hashCode()

    override fun toString(): String = "KeyBindings(${overrides.entries.joinToString { (action, chords) ->
        "${action.name}=${chords.joinToString("|") { it.label }}" }})"

    companion object {
        val Default = KeyBindings(emptyMap())

        private val TextBoxDefaults: Set<KeyChord> = ShortcutAction.entries
            .filter { it.scope == ShortcutScope.TextBox }.flatMap { it.defaults }.toSet()

        /**
         * Bindings from stored [changes], cleaned as they are read: a change with a chord its action
         * does not [accept][ShortcutAction.accepts] is ignored, a chord claimed twice stays with the
         * later action in declaration order, and a change back to an action's defaults is no change.
         */
        fun of(changes: Map<ShortcutAction, List<KeyChord>>): KeyBindings {
            val chords = ShortcutAction.entries.associateWith { it.defaults }.toMutableMap()
            for (action in ShortcutAction.entries) {
                val assigned = changes[action]?.takeIf { list -> list.all(action::accepts) }?.distinct() ?: continue
                val taken = assigned.toSet()
                for (other in ShortcutAction.entries) {
                    chords[other] = if (other == action) assigned else chords.getValue(other).filterNot { it in taken }
                }
            }
            return KeyBindings(chords.filter { (action, list) -> list != action.defaults })
        }

        /**
         * The stored form: only the changes, one entry per command — `{"Undo":["Ctrl+Shift+U"]}`, or
         * an empty list for a disabled one — so a default a later build changes reaches everyone who
         * never touched it. The desktop port writes the same pairs to a properties file.
         */
        fun encode(bindings: KeyBindings): Map<String, List<String>> =
            bindings.changes.entries.associate { (action, chords) -> action.name to chords.map { it.encode() } }

        /** Reads [encode]'s form. A command this build does not know, or a chord it cannot read, is skipped. */
        fun decode(stored: Map<String, List<String>>): KeyBindings = of(
            ShortcutAction.entries.mapNotNull { action ->
                val chords = stored[action.name]?.map(KeyChord::decode) ?: return@mapNotNull null
                if (chords.any { it == null }) null else action to chords.filterNotNull()
            }.toMap(),
        )
    }
}
