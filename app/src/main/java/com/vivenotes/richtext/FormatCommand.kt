package com.vivenotes.richtext

import com.vivenotes.model.Align
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark

/**
 * What the ribbon sends to the editor.
 *
 * The ribbon never holds a reference to the view — it emits commands and renders from
 * [SelectionState]. That keeps the Compose/View boundary one-directional in each direction and
 * means the ribbon's toggle states always describe the real caret rather than a guess.
 */
sealed interface FormatCommand {
    /** Flip a mark that is either on or off. */
    data class ToggleMark(val mark: Mark) : FormatCommand

    /** Apply a mark that carries a value — colour, size, font, link. */
    data class SetMark(val mark: Mark) : FormatCommand

    /** Remove a mark by kind, whatever its value. */
    data class ClearMark(val mark: Mark) : FormatCommand

    data class SetBlockType(val type: BlockType) : FormatCommand

    data class Indent(val delta: Int) : FormatCommand

    data class SetAlign(val align: Align) : FormatCommand

    data object ClearFormatting : FormatCommand

    /** Puts the caret and IME away before a Draw-tab tool starts consuming page gestures. */
    data object DeactivateTextInput : FormatCommand

    /**
     * Drops whatever is selected on the canvas, because the user picked a different tool —
     * `memory/diagram.md`, Prime Object Class: *"Selecting any other tool removes selection of
     * object."*
     *
     * The canvas twin of [DeactivateTextInput], emitted from the same line of `selectTool` and for
     * the same reason: a tool change means the page's gestures now belong to something else, so what
     * the previous tool left behind is put away first. Text state was already doing this; the
     * object selection was not, so a dashed box and a floating toolbar stayed over the page while
     * you drew on it.
     *
     * **A command rather than a `LaunchedEffect` on the armed tool**, which is what this looked like
     * it wanted to be. Placing an object *disarms* the tool that placed it — `insertShape`,
     * `insertTable` and `insertEquation` all set `DrawTool.None` — and then selects what they just
     * made, so a clear keyed on the tool's *value* would fire on that transition and wipe the
     * selection the insert had only just handed over. `selectTool` is the user picking a tool, which
     * is what the rule is actually about, and a one-shot event down the existing bus cannot race the
     * state write that follows it.
     */
    data object ClearCanvasSelection : FormatCommand

    /** Keeps the current editor/caret alive while the focusable equation panel is open. */
    data object RetainEquationTarget : FormatCommand

    /** Releases a retained target when equation entry is cancelled or dismissed. */
    data object ReleaseEquationTarget : FormatCommand

    /** Inserts a new equation, or replaces the equation at the retained caret. */
    data class InsertEquation(val latex: String) : FormatCommand

    /**
     * Delegated to the platform widget's own handlers, which already move styled text through the
     * clipboard. Reimplementing cut/copy/paste would lose that for no gain.
     */
    data class Clipboard(val action: ClipboardAction) : FormatCommand

    /**
     * Selects everything in the focused container — the TextBox toolkit's own action,
     * `memory/textBoxPlan.md` TD4.
     *
     * A command rather than a call on the view, even though the bar is raised a few dp from the
     * editor it is about: AD6's whole point is that there is one way to drive the editor, and a
     * second one that happens to be shorter is how the two drift apart.
     */
    data object SelectAll : FormatCommand
}

enum class ClipboardAction { Cut, Copy, Paste, PasteAsPlainText }

/** What the editor reports back, so the ribbon can show accurate state. */
data class SelectionState(
    val marks: Set<Mark> = emptySet(),
    val blockType: BlockType = BlockType.Paragraph,
    val align: Align = Align.Start,
    val indent: Int = 0,
    val hasSelection: Boolean = false,
    /**
     * The size the selected text is drawn at, or the size the next character typed will be when
     * nothing is selected. Null where a selection mixes sizes and there is no one answer.
     *
     * Resolved by the editor rather than read out of [marks], because "no size mark" means both
     * "drawn at the editor's base size" and "several sizes at once", and a ribbon that cannot tell
     * those apart shows a number for text that has no single size.
     */
    val fontSize: Int? = null,

    /** The family, on the same terms as [fontSize]. */
    val fontFamily: String? = null,

    /** Source of the equation at the selection/caret, if there is one to edit. */
    val equation: String? = null,

    /** An equation needs a real insertion target; a page by itself is not enough. */
    val editorFocused: Boolean = false,
) {
    fun has(mark: Mark): Boolean = mark in marks

    val textColor: Int? get() = marks.filterIsInstance<Mark.TextColor>().firstOrNull()?.argb
    val highlight: Int? get() = marks.filterIsInstance<Mark.Highlight>().firstOrNull()?.argb
}
