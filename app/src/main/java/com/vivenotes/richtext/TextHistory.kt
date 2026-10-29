package com.vivenotes.richtext

import com.vivenotes.model.Block

/** A selection in an editor's text: a caret when [start] and [end] are the same. */
data class TextSelection(val start: Int, val end: Int = start) {
    val min: Int get() = minOf(start, end)
    val max: Int get() = maxOf(start, end)
    val collapsed: Boolean get() = start == end

    fun clamped(length: Int): TextSelection =
        TextSelection(start.coerceIn(0, length), end.coerceIn(0, length))
}

/**
 * One undo step of a text box's or table cell's text: typing, or a command such as Bold, a
 * highlight, a list or Center. Undo puts [before] back and Redo [after], each with the selection it
 * had, and leave the rest of the page as it stands.
 */
data class TextHistoryChange(
    val outlineId: String,
    val before: List<Block>,
    val after: List<Block>,
    val selectionBefore: TextSelection,
    val selectionAfter: TextSelection,
)

/**
 * Typing the last text step may still take in. Characters typed on from [caret] join it until the
 * next word begins, and deleting carries on the same way; a click elsewhere, a command or an undo
 * ends it, so the next keystroke starts a step of its own.
 */
data class TypingRun(val outlineId: String, val deleting: Boolean, val caret: TextSelection)

/** What one keystroke's change to the text was, as far as grouping typing into steps goes. */
data class Keystroke(val deleting: Boolean, val joinable: Boolean) {
    companion object {
        /**
         * The keystroke that turned [old] into [new] with [selection] before it. Only characters
         * inserted at a caret or deleted next to one can join a step: replacing a selection begins one.
         */
        fun of(old: String, new: String, selection: TextSelection): Keystroke {
            if (!selection.collapsed) {
                val kept = old.length - (selection.max - selection.min)
                return Keystroke(deleting = new.length <= kept, joinable = false)
            }
            if (new.length < old.length) return Keystroke(deleting = true, joinable = true)
            val at = selection.start
            val added = new.length - old.length
            val inserted = added > 0 && new.regionMatches(0, old, 0, at) &&
                new.regionMatches(at + added, old, at, old.length - at)
            if (!inserted) return Keystroke(deleting = false, joinable = false)
            // A word typed after a space or a new line is a step of its own; the space stays with
            // the word before it.
            val startsWord = !new[at].isWhitespace() && at > 0 && old[at - 1].isWhitespace()
            return Keystroke(deleting = false, joinable = !startsWord)
        }
    }
}

/**
 * One change an editor made to its blocks, as it reports it for the undo history: what the text was
 * and became, the selection on either side, and — when typing made it rather than a command — the
 * [keystroke], which groups typing by word. [composing] says an input method was still composing
 * when the change began, and each change to what it composes joins the step it began.
 */
data class TextEdit(
    val before: List<Block>,
    val after: List<Block>,
    val selectionBefore: TextSelection,
    val selectionAfter: TextSelection,
    val keystroke: Keystroke?,
    val composing: Boolean,
)
