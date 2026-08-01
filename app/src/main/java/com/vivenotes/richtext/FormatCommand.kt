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
) {
    fun has(mark: Mark): Boolean = mark in marks

    val textColor: Int? get() = marks.filterIsInstance<Mark.TextColor>().firstOrNull()?.argb
    val highlight: Int? get() = marks.filterIsInstance<Mark.Highlight>().firstOrNull()?.argb
}
