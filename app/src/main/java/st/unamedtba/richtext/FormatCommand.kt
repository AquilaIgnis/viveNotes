package st.unamedtba.richtext

import st.unamedtba.model.Align
import st.unamedtba.model.BlockType
import st.unamedtba.model.Mark

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

    /**
     * Delegated to the platform widget's own handlers, which already move styled text through the
     * clipboard. Reimplementing cut/copy/paste would lose that for no gain.
     */
    data class Clipboard(val action: ClipboardAction) : FormatCommand
}

enum class ClipboardAction { Cut, Copy, Paste, PasteAsPlainText }

/** What the editor reports back, so the ribbon can show accurate state. */
data class SelectionState(
    val marks: Set<Mark> = emptySet(),
    val blockType: BlockType = BlockType.Paragraph,
    val align: Align = Align.Start,
    val indent: Int = 0,
    val hasSelection: Boolean = false,
) {
    fun has(mark: Mark): Boolean = mark in marks

    val textColor: Int? get() = marks.filterIsInstance<Mark.TextColor>().firstOrNull()?.argb
    val highlight: Int? get() = marks.filterIsInstance<Mark.Highlight>().firstOrNull()?.argb
    val fontSize: Int? get() = marks.filterIsInstance<Mark.FontSize>().firstOrNull()?.sp
    val fontFamily: String? get() = marks.filterIsInstance<Mark.FontFamily>().firstOrNull()?.name
}
