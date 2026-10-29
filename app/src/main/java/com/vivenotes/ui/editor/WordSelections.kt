package com.vivenotes.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.text.SpannableStringBuilder
import com.vivenotes.model.Mark
import com.vivenotes.richtext.ClipboardAction
import com.vivenotes.richtext.FormatCommand
import com.vivenotes.richtext.OutlineEditText
import com.vivenotes.richtext.SelectionState
import com.vivenotes.richtext.TextEdit
import com.vivenotes.richtext.TextSelection

data class SelectedTextRange(val containerId: String, val range: TextSelection)

/** Transient selections in one text box or in the cells of one table; the last range is active. */
data class WordSelections(val ownerId: String, val ranges: List<SelectedTextRange>)

/**
 * [group] with the word at [range] in [id] added — or taken away, when it is already one of them.
 * The first word joins [focused], the editor's own selection, when that is in [owner] too. Null
 * when nothing is left selected.
 */
internal fun addWordSelection(
    group: WordSelections?,
    owner: String,
    id: String,
    range: TextSelection,
    focused: SelectedTextRange?,
): WordSelections? {
    val current = group?.takeIf { it.ownerId == owner }
    val seed = current?.ranges ?: listOfNotNull(focused?.takeIf { !it.range.collapsed }
        ?.let { it.copy(range = TextSelection(it.range.min, it.range.max)) })
    val target = SelectedTextRange(id, TextSelection(range.min, range.max))
    val ranges = if (current != null && target in seed) seed - target else {
        val overlapping = seed.filter { it.containerId == id && it.range.min < range.max && range.min < it.range.max }
        val merged = TextSelection((overlapping.map { it.range.min } + range.min).min(),
            (overlapping.map { it.range.max } + range.max).max())
        seed.filterNot { it in overlapping || it.range.collapsed } + SelectedTextRange(id, merged)
    }
    return if (ranges.isEmpty()) null else WordSelections(owner, ranges)
}

/** [group]'s ranges in reading order: the owner's containers in [order], each left to right. */
internal fun orderedTextRanges(group: WordSelections, order: List<String>): List<SelectedTextRange> =
    group.ranges.sortedWith(compareBy({ order.indexOf(it.containerId) }, { it.range.min }))

/** What one edit of the active range did, to be done at every range. */
internal sealed interface RangeEdit {
    /** The range's text was replaced by [text] — typed over, or typed into at a caret. */
    data class Replace(val text: String) : RangeEdit

    /** [count] characters went from beside a caret, before it when [backwards]. */
    data class Delete(val count: Int, val backwards: Boolean) : RangeEdit
}

/**
 * What turning [old] into [new] did to [range], which the edit started from with the caret ending
 * at [after]: null for anything else, such as an input method rewriting the word behind the caret.
 */
internal fun rangeEdit(old: String, new: String, range: TextSelection, after: TextSelection): RangeEdit? {
    if (old == new) return null
    val clamped = range.clamped(old.length)
    val prefix = old.substring(0, clamped.min)
    val suffix = old.substring(clamped.max)
    if (new.length >= prefix.length + suffix.length && new.startsWith(prefix) && new.endsWith(suffix)) {
        return RangeEdit.Replace(new.substring(prefix.length, new.length - suffix.length))
    }
    if (clamped.collapsed && after.collapsed && new.length < old.length) {
        return RangeEdit.Delete(old.length - new.length, backwards = after.start < clamped.start)
    }
    return null
}

/**
 * Words selected together with Ctrl+double-click, in one text box or across the cells of one table:
 * what is selected, what draws it, and what every command does to all of it at once.
 *
 * An `EditText` has one selection, so the active range — the last one added — is the focused
 * editor's own and the rest are drawn beside it ([OutlineEditText.extraSelections]). A command is
 * run at every range, in every editor holding one, as one step of Undo; typing at the active range
 * is typed at the others too.
 *
 * Any other press on an editor, a caret moved, the focus leaving for an editor outside the group, or
 * a tool picked up puts the words down.
 */
