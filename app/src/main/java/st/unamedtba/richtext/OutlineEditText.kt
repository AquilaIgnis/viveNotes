package st.unamedtba.richtext

import android.content.Context
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import st.unamedtba.model.Align
import st.unamedtba.model.Block
import st.unamedtba.model.BlockType
import st.unamedtba.model.Mark
import kotlin.math.roundToInt

/**
 * The note canvas.
 *
 * An outline's blocks all live in one [EditText] as newline-separated paragraphs. Using the
 * platform text widget means IME behaviour, selection handles, magnifier, accessibility and
 * spell-check are inherited rather than reimplemented — which is the whole reason the editor is a
 * View inside a Compose app rather than a Compose text field.
 */
class OutlineEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : EditText(context, attrs) {

    var editorStyle: EditorStyle = EditorStyle(
        indentStepPx = 48,
        listGapPx = 48,
        bulletRadiusPx = 6,
        accentColor = 0xFF4CAF50.toInt(),
        codeBackgroundColor = 0x22FFFFFF,
        quoteColor = 0xFF4CAF50.toInt(),
    )

    /** Called after any user edit, debounced by the caller. */
    var onBlocksChanged: ((List<Block>) -> Unit)? = null

    var onSelectionStateChanged: ((SelectionState) -> Unit)? = null

    /**
     * Fired when a [FormatCommand.SetMark] lands on a collapsed caret.
     *
     * That is the case where the mark describes what the user is about to type rather than editing
     * anything, so it is also what should become the editor's default. Reported from here because
     * this is the only place that knows the real selection at the moment the command is applied —
     * inferring it from the last [SelectionState] the ribbon saw is a race.
     */
    var onMarkArmed: ((Mark) -> Unit)? = null

    /**
     * The editor's default font and size, as marks to stamp onto text that has nothing to inherit.
     *
     * Deliberately not applied as the view's base typeface and text size. The base is shared by
     * every character with no span of its own, so driving it from the default retroactively
     * restyled text the user wrote under a previous one. Stamping instead means a default only ever
     * reaches text typed while it was in force, and existing content is left exactly as written.
     *
     * Empty when the default matches the view's fixed base, so the common case adds no marks to the
     * document at all. It is also the last thing consulted — see [carryFontForward].
     */
    var defaultMarks: Set<Mark> = emptySet()

    /**
     * Font id of the view's own typeface, so text carrying no font mark can be named rather than
     * reported as nothing.
     *
     * Declared instead of derived because a [android.graphics.Typeface] cannot be mapped back to
     * the id a document would store. Set wherever the base typeface is set; fixed, never the user's
     * default, for the reason [defaultMarks] gives.
     */
    var baseFontFamily: String = "sans-serif"

    /**
     * The size text with no size mark is drawn at, read back off the view so the two can never
     * disagree. [android.util.TypedValue.deriveDimension] inverts the density and font-scale the
     * size was set through, so this is the number the user picked, not a pixel count.
     */
    private val baseFontSize: Int
        get() = TypedValue
            .deriveDimension(TypedValue.COMPLEX_UNIT_SP, textSize, resources.displayMetrics)
            .roundToInt()

    /**
     * Marks queued by toggling with no selection. Android has no notion of "formatting about to be
     * typed", so it is held here and applied to the next inserted characters.
     */
    private var pendingMarks: MutableSet<Mark> = mutableSetOf()

    /**
     * Marks to strip from whatever gets typed next.
     *
     * Needed because inline spans are end-inclusive so typing continues the current formatting.
     * Without an explicit suppression set there is no way to turn a mark off mid-word: the caret
     * sits inside the span, so the span simply grows over the new text.
     */
    private var suppressedMarks: MutableSet<Mark> = mutableSetOf()

    /**
     * What the text around the insertion point hands down, sampled before the buffer changes.
     *
     * It has to be read in [TextWatcher.beforeTextChanged]: by the time the edit has landed, the
     * character behind the insertion point is one the user has just typed, so every keystroke would
     * inherit from itself and the first one would decide the rest of the word.
     */
    private var inheritedAtInsert: Set<Mark> = emptySet()

    /** Guards the [TextWatcher] against the edits that normalisation itself makes. */
    private var suppressWatcher = false

    private val watcher = object : TextWatcher {
        private var insertStart = 0
        private var insertCount = 0

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            inheritedAtInsert = if (!suppressWatcher && after > 0 && s is Spanned) {
                SpannableCodec.inheritedMarks(s, start)
            } else {
                emptySet()
            }
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            insertStart = start
            insertCount = count
        }

