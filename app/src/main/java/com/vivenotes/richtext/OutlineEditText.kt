package com.vivenotes.richtext

import android.content.Context
import android.graphics.Rect
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.AbsoluteSizeSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import com.vivenotes.model.Align
import com.vivenotes.model.Block
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import com.vivenotes.model.OBJECT_REPLACEMENT_CHARACTER
import com.vivenotes.model.opposingScript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    private val equationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var equationRenderJob: Job? = null
    private var equationRenderGeneration = 0
    private var autoEquationRenderJob: Job? = null
    private var autoEquationRenderGeneration = 0
    private val autoEquationCache = object : LinkedHashMap<EquationRenderKey, io.ratex.RaTeXRenderer>(
        AUTO_EQUATION_CACHE_SIZE,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<EquationRenderKey, io.ratex.RaTeXRenderer>?,
        ): Boolean = size > AUTO_EQUATION_CACHE_SIZE
    }

    /** Equations are parsed with the canvas text colour because RaTeX bakes it into its display list. */
    var equationColor: Int = 0xFF000000.toInt()
        set(value) {
            if (field == value) return
            field = value
            autoEquationCache.clear()
            if (initialised) {
                hydrateEquations()
                refreshAutoEquations()
            }
        }

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
     * What Tab does when this editor is a table cell: move the caret on, and say whether it moved —
     * `docs/tablePlan.md` TA17.
     *
     * Null in a text container, which is the common case and the one Tab already had an answer for:
     * inside a note, indent is what a writer means. A cell is where that stops being true, because
     * the grid is the structure and walking it is what the key is for everywhere else.
     *
     * Set from outside because this view knows nothing about grids — it is one editor, and which
     * cell it is (if it is one at all) is `TableContainer`'s business, not its own.
     */
    var onTabNavigate: ((forward: Boolean) -> Boolean)? = null

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
            refreshAutoEquations()
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
        hydrateEquations()
        emitSelectionState()
        refreshAutoEquations()
    }

    fun blocks(): List<Block> = SpannableCodec.parse(text)

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!initialised) return
        // Moving the caret discards formatting that was armed or suppressed but never typed.
        if (pendingMarks.isNotEmpty()) pendingMarks.clear()
        if (suppressedMarks.isNotEmpty()) suppressedMarks.clear()
        emitSelectionState()
        refreshAutoEquations()
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        if (initialised) {
            emitSelectionState()
            refreshAutoEquations()
        }
    }

    override fun onDetachedFromWindow() {
        equationRenderGeneration++
        equationRenderJob?.cancel()
        autoEquationRenderGeneration++
        autoEquationRenderJob?.cancel()
        super.onDetachedFromWindow()
    }

    /** Leaves text mode completely when a canvas tool is picked up. */
    fun deactivateTextInput() {
        pendingMarks.clear()
        suppressedMarks.clear()
        clearFocus()
        context.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Tab indents rather than moving focus — inside a note, indent is what a writer means. In a
        // table cell it walks the grid instead (TA17), and falls back to the indent in the last cell,
        // where there is nowhere left to walk.
        if (keyCode == KeyEvent.KEYCODE_TAB) {
            val forward = !event.isShiftPressed
            if (onTabNavigate?.invoke(forward) == true) return true
            apply(FormatCommand.Indent(if (forward) 1 else -1))
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
        // These are consumed by EditorPane; accepting them here as no-ops keeps this view's
        // command surface exhaustive without turning panel lifetime into a document edit.
        if (
            command == FormatCommand.DeactivateTextInput ||
            command == FormatCommand.RetainEquationTarget ||
            command == FormatCommand.ReleaseEquationTarget ||
            command == FormatCommand.ClearCanvasSelection
        ) return
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
                val equation = if (command.mark is Mark.FontSize) {
                    equationFormattingRange(editable, from, to)
                } else {
                    null
                }
                SpannableCodec.removeMark(
                    editable,
                    command.mark,
                    equation?.start ?: from,
                    equation?.end ?: to,
                )
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
            is FormatCommand.InsertEquation -> insertEquation(editable, command.latex, from, to)
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
            // Selection only: no span is touched, so `emitSelectionState` below is what the ribbon
            // and the toolkit see change.
            FormatCommand.SelectAll -> selectAll()
            FormatCommand.DeactivateTextInput,
            FormatCommand.RetainEquationTarget,
            FormatCommand.ReleaseEquationTarget,
            // The canvas's selection, not this view's — `EditorPane` holds it and consumes this.
            FormatCommand.ClearCanvasSelection,
            -> Unit
        }
        suppressWatcher = false

        onBlocksChanged?.invoke(SpannableCodec.parse(editable))
        if (command.affectsEquationMetrics()) hydrateEquations()
        emitSelectionState()
        refreshAutoEquations()
    }

    private fun insertEquation(editable: Editable, latex: String, from: Int, to: Int) {
        val source = latex.trim()
        if (source.isEmpty()) return

        val existing = SpannableCodec.equationAt(editable, from, to)
        val replaceFrom = existing?.start ?: from
        val replaceTo = existing?.end ?: to
        editable.getSpans(replaceFrom, replaceTo, EquationSpan::class.java).forEach(editable::removeSpan)
        editable.replace(replaceFrom, replaceTo, OBJECT_REPLACEMENT_CHARACTER.toString())

        // End-inclusive text formatting around the caret may have expanded onto the replacement
        // character. An equation is its own atomic content, so begin with no inherited text marks.
        SpannableCodec.clearMarks(editable, replaceFrom, replaceFrom + 1)
        SpannableCodec.applyMark(editable, Mark.Equation(source), replaceFrom, replaceFrom + 1)
        SpannableCodec.normalize(editable, editorStyle)
        pendingMarks.clear()
        suppressedMarks.clear()
        setSelection(replaceFrom + 1)
    }

    /** Replaces each source fallback with a native renderer, discarding work from older documents. */
    private fun hydrateEquations() {
        val editable = text ?: return
        val spans = editable.getSpans(0, editable.length, EquationSpan::class.java).toList()
        val generation = ++equationRenderGeneration
        equationRenderJob?.cancel()
        if (spans.isEmpty()) return

        val color = equationColor
        equationRenderJob = equationScope.launch {
            spans.forEach { span ->
                launch {
                    val start = editable.getSpanStart(span)
                    val end = editable.getSpanEnd(span)
                    if (start < 0 || end <= start) return@launch
                    val sizePx = equationSizePx(editable, start, end)
                    val renderer = try {
                        createEquationRenderer(context, span.latex, sizePx, color)
                    } catch (_: Exception) {
                        null
                    }
                    if (generation != equationRenderGeneration || editable.getSpanStart(span) < 0) return@launch
                    span.show(renderer)
                    requestLayout()
                    invalidate()
                }
            }
        }
    }

    /**
     * Draws explicitly bounded LaTeX while preserving its source as ordinary editable text.
     *
     * The preview span is absent whenever the focused caret/selection touches its range. Moving
     * away reapplies it from a small renderer cache; unfinished or invalid source simply never gets
     * a span and therefore remains visible.
     */
    private fun refreshAutoEquations() {
        val editable = text ?: return
        val sourceText = editable.toString()
        val candidates = findAutoEquationCandidates(sourceText)
        val generation = ++autoEquationRenderGeneration
        autoEquationRenderJob?.cancel()

        var spansChanged = false
        editable.getSpans(0, editable.length, LiveEquationSpan::class.java).forEach { span ->
            val start = editable.getSpanStart(span)
            val end = editable.getSpanEnd(span)
            val candidate = candidates.firstOrNull {
                it.start == start && it.end == end && it.latex == span.latex &&
                    equationRenderKey(editable, it).let { key ->
                        span.renderSizePx == key.sizePx && span.renderColor == key.color
                    }
            }
            if (candidate == null || isEditing(candidate)) {
                editable.removeSpan(span)
                spansChanged = true
            }
        }

        val missing = candidates.filterNot(::isEditing).filterNot { candidate ->
            editable.getSpans(candidate.start, candidate.end, LiveEquationSpan::class.java).any {
                editable.getSpanStart(it) == candidate.start &&
                    editable.getSpanEnd(it) == candidate.end &&
                    it.latex == candidate.latex &&
                    equationRenderKey(editable, candidate).let { key ->
                        it.renderSizePx == key.sizePx && it.renderColor == key.color
                    }
            }
        }
        if (missing.isEmpty()) {
            if (spansChanged) refreshEquationLayout()
            return
        }

        val pending = mutableListOf<PendingAutoEquation>()
        missing.forEach { candidate ->
            val key = equationRenderKey(editable, candidate)
            val cached = autoEquationCache[key]
            if (cached == null) {
                pending += PendingAutoEquation(candidate, key)
            } else {
                applyLiveEquation(editable, candidate, key, cached)
                spansChanged = true
            }
        }
        if (spansChanged) refreshEquationLayout()
        if (pending.isEmpty()) return

        autoEquationRenderJob = equationScope.launch {
            pending.forEach { pendingEquation ->
                launch {
                    val candidate = pendingEquation.candidate
                    val key = pendingEquation.key
                    val renderer = try {
                        createEquationRenderer(context, candidate.latex, key.sizePx, key.color)
                    } catch (_: Exception) {
                        null
                    } ?: return@launch
                    if (generation != autoEquationRenderGeneration) return@launch
                    if (candidate.end > editable.length ||
                        editable.subSequence(candidate.start, candidate.end).toString() !=
                        sourceText.substring(candidate.start, candidate.end) ||
                        isEditing(candidate)
                    ) return@launch

                    autoEquationCache[key] = renderer
                    applyLiveEquation(editable, candidate, key, renderer)
                    refreshEquationLayout()
                }
            }
        }
    }

    private fun isEditing(candidate: AutoEquationCandidate): Boolean {
        return candidate.isBeingEdited(hasFocus(), selectionStart, selectionEnd)
    }

    private fun applyLiveEquation(
        editable: Editable,
        candidate: AutoEquationCandidate,
        key: EquationRenderKey,
        renderer: io.ratex.RaTeXRenderer,
    ) {
        if (editable.getSpans(candidate.start, candidate.end, LiveEquationSpan::class.java).any {
                editable.getSpanStart(it) == candidate.start && editable.getSpanEnd(it) == candidate.end
            }
        ) return
        val span = LiveEquationSpan(candidate.latex, key.sizePx, key.color)
        span.show(renderer)
        editable.setSpan(span, candidate.start, candidate.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun equationRenderKey(
        editable: Editable,
        candidate: AutoEquationCandidate,
    ): EquationRenderKey = EquationRenderKey(
        latex = candidate.latex,
        sizePx = equationSizePx(editable, candidate.start, candidate.end),
        color = equationColor,
    )

    private fun equationSizePx(editable: Editable, start: Int, end: Int): Float {
        val sp = SpannableCodec.fontSizeIn(editable, start, end, baseFontSize)
            ?: editable.getSpans(start, end, AbsoluteSizeSpan::class.java).lastOrNull()?.size
            ?: baseFontSize
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            sp.toFloat(),
            resources.displayMetrics,
        )
    }

    private fun refreshEquationLayout() {
        requestLayout()
        invalidate()
    }

    private fun toggleMark(editable: Editable, mark: Mark, from: Int, to: Int) {
        val here = SpannableCodec.marksAt(editable, from, to)
        val active = mark in here
        if (from == to) {
            // No selection: arm the mark for what gets typed next, or suppress it if the caret is
            // already inside it.
            if (mark in pendingMarks || (active && mark !in suppressedMarks)) {
                pendingMarks.remove(mark)
                if (active) suppressedMarks.add(mark)
            } else {
                suppressedMarks.remove(mark)
                pendingMarks.add(mark)
                // The same exclusion the text gets, applied to what is about to be typed: arming a
                // script disarms the other one, and suppresses it where the caret already sits in
                // it. Without this the ribbon would light both up and the first character typed
                // would silently pick one.
                mark.opposingScript()?.let { opposite ->
                    pendingMarks.remove(opposite)
                    if (opposite in here) suppressedMarks.add(opposite)
                }
            }
            return
        }
        if (active) SpannableCodec.removeMark(editable, mark, from, to)
        else SpannableCodec.applyMark(editable, mark, from, to)
    }

    private fun setMark(editable: Editable, mark: Mark, from: Int, to: Int) {
        val equation = if (mark is Mark.FontSize) equationFormattingRange(editable, from, to) else null
        if (equation != null) {
            SpannableCodec.removeMark(editable, mark, equation.start, equation.end)
            SpannableCodec.applyMark(editable, mark, equation.start, equation.end)
            pendingMarks.removeAll { it.sameKindAs(mark) }
            return
        }
        if (from == to) {
            pendingMarks.removeAll { it.sameKindAs(mark) }
            pendingMarks.add(mark)
            onMarkArmed?.invoke(mark)
            return
        }
        SpannableCodec.removeMark(editable, mark, from, to)
        SpannableCodec.applyMark(editable, mark, from, to)
    }

    /** Font size belongs to the rendered formula, even when only its caret-sized source is active. */
    private fun equationFormattingRange(editable: Editable, from: Int, to: Int): EquationRange? {
        SpannableCodec.equationAt(editable, from, to)?.let { return it }
        return findAutoEquationCandidates(editable.toString())
            .firstOrNull { it.isBeingEdited(true, from, to) }
            ?.let { EquationRange(it.latex, it.start, it.end) }
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
        val equationRange = equationFormattingRange(editable, from, to)
        val equationFontSize = equationRange?.let {
            SpannableCodec.fontSizeIn(editable, it.start, it.end, baseFontSize)
        }
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
                    equationFontSize
                        ?: marks.filterIsInstance<Mark.FontSize>().firstOrNull()?.sp
                        ?: base
                } else {
                    SpannableCodec.fontSizeIn(editable, from, to, base)
                },
                fontFamily = if (caret) {
                    marks.filterIsInstance<Mark.FontFamily>().firstOrNull()?.name ?: baseFontFamily
                } else {
                    SpannableCodec.fontFamilyIn(editable, from, to, baseFontFamily)
                },
                equation = SpannableCodec.equationAt(editable, from, to)?.latex,
                editorFocused = hasFocus(),
            ),
        )
    }

    private companion object {
        const val MAX_INDENT = 8
        const val AUTO_EQUATION_CACHE_SIZE = 48
    }

    private data class EquationRenderKey(val latex: String, val sizePx: Float, val color: Int)
    private data class PendingAutoEquation(
        val candidate: AutoEquationCandidate,
        val key: EquationRenderKey,
    )
}

private fun FormatCommand.affectsEquationMetrics(): Boolean = when (this) {
    is FormatCommand.InsertEquation -> true
    is FormatCommand.SetMark -> mark is Mark.FontSize
    is FormatCommand.ClearMark -> mark is Mark.FontSize
    FormatCommand.ClearFormatting -> true
    else -> false
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
    this is Mark.Equation && other is Mark.Equation -> true
    else -> this == other
}