internal class WordSelectionController(
    private val editors: PageEditors,
    /** The text box a container is, or the table a cell is in; null where words cannot be selected. */
    private val ownerOf: (String) -> String?,
    /** An owner's containers in reading order: a box alone, or a table's cells row by row. */
    private val readingOrder: (String) -> List<String>,
    /** Runs what it is given as one step of Undo. */
    private val together: (() -> Unit) -> Unit,
    /** Records an editor's change for the undo history. */
    private val record: (String, TextEdit) -> Unit,
    private val clipboard: () -> ClipboardManager?,
    /** The ribbon's state, which reads the marks every selected word has in common. */
    private val publish: (SelectionState) -> Unit,
) {
    var group: WordSelections? = null
        private set

    /** True while this moves selections and edits text itself, which none of its listeners react to. */
    private var busy = false
    private var lastState = SelectionState()

    private val primary: SelectedTextRange? get() = group?.ranges?.lastOrNull()

    /** Only a group of several is anything but the editor's ordinary selection. */
    private val active: Boolean get() = (group?.ranges?.size ?: 0) > 1

    fun onCtrlDoubleClick(id: String, range: TextSelection) {
        val owner = ownerOf(id) ?: return
        val focused = editors.byId.entries.firstOrNull { it.value.hasFocus() }
            ?.takeIf { ownerOf(it.key) == owner }
            ?.let { (focusedId, editor) -> SelectedTextRange(focusedId, editor.selection()) }
        val next = addWordSelection(group, owner, id, range, focused)
        group = next
        val target = next?.ranges?.last() ?: SelectedTextRange(id, TextSelection(range.max))
        quietly {
            editors.byId[target.containerId]?.let { editor ->
                editor.requestFocus()
                editor.setSelection(target.range.min, target.range.max)
            }
        }
        redraw()
    }

    fun clear() {
        if (group == null) return
        group = null
        redraw()
    }

    fun onPlainPress() = clear()

    fun onSelectionMoved(id: String, selection: TextSelection) {
        if (busy) return
        val active = primary ?: return
        if (id != active.containerId || selection != active.range) clear()
    }

    fun onFocused(id: String) {
        if (!busy && primary?.containerId?.let { it != id } == true) clear()
    }

    /** The focused editor's state, with the marks every selected word has in common. */
    fun decorate(state: SelectionState): SelectionState {
        lastState = state
        if (!active) return state
        return state.copy(marks = commonMarks(), hasSelection = true)
    }

    /** An editor's change: recorded, and — when typed at the active range — typed at every other. */
    fun onTextEdited(id: String, edit: TextEdit) {
        val current = group
        val active = primary
        if (busy || !this.active || current == null || active == null || edit.keystroke == null) {
            record(id, edit)
            return
        }
        val old = edit.before.joinToString("\n") { it.editorText }
        val new = edit.after.joinToString("\n") { it.editorText }
        val change = rangeEdit(old, new, active.range, edit.selectionAfter)
            .takeIf { id == active.containerId && edit.selectionBefore == active.range }
        if (change == null) {
            record(id, edit)
            clear()
            return
        }
        val shift = new.length - old.length
        // The others, in what the editor holds now: past the active range, the text has moved.
        val others = current.ranges.dropLast(1).map { range ->
            if (range.containerId == id && range.range.min >= active.range.max) {
                range.copy(range = TextSelection(range.range.start + shift, range.range.end + shift))
            } else {
                range
            }
        }
        val carets = mutableMapOf<SelectedTextRange, SelectedTextRange>()
        together {
            record(id, edit)
            quietly {
                others.groupBy { it.containerId }.forEach { (containerId, ranges) ->
                    carets += ranges.zip(edit(containerId, ranges, change))
                }
            }
        }
        // Read back rather than taken from the edit: a word before it in the same box moved it.
        val caret = editors.byId[id]?.selection() ?: edit.selectionAfter
        group = current.copy(ranges = others.map { carets[it] ?: it } + SelectedTextRange(id, caret))
        redraw()
    }

    /** Takes [command] for every selected word, when several are; false leaves it to the editor. */
    fun intercept(command: FormatCommand): Boolean {
        val current = group?.takeIf { active } ?: return false
        when (command) {
            is FormatCommand.ToggleMark -> {
                // Set everywhere unless every word already has it, rather than flipped word by word.
                val on = command.mark in commonMarks()
                together {
                    quietly {
                        current.byContainer { editor, ranges ->
                            val targets = if (on) ranges else ranges.filter { command.mark !in editor.marksIn(it) }
                            if (targets.isNotEmpty()) editor.applyAt(command, targets, paragraphs = false)
                        }
                    }
                }
            }
            is FormatCommand.SetMark, is FormatCommand.ClearMark, FormatCommand.ClearFormatting ->
                applyEverywhere(current, command, paragraphs = false)
            is FormatCommand.SetBlockType, is FormatCommand.SetAlign, is FormatCommand.Indent ->
                applyEverywhere(current, command, paragraphs = true)
            is FormatCommand.Clipboard -> when (command.action) {
                ClipboardAction.Copy -> copy(current)
                ClipboardAction.Cut -> {
                    copy(current)
                    replaceEverywhere(current, "")
                }
                ClipboardAction.Paste, ClipboardAction.PasteAsPlainText -> {
                    val item = clipboard()?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return true
                    val context = editors.byId[current.ranges.last().containerId]?.context ?: return true
                    val pasted = if (command.action == ClipboardAction.Paste) item.coerceToStyledText(context)
                        else item.coerceToText(context)
                    replaceEverywhere(current, pasted)
                }
            }
            // One target only — the active word — and selecting all is selecting something else.
            else -> {
                clear()
                return false
            }
        }
        publish(decorate(lastState))
        return true
    }

    private fun applyEverywhere(current: WordSelections, command: FormatCommand, paragraphs: Boolean) = together {
        quietly { current.byContainer { editor, ranges -> editor.applyAt(command, ranges, paragraphs) } }
    }

    /** Puts [content] in place of every word, leaving a caret where each was for what is typed next. */
    private fun replaceEverywhere(current: WordSelections, content: CharSequence) {
        val carets = mutableMapOf<SelectedTextRange, SelectedTextRange>()
        together {
            quietly {
                current.ranges.groupBy { it.containerId }.forEach { (id, ranges) ->
                    val editor = editors.byId[id] ?: return@forEach
                    carets += ranges.zip(editor.replaceAt(ranges.map { it.range }, content).map { SelectedTextRange(id, it) })
                }
            }
        }
        val next = current.copy(ranges = current.ranges.map { carets[it] ?: it })
        group = next
        val caret = next.ranges.last()
        quietly { editors.byId[caret.containerId]?.setSelection(caret.range.start) }
        redraw()
    }

    private fun copy(current: WordSelections) {
        val joined = SpannableStringBuilder()
        orderedTextRanges(current, readingOrder(current.ownerId)).forEach { range ->
            val text = editors.byId[range.containerId]?.textIn(range.range) ?: return@forEach
            if (text.isEmpty()) return@forEach
            if (joined.isNotEmpty()) joined.append('\n')
            joined.append(text)
        }
        if (joined.isNotEmpty()) clipboard()?.setPrimaryClip(ClipData.newPlainText(null, joined))
    }

    /** Does [change] at each of [ranges] in [id]'s editor, and returns where each caret is after. */
    private fun edit(id: String, ranges: List<SelectedTextRange>, change: RangeEdit): List<SelectedTextRange> {
        val editor = editors.byId[id] ?: return ranges
        val length = editor.text?.length ?: 0
        val targets = ranges.map { selected ->
            val range = selected.range
            when (change) {
                is RangeEdit.Replace -> range
                is RangeEdit.Delete -> if (!range.collapsed) range
                    else if (change.backwards) TextSelection((range.start - change.count).coerceAtLeast(0), range.start)
                    else TextSelection(range.start, (range.start + change.count).coerceAtMost(length))
            }
        }
        val content = (change as? RangeEdit.Replace)?.text.orEmpty()
        return editor.replaceAt(targets, content).map { SelectedTextRange(id, it) }
    }

    private fun commonMarks(): Set<Mark> {
        val current = group ?: return emptySet()
        val marks = current.ranges.mapNotNull { editors.byId[it.containerId]?.marksIn(it.range) }
        return marks.firstOrNull()?.let { first -> marks.drop(1).fold(first) { common, next -> common intersect next } }.orEmpty()
    }

    private inline fun WordSelections.byContainer(action: (OutlineEditText, List<TextSelection>) -> Unit) {
        ranges.groupBy { it.containerId }.forEach { (id, ranges) ->
            editors.byId[id]?.let { action(it, ranges.map(SelectedTextRange::range)) }
        }
    }

    /** Draws each editor's words but the one its own selection already shows. */
    private fun redraw() {
        val current = group?.takeIf { active }
        val shown = primary
        editors.byId.forEach { (id, editor) ->
            editor.extraSelections = current?.ranges.orEmpty()
                .filter { it.containerId == id && !(it == shown && editor.hasFocus()) }
                .map { it.range }
        }
        publish(decorate(lastState))
    }

    private inline fun <T> quietly(body: () -> T): T {
        val was = busy
        busy = true
        try {
            return body()
        } finally {
            busy = was
        }
    }

    private fun OutlineEditText.selection() =
        TextSelection(selectionStart.coerceAtLeast(0), selectionEnd.coerceAtLeast(0))
}