        override fun afterTextChanged(s: Editable?) {
            if (suppressWatcher || s == null) return
            suppressWatcher = true

            if (insertCount > 0) {
                val to = insertStart + insertCount
                pendingMarks.forEach { SpannableCodec.applyMark(s, it, insertStart, to) }
                suppressedMarks.forEach { SpannableCodec.removeMark(s, it, insertStart, to) }
                carryFontForward(s, insertStart, to)
            }
            SpannableCodec.normalize(s, editorStyle)

            suppressWatcher = false
            onBlocksChanged?.invoke(SpannableCodec.parse(s))
            emitSelectionState()
        }
    }

    /**
     * False until this class's own construction finishes.
     *
     * [android.widget.TextView]'s constructor calls `setText`, which fires [onSelectionChanged]
     * while every field declared here is still null. A primitive boolean is safe to read in that
     * window because the JVM has already zeroed it.
     */
    private var initialised = false

    init {
        addTextChangedListener(watcher)
        initialised = true
    }

    /** Replaces the whole outline. Used when a different page is opened, not while typing. */
    fun setBlocks(blocks: List<Block>) {
        suppressWatcher = true
        pendingMarks.clear()
        suppressedMarks.clear()
        setText(SpannableCodec.render(blocks, editorStyle))
        suppressWatcher = false
        emitSelectionState()
    }

    fun blocks(): List<Block> = SpannableCodec.parse(text)

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!initialised) return
        // Moving the caret discards formatting that was armed or suppressed but never typed.
        if (pendingMarks.isNotEmpty()) pendingMarks.clear()
        if (suppressedMarks.isNotEmpty()) suppressedMarks.clear()
        emitSelectionState()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Tab indents rather than moving focus — inside a note, indent is what a writer means.
        if (keyCode == KeyEvent.KEYCODE_TAB) {
            apply(FormatCommand.Indent(if (event.isShiftPressed) -1 else 1))
            return true
        }
        if (event.isCtrlPressed) {
            val mark = when (keyCode) {
                KeyEvent.KEYCODE_B -> Mark.Bold
                KeyEvent.KEYCODE_I -> Mark.Italic
                KeyEvent.KEYCODE_U -> Mark.Underline
                else -> null
            }
            if (mark != null) {
                apply(FormatCommand.ToggleMark(mark))
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    fun apply(command: FormatCommand) {
        val editable = text ?: return
        // Applying spans while the IME holds a composing region corrupts predictive text on many
        // keyboards. Committing the composition first is cheap and avoids the whole class of bug.
        if (BaseInputConnection.getComposingSpanStart(editable) >= 0) {
            BaseInputConnection.removeComposingSpans(editable)
        }

        val start = selectionStart.coerceAtLeast(0)
        val end = selectionEnd.coerceAtLeast(0)
        val from = minOf(start, end)
        val to = maxOf(start, end)

        suppressWatcher = true
        when (command) {
            is FormatCommand.ToggleMark -> toggleMark(editable, command.mark, from, to)
            is FormatCommand.SetMark -> setMark(editable, command.mark, from, to)
            is FormatCommand.ClearMark -> {
                SpannableCodec.removeMark(editable, command.mark, from, to)
                pendingMarks.removeAll { it.sameKindAs(command.mark) }
            }
            is FormatCommand.SetBlockType -> SpannableCodec.updateBlocks(editable, from, to, editorStyle) {
                // Tapping the active list style again returns the block to a plain paragraph.
                val next = if (it.type == command.type) BlockType.Paragraph else command.type
                it.copy(type = next, checked = if (next == BlockType.Todo) (it.checked ?: false) else null)
            }
            is FormatCommand.Indent -> SpannableCodec.updateBlocks(editable, from, to, editorStyle) {
                it.copy(indent = (it.indent + command.delta).coerceIn(0, MAX_INDENT))
            }
            is FormatCommand.SetAlign -> SpannableCodec.updateBlocks(editable, from, to, editorStyle) {
                it.copy(align = command.align)
            }
            is FormatCommand.Clipboard -> {
                val id = when (command.action) {
                    ClipboardAction.Cut -> android.R.id.cut
                    ClipboardAction.Copy -> android.R.id.copy
                    ClipboardAction.Paste -> android.R.id.paste
                    ClipboardAction.PasteAsPlainText -> android.R.id.pasteAsPlainText
                }
                suppressWatcher = false
                onTextContextMenuItem(id)
                suppressWatcher = true
            }
            FormatCommand.ClearFormatting -> {
                SpannableCodec.clearMarks(editable, from, to)
                SpannableCodec.updateBlocks(editable, from, to, editorStyle) {
                    it.copy(type = BlockType.Paragraph, indent = 0, align = Align.Start, checked = null)
                }
                pendingMarks.clear()
            }
        }
        suppressWatcher = false

        onBlocksChanged?.invoke(SpannableCodec.parse(editable))
        emitSelectionState()
    }

    private fun toggleMark(editable: Editable, mark: Mark, from: Int, to: Int) {
        val active = SpannableCodec.marksAt(editable, from, to).contains(mark)
        if (from == to) {
            // No selection: arm the mark for what gets typed next, or suppress it if the caret is
            // already inside it.
            if (mark in pendingMarks || (active && mark !in suppressedMarks)) {
                pendingMarks.remove(mark)
                if (active) suppressedMarks.add(mark)
            } else {
                suppressedMarks.remove(mark)
                pendingMarks.add(mark)
            }
            return
        }
        if (active) SpannableCodec.removeMark(editable, mark, from, to)
        else SpannableCodec.applyMark(editable, mark, from, to)
    }

    private fun setMark(editable: Editable, mark: Mark, from: Int, to: Int) {
        if (from == to) {
            pendingMarks.removeAll { it.sameKindAs(mark) }
            pendingMarks.add(mark)
            onMarkArmed?.invoke(mark)
            return
        }
        SpannableCodec.removeMark(editable, mark, from, to)
        SpannableCodec.applyMark(editable, mark, from, to)
    }

    /**
     * Gives just-typed text a font and size where nothing else has decided one.
     *
     * Three sources, in falling order. A mark the user armed from the ribbon has already been
     * applied by the caller and wins outright. Failing that, whatever the range already carries
     * stands — text typed onto the end of a styled run needs nothing, since end-inclusive spans have
     * already grown over it. What is left is text with nothing on it, and it takes the font of the
     * character it was typed against, so writing at the head of a 20pt line stays 20pt instead of
     * snapping back. [defaultMarks] fills in only where there is no neighbour at all: a fresh
     * container, or a new page.
     */
    private fun carryFontForward(s: Editable, from: Int, to: Int) {
        if (inheritedAtInsert.isEmpty() && defaultMarks.isEmpty()) return
        val present = SpannableCodec.marksTouching(s, from, to)
        carry<Mark.FontSize>(s, from, to, present)
        carry<Mark.FontFamily>(s, from, to, present)
    }

    /**
     * Any mark of this kind *anywhere* in the range means the text arrived with formatting of its
     * own — a paste, most likely — and a mark laid over the whole range would flatten it. Only text
     * that carries nothing gets one.
     */
    private inline fun <reified T : Mark> carry(s: Editable, from: Int, to: Int, present: Set<Mark>) {
        if (present.any { it is T }) return
        val mark = inheritedAtInsert.firstOrNull { it is T }
            ?: defaultMarks.firstOrNull { it is T }
            ?: return
        SpannableCodec.applyMark(s, mark, from, to)
    }

    private fun emitSelectionState() {
        val listener = onSelectionStateChanged ?: return
        val editable = text ?: return
        val from = minOf(selectionStart, selectionEnd).coerceIn(0, editable.length)
        val to = maxOf(selectionStart, selectionEnd).coerceIn(0, editable.length)
        val block = SpannableCodec.blockAt(editable, from)
        val caret = from == to
        // Two different questions. Over a selection the ribbon describes the text; at a caret it
        // has to describe what typing would produce, which is exactly what [carryFontForward]
        // applies — so the two read from the same place and cannot drift apart.
        val marks = if (caret) {
            SpannableCodec.inheritedMarks(editable, from).overriddenBy(pendingMarks) - suppressedMarks
        } else {
            SpannableCodec.marksAt(editable, from, to)
        }
        val base = baseFontSize
        listener(
            SelectionState(
                marks = marks,
                blockType = block?.type ?: BlockType.Paragraph,
                align = block?.align ?: Align.Start,
                indent = block?.indent ?: 0,
                hasSelection = !caret,
                fontSize = if (caret) {
                    marks.filterIsInstance<Mark.FontSize>().firstOrNull()?.sp ?: base
                } else {
                    SpannableCodec.fontSizeIn(editable, from, to, base)
                },
                fontFamily = if (caret) {
                    marks.filterIsInstance<Mark.FontFamily>().firstOrNull()?.name ?: baseFontFamily
                } else {
                    SpannableCodec.fontFamilyIn(editable, from, to, baseFontFamily)
                },
            ),
        )
    }

    private companion object {
        const val MAX_INDENT = 8
    }
}

/**
 * Merges two mark sets, [others] replacing anything of the same kind.
 *
 * A plain union would keep both — leaving an inherited 12 sitting next to a just-picked 20, for the
 * ribbon to choose between arbitrarily.
 */
private fun Set<Mark>.overriddenBy(others: Collection<Mark>): Set<Mark> =
    if (others.isEmpty()) this else filterNot { mark -> others.any { it.sameKindAs(mark) } }.toSet() + others

/** Compares marks by kind, ignoring any value, so setting a colour replaces the previous one. */
internal fun Mark.sameKindAs(other: Mark): Boolean = when {
    this is Mark.TextColor && other is Mark.TextColor -> true
    this is Mark.Highlight && other is Mark.Highlight -> true
    this is Mark.FontSize && other is Mark.FontSize -> true
    this is Mark.FontFamily && other is Mark.FontFamily -> true
    this is Mark.Link && other is Mark.Link -> true
    else -> this == other
}
