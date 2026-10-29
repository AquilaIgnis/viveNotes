package com.vivenotes.richtext

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.text.Editable
import android.text.Spannable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.URLSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import com.vivenotes.keyboard.KeyBindings
import com.vivenotes.keyboard.ShortcutAction
import com.vivenotes.keyboard.ShortcutDecision
import com.vivenotes.keyboard.ShortcutScope
import com.vivenotes.keyboard.toChord
import com.vivenotes.model.Align
import com.vivenotes.model.Block
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import com.vivenotes.model.OBJECT_REPLACEMENT_CHARACTER
import com.vivenotes.model.VideoLink
import com.vivenotes.model.findVideoLinks
import com.vivenotes.model.newId
import com.vivenotes.model.opposingScript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.BreakIterator
import kotlin.math.hypot
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

    /**
     * Where link-preview thumbnails come from, or null to draw no cards at all.
     *
     * Null is the off switch as well as the default: the Settings toggle withholds the source rather
     * than passing a flag beside it, so "previews are off" and "nothing has been wired up" are the
     * same state and neither can reach the network. See [VideoThumbnails].
     */
    var videoThumbnails: VideoThumbnails? = null
        set(value) {
            if (field === value) return
            field = value
            if (initialised) refreshVideoEmbeds()
        }

    /**
     * Opens a pasted video, fired by a tap on a card's play badge.
     *
     * A callback rather than an `ACTION_VIEW` built here: this view is the text editor, and knowing
     * how the app leaves itself is not its job. It also keeps the tap assertable from a test without
     * an activity actually being launched.
     */
    var onOpenVideo: ((url: String) -> Unit)? = null

    /** Opens a link after a second tap on its exposed text. */
    var onOpenLink: ((url: String) -> Unit)? = null

    var linkColor: Int = 0xFF0063C6.toInt()
        set(value) {
            if (field == value) return
            field = value
            if (initialised) refreshLinkStyles()
        }

    /** Called after any user edit, debounced by the caller. */
    var onBlocksChanged: ((List<Block>) -> Unit)? = null

    /**
     * Each change to the blocks as an undo step — see [TextEdit]. Beside [onBlocksChanged] rather
     * than in it: that one is the document's, this one is the history's, and a command that edits in
     * several passes is one step here.
     */
    var onTextEdited: ((TextEdit) -> Unit)? = null

    var onSelectionStateChanged: ((SelectionState) -> Unit)? = null

    /**
     * What Tab does when this editor is a table cell: move the caret on, and say whether it moved —
     *
     * Null in a text container, which is the common case and the one Tab already had an answer for:
     * inside a note, indent is what a writer means. A cell is where that stops being true, because
     * the grid is the structure and walking it is what the key is for everywhere else.
     *
     * Set from outside because this view knows nothing about grids — it is one editor, and which
     * cell it is (if it is one at all) is `TableContainer`'s business, not its own.
     */
    var onTabNavigate: ((forward: Boolean) -> Boolean)? = null

    /** Which keys run which commands here — Hardware ▸ Keyboard's, set by the host. */
    var keyBindings: KeyBindings = KeyBindings.Default

    /**
     * Runs a workspace command — Zoom in, say — that the user gave one of this box's own keys, which
     * would otherwise never get past the text field. Returns whether it did anything.
     */
    var onWorkspaceShortcut: ((ShortcutAction) -> Boolean)? = null

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

    /** The blocks as last reported to [onTextEdited]: what the next change is a change from. */
    private var reportedBlocks: List<Block> = emptyList()

    /** Above zero while a command runs, so what it types through the watcher is one step, not typing. */
    private var commandDepth = 0

    // The text, selection and composition before the keystroke the watcher is about to see.
    private var textBeforeEdit = ""
    private var selectionBeforeEdit = TextSelection(0)
    private var composingBeforeEdit = false

    /** Above zero while the text is changing, when a selection that moves has not been moved by anyone. */
    private var textChanges = 0

    /**
     * Above zero inside a batch edit — a key typed, or an input method's commit — and the selection
     * when it began. Both move the caret to the end of the selection before replacing it, so the
     * selection a keystroke replaced is the one the batch began with.
     */
    private var batchEdits = 0
    private var selectionAtBatch = TextSelection(0)

    /**
     * Ctrl+double-click on a word, with a mouse: the word to select beside the others — see
     * `ui/editor/WordSelections.kt`. Null leaves Ctrl+click to the editor.
     */
    var onCtrlDoubleClickWord: ((TextSelection) -> Unit)? = null

    /** Any other press on this editor, which puts the words selected together down. */
    var onPlainPress: (() -> Unit)? = null

    /** The selection moved by a click or a key, rather than by the text changing under it. */
    var onSelectionMoved: ((TextSelection) -> Unit)? = null

    /** Gets each command first, for the words selected together; true when it took it. */
    var commandInterceptor: ((FormatCommand) -> Boolean)? = null

    /** Words selected beside this editor's own selection, drawn the way it is. */
    var extraSelections: List<TextSelection> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val extraPath = Path()
    private val extraPaint = Paint()
    private var ctrlPress = false
    private var ctrlClickAt = -1L
    private var ctrlClickX = 0f
    private var ctrlClickY = 0f

    /** The card whose play badge a finger went down on, held until it lifts. See [onTouchEvent]. */
    private var pressedBadge: VideoEmbedSpan? = null
    private var pressedLink: String? = null

    private val watcher = object : TextWatcher {
        private var insertStart = 0
        private var insertCount = 0

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            textChanges++
            inheritedAtInsert = if (!suppressWatcher && after > 0 && s is Spanned) {
                SpannableCodec.inheritedMarks(s, start)
            } else {
                emptySet()
            }
            // Even while suppressed: an empty line deleted by a programmatic replace, such as an
            // equation inserted over a selection, must not hand its style to the replacement.
            if (count > 0 && s is Spannable) {
                SpannableCodec.forgetDeletedEmptyParagraphs(s, start, start + count)
            }
            if (!suppressWatcher && commandDepth == 0 && s is Spannable) {
                textBeforeEdit = s.toString()
                selectionBeforeEdit = if (batchEdits > 0) selectionAtBatch else currentSelection()
                composingBeforeEdit = BaseInputConnection.getComposingSpanStart(s) >= 0
            }
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            insertStart = start
            insertCount = count
        }

        override fun afterTextChanged(s: Editable?) {
            textChanges--
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
            val blocks = SpannableCodec.parse(s)
            onBlocksChanged?.invoke(blocks)
            if (commandDepth == 0) {
                val before = selectionBeforeEdit.clamped(textBeforeEdit.length)
                reportEdit(blocks, before, Keystroke.of(textBeforeEdit, s.toString(), before), composingBeforeEdit)
                // The next edit in the same batch replaces what this one left selected.
                if (batchEdits > 0) selectionAtBatch = currentSelection()
            }
            emitSelectionState()
            refreshAutoEquations()
            refreshLinkStyles()
            refreshVideoEmbeds()
        }
    }

    private fun currentSelection(): TextSelection =
        TextSelection(selectionStart.coerceAtLeast(0), selectionEnd.coerceAtLeast(0))

    /** Reports the change from [reportedBlocks] to [after], unless there was none. */
    private fun reportEdit(after: List<Block>, selectionBefore: TextSelection, keystroke: Keystroke?, composing: Boolean) {
        val before = reportedBlocks
        reportedBlocks = after
        if (before == after) return
        onTextEdited?.invoke(TextEdit(before, after, selectionBefore, currentSelection(), keystroke, composing))
    }

    /**
     * Runs [body] as one command: whatever it changes, typed through the watcher or not, is a single
     * step of the history, reported once it is done. A command inside another is part of it.
     */
    private inline fun <T> asCommand(body: () -> T): T {
        val selection = currentSelection()
        commandDepth++
        try {
            return body()
        } finally {
            commandDepth--
            if (commandDepth == 0) text?.let { reportEdit(SpannableCodec.parse(it), selection, keystroke = null, composing = false) }
        }
    }

    /**
     * Puts [blocks] back with [selection] — Undo and Redo of the text. The history has the step
     * already, so only [onBlocksChanged] hears of it, for whatever shows whether the box is empty.
     */
    fun restore(blocks: List<Block>, selection: TextSelection) {
        setBlocks(blocks)
        val length = text?.length ?: 0
        setSelection(selection.start.coerceIn(0, length), selection.end.coerceIn(0, length))
        onBlocksChanged?.invoke(blocks)
    }

    /** The text of [range], spans and all — a word among several being copied together. */
    fun textIn(range: TextSelection): CharSequence {
        val editable = text ?: return ""
        val clamped = range.clamped(editable.length)
        return editable.subSequence(clamped.min, clamped.max)
    }

    /** The marks all of [range] carries, or what typing would carry at a caret. */
    fun marksIn(range: TextSelection): Set<Mark> {
        val editable = text ?: return emptySet()
        val clamped = range.clamped(editable.length)
        return if (clamped.collapsed) SpannableCodec.inheritedMarks(editable, clamped.min)
        else SpannableCodec.marksAt(editable, clamped.min, clamped.max)
    }

    /**
     * Runs [command] at each of [ranges] — once in each paragraph they touch, when [paragraphs] — as
     * one change, and leaves the selection where it was.
     */
    fun applyAt(command: FormatCommand, ranges: List<TextSelection>, paragraphs: Boolean): Unit = asCommand {
        val editable = text ?: return@asCommand
        val targets = if (!paragraphs) ranges else SpannableCodec.paragraphs(editable)
            .filter { (start, end) -> ranges.any { it.min <= end && it.max >= start } }
            .map { (start, _) -> TextSelection(start) }
        val kept = currentSelection()
        for (target in targets.sortedByDescending { it.min }) {
            val clamped = target.clamped(editable.length)
            setSelection(clamped.min, clamped.max)
            applyCommand(command)
        }
        setSelection(kept.start.coerceIn(0, editable.length), kept.end.coerceIn(0, editable.length))
    }

    /**
     * Puts [content] in place of each of [ranges] as one change, and returns the caret after each,
     * in the same order. The editor's own selection moves with the text around it.
     */
    fun replaceAt(ranges: List<TextSelection>, content: CharSequence): List<TextSelection> = asCommand {
        val editable = text ?: return@asCommand ranges
        val carets = IntArray(ranges.size)
        val done = mutableListOf<Int>()
        // Right to left, so a replacement cannot move a range still to come; each one moves the
        // carets already placed after it.
        for (index in ranges.indices.sortedByDescending { ranges[it].min }) {
            val range = ranges[index].clamped(editable.length)
            editable.replace(range.min, range.max, content)
            val shift = content.length - (range.max - range.min)
            done.forEach { carets[it] += shift }
            carets[index] = range.min + content.length
            done += index
        }
        carets.map { TextSelection(it) }
    }

    /** Ctrl+double-click selects a word beside the others. Both presses are used up, so the editor keeps them. */
    private fun wordClick(event: MotionEvent): Boolean {
        val onWord = onCtrlDoubleClickWord ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val ctrl = event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE &&
                    event.metaState and KeyEvent.META_CTRL_ON != 0 &&
                    event.isButtonPressed(MotionEvent.BUTTON_PRIMARY)
                if (!ctrl) {
                    ctrlClickAt = -1L
                    onPlainPress?.invoke()
                    return false
                }
                ctrlPress = true
                return true
            }
            MotionEvent.ACTION_MOVE -> return ctrlPress
            MotionEvent.ACTION_UP -> {
                if (!ctrlPress) return false
                ctrlPress = false
                val near = hypot(event.x - ctrlClickX, event.y - ctrlClickY) <= ViewConfiguration.get(context).scaledTouchSlop * 2
                if (ctrlClickAt >= 0 && event.eventTime - ctrlClickAt <= ViewConfiguration.getDoubleTapTimeout() && near) {
                    ctrlClickAt = -1L
                    wordAt(event.x, event.y)?.let(onWord)
                } else {
                    ctrlClickAt = event.eventTime
                    ctrlClickX = event.x
                    ctrlClickY = event.y
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                val pressed = ctrlPress
                ctrlPress = false
                return pressed
            }
        }
        return false
    }

    /** The word at ([x], [y]), or null over a space or past the text. */
    private fun wordAt(x: Float, y: Float): TextSelection? {
        val content = text?.toString().orEmpty()
        if (content.isEmpty()) return null
        val offset = getOffsetForPosition(x, y).coerceIn(0, content.lastIndex)
        val words = BreakIterator.getWordInstance().apply { setText(content) }
        val end = words.following(offset).takeIf { it != BreakIterator.DONE } ?: content.length
        val start = words.preceding(end).takeIf { it != BreakIterator.DONE } ?: 0
        return TextSelection(start, end).takeUnless { it.collapsed || content.substring(start, end).isBlank() }
    }

    /** The words selected beside this editor's own selection, under the text as a selection is. */
    override fun onDraw(canvas: Canvas) {
        val shown = layout
        if (extraSelections.isNotEmpty() && shown != null) {
            extraPaint.color = highlightColor
            val length = shown.text.length
            canvas.save()
            canvas.translate(compoundPaddingLeft.toFloat(), extendedPaddingTop.toFloat())
            for (range in extraSelections) {
                extraPath.reset()
                shown.getSelectionPath(range.min.coerceIn(0, length), range.max.coerceIn(0, length), extraPath)
                canvas.drawPath(extraPath, extraPaint)
            }
            canvas.restore()
        }
        super.onDraw(canvas)
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
        reportedBlocks = blocks
        hydrateEquations()
        emitSelectionState()
        refreshAutoEquations()
        refreshLinkStyles()
        refreshVideoEmbeds()
    }

    fun blocks(): List<Block> = SpannableCodec.parse(text)

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!initialised) return
        if (textChanges == 0 && commandDepth == 0 && batchEdits == 0) onSelectionMoved?.invoke(currentSelection())
        // Moving the caret discards formatting that was armed or suppressed but never typed.
        if (pendingMarks.isNotEmpty()) pendingMarks.clear()
        if (suppressedMarks.isNotEmpty()) suppressedMarks.clear()
        emitSelectionState()
        refreshAutoEquations()
        refreshVideoEmbeds()
    }

    override fun onBeginBatchEdit() {
        super.onBeginBatchEdit()
        if (batchEdits++ == 0) selectionAtBatch = currentSelection()
    }

    /** Where a batch left the caret is where a click or a key moved it, unless the text changed with it. */
    override fun onEndBatchEdit() {
        super.onEndBatchEdit()
        batchEdits = (batchEdits - 1).coerceAtLeast(0)
        if (batchEdits == 0 && initialised && commandDepth == 0) onSelectionMoved?.invoke(currentSelection())
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        if (initialised) {
            emitSelectionState()
            refreshAutoEquations()
            refreshVideoEmbeds()
        }
    }

    /**
     * A card is measured against the editor's content width, so a resize invalidates every one.
     *
     * Reached by a container being dragged wider, a table column moving, or the pane layout
     * changing under a rotation. Without this the cards keep the width they were built at and either
     * leave a gap or run past the edge, because a [android.text.style.ReplacementSpan] reports its
     * own size and nothing re-asks it.
     */
    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (initialised && width != oldWidth) refreshVideoEmbeds()
    }

    /**
     * Opens a video badge or an exposed link, and otherwise leaves touches to the editor.
     *
     * The badge consumes the gesture from the down event onwards rather than acting on the up. A tap
     * that both moved the caret and left the app would put the caret inside the URL on the way out,
     * so the card would be gone and the raw link showing when the user came back. Consuming the down
     * also keeps `EditText`'s own touch state machine out of the gesture entirely.
     *
     * The rest of the card is deliberately not consumed: a tap there places the caret, which
     * uncovers the URL for editing.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (wordClick(event)) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val span = badgeAt(event.x, event.y)
                if (span != null) {
                    pressedBadge = span
                    return true
                }
                val link = linkAt(event.x, event.y)
                if (link != null && rangeIsBeingEdited(
                        link.start, link.end, hasFocus(), selectionStart, selectionEnd,
                    )) {
                    pressedLink = link.url
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> if (pressedBadge != null) {
                // Sliding off the badge abandons the tap rather than opening on release, the same
                // way a button behaves when a finger wanders out of it.
                if (badgeAt(event.x, event.y) !== pressedBadge) pressedBadge = null
                return true
            }
            MotionEvent.ACTION_MOVE -> if (pressedLink != null) {
                if (linkAt(event.x, event.y)?.url != pressedLink) pressedLink = null
                return true
            }
            MotionEvent.ACTION_UP -> {
                val span = pressedBadge
                pressedBadge = null
                if (span != null) {
                    if (badgeAt(event.x, event.y) === span) onOpenVideo?.invoke(span.url)
                    return true
                }
                val url = pressedLink
                pressedLink = null
                if (url != null) {
                    if (linkAt(event.x, event.y)?.url == url) onOpenLink?.invoke(url)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> if (pressedBadge != null) {
                pressedBadge = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> if (pressedLink != null) {
                pressedLink = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private data class TouchedLink(val start: Int, val end: Int, val url: String)

    private fun linkAt(x: Float, y: Float): TouchedLink? {
        val editable = text ?: return null
        val textLayout = layout ?: return null
        val textX = x - totalPaddingLeft + scrollX
        val textY = y - totalPaddingTop + scrollY
        if (textY < 0 || textY >= textLayout.height) return null
        val line = textLayout.getLineForVertical(textY.toInt())
        if (textX < textLayout.getLineLeft(line) || textX > textLayout.getLineRight(line)) return null
        val offset = textLayout.getOffsetForHorizontal(line, textX)
        val stored = editable.getSpans(offset, (offset + 1).coerceAtMost(editable.length), URLSpan::class.java)
            .firstOrNull { offset in editable.getSpanStart(it) until editable.getSpanEnd(it) }
        if (stored != null) return TouchedLink(
            editable.getSpanStart(stored), editable.getSpanEnd(stored), stored.url,
        )
        return editable.getSpans(offset, (offset + 1).coerceAtMost(editable.length), AutoVideoLinkSpan::class.java)
            .firstOrNull { offset in editable.getSpanStart(it) until editable.getSpanEnd(it) }
            ?.let { TouchedLink(editable.getSpanStart(it), editable.getSpanEnd(it), it.url) }
    }

    /** The card whose play badge is under ([x], [y]) in this view's coordinates, or null. */
    private fun badgeAt(x: Float, y: Float): VideoEmbedSpan? {
        val editable = text ?: return null
        val layout = layout ?: return null
        val spans = editable.getSpans(0, editable.length, VideoEmbedSpan::class.java)
        if (spans.isEmpty()) return null

        val textX = x - totalPaddingLeft + scrollX
        val textY = y - totalPaddingTop + scrollY
        return spans.firstOrNull { span ->
            val start = editable.getSpanStart(span)
            if (start < 0) return@firstOrNull false
            val line = layout.getLineForOffset(start)
            val left = layout.getPrimaryHorizontal(start)
            // The card hangs above the baseline — see `VideoEmbedSpan.getSize`, which reports its
            // whole height as ascent — so the baseline is where its bottom edge is.
            val cardTop = layout.getLineBaseline(line) - span.cardHeightPx
            span.badgeContains(textX - left, textY - cardTop)
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

    /**
     * The text box's shortcuts, ahead of `TextView`'s own keys — see [ShortcutScope.TextBox]. Which
     * key does what is [keyBindings]'s; a default this box's commands no longer use is used up rather
     * than left to `TextView`, whose own Ctrl+C would otherwise come back.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val chord = event.toChord() ?: return super.onKeyDown(keyCode, event)
        return when (val decision = keyBindings.decide(chord, ShortcutScope.TextBox, repeat = event.repeatCount > 0)) {
            ShortcutDecision.Pass -> super.onKeyDown(keyCode, event)
            ShortcutDecision.Swallow -> true
            is ShortcutDecision.Run -> runShortcut(decision.action) || super.onKeyDown(keyCode, event)
        }
    }

    /** One text box command; a workspace one a text box key was given goes to [onWorkspaceShortcut]. */
    private fun runShortcut(action: ShortcutAction): Boolean {
        val selected = selectionStart != selectionEnd
        when (action) {
            // With nothing selected these still use the key, as `TextView`'s own would — and must not
            // reach it, which would put an empty clip on the clipboard.
            ShortcutAction.Cut -> if (selected) apply(FormatCommand.Clipboard(ClipboardAction.Cut))
            ShortcutAction.Copy -> if (selected) apply(FormatCommand.Clipboard(ClipboardAction.Copy))
            ShortcutAction.Paste -> apply(FormatCommand.Clipboard(ClipboardAction.Paste))
            ShortcutAction.PastePlainText -> apply(FormatCommand.Clipboard(ClipboardAction.PasteAsPlainText))
            ShortcutAction.SelectAll -> apply(FormatCommand.SelectAll)
            ShortcutAction.Bold -> apply(FormatCommand.ToggleMark(Mark.Bold))
            ShortcutAction.Italic -> apply(FormatCommand.ToggleMark(Mark.Italic))
            ShortcutAction.Underline -> apply(FormatCommand.ToggleMark(Mark.Underline))
            // Indent rather than moving focus — inside a note, indent is what a writer means. In a
            // table cell it walks the grid instead, and falls back to the indent in the last cell,
            // where there is nowhere left to walk.
            ShortcutAction.Indent, ShortcutAction.Outdent -> {
                val forward = action == ShortcutAction.Indent
                if (onTabNavigate?.invoke(forward) != true) apply(FormatCommand.Indent(if (forward) 1 else -1))
            }
            ShortcutAction.ExitList -> insertPlainParagraph()
            // The page's history, which holds the text and its formatting. The key is used up even
            // with nothing to undo: `TextView`'s own undo would put back characters without either.
            ShortcutAction.Undo, ShortcutAction.Redo -> onWorkspaceShortcut?.invoke(action)
            else -> return onWorkspaceShortcut?.invoke(action) == true
        }
        return true
    }

    fun apply(command: FormatCommand) {
        // These are consumed by EditorPane; accepting them here as no-ops keeps this view's
        // command surface exhaustive without turning panel lifetime into a document edit.
        if (
            command == FormatCommand.DeactivateTextInput ||
            command == FormatCommand.RetainInlineTarget ||
            command == FormatCommand.ReleaseInlineTarget ||
            command == FormatCommand.ClearCanvasSelection ||
            command == FormatCommand.DeleteCanvasSelection
        ) return
        if (commandInterceptor?.invoke(command) == true) return
        asCommand { applyCommand(command) }
    }

    private fun applyCommand(command: FormatCommand) {
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
            is FormatCommand.InsertLink -> insertLink(editable, command.text, command.url, from, to)
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
            FormatCommand.RetainInlineTarget,
            FormatCommand.ReleaseInlineTarget,
            // The canvas's selection, not this view's — `EditorPane` holds it and consumes these.
            FormatCommand.ClearCanvasSelection,
            FormatCommand.DeleteCanvasSelection,
            -> Unit
        }
        suppressWatcher = false

        onBlocksChanged?.invoke(SpannableCodec.parse(editable))
        if (command.affectsEquationMetrics()) hydrateEquations()
        emitSelectionState()
        refreshAutoEquations()
        refreshLinkStyles()
        refreshVideoEmbeds()
    }

    /**
     * Paste keeps what another app copied as HTML — a web page, Google Docs — as the headings,
     * lists and marks it showed there ([pastedHtmlBlocks]), in the font and size at the caret.
     *
     * Every Paste lands here: the ribbon's, the selection toolbar's and Ctrl+V. HTML with no text in
     * it — a picture's — and a whole site copied, too long to read without a stall, paste their plain
     * text, not the platform's reading of the HTML. Paste as plain text and a clip with no HTML, such
     * as this app's own Copy, are left to the platform, as before.
     */
    override fun onTextContextMenuItem(id: Int): Boolean = when (id) {
        // `TextView`'s own undo knows characters only, and would put text back without its
        // formatting: Ctrl+Z, Ctrl+Y and a keyboard's undo key all take the page's history instead,
        // whether or not there is anything in it.
        android.R.id.undo -> {
            onWorkspaceShortcut?.invoke(ShortcutAction.Undo)
            true
        }
        android.R.id.redo -> {
            onWorkspaceShortcut?.invoke(ShortcutAction.Redo)
            true
        }
        // The selection toolbar's, which reach the words selected together as the ribbon's do.
        android.R.id.cut, android.R.id.copy, android.R.id.paste, android.R.id.pasteAsPlainText ->
            commandInterceptor?.invoke(FormatCommand.Clipboard(clipboardAction(id))) == true ||
                asCommand { contextMenuItem(id) }
        else -> asCommand { contextMenuItem(id) }
    }

    private fun clipboardAction(id: Int): ClipboardAction = when (id) {
        android.R.id.cut -> ClipboardAction.Cut
        android.R.id.copy -> ClipboardAction.Copy
        android.R.id.paste -> ClipboardAction.Paste
        else -> ClipboardAction.PasteAsPlainText
    }

    private fun contextMenuItem(id: Int): Boolean {
        if (id != android.R.id.paste) return super.onTextContextMenuItem(id)
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        // The description first: reading the clip itself is what Android announces, and the
        // platform's paste would read it a second time.
        if (clipboard?.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) != true) {
            return super.onTextContextMenuItem(id)
        }
        val html = clipboard.primaryClip?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.htmlText
            ?: return super.onTextContextMenuItem(id)
        val fragment = html.takeIf { it.length <= MAX_PASTED_HTML_CHARS }?.let { pastedHtmlBlocks(it, typingMarks()) }
            ?: return super.onTextContextMenuItem(android.R.id.pasteAsPlainText)
        pasteFormatted(fragment)
        return true
    }

    /** What typing at the caret would be drawn in: what [carryFontForward] gives a new character. */
    private fun typingMarks(): Set<Mark> {
        val editable = text ?: return defaultMarks
        val inherited = SpannableCodec.inheritedMarks(editable, minOf(selectionStart, selectionEnd).coerceAtLeast(0))
            .overriddenBy(pendingMarks)
        return inherited + defaultMarks.filterNot { default -> inherited.any { it.sameKindAs(default) } }
    }

    /**
     * Puts [fragment] over the selection. Its first paragraph joins the one the caret is in and its
     * last runs on into the text after the selection, each keeping that paragraph's style, unless
     * the paste begins or ends the paragraph, which then takes the pasted one's. A single heading,
     * list item, quote or code line pasted where it fills its paragraph keeps its style: it was copied
     * as that paragraph, where a piece of a paragraph copied here is words out of one.
     */
    private fun pasteFormatted(fragment: List<Block>) {
        val editable = text ?: return
        if (BaseInputConnection.getComposingSpanStart(editable) >= 0) {
            BaseInputConnection.removeComposingSpans(editable)
        }
        val from = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val to = maxOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val paragraphs = SpannableCodec.paragraphs(editable)
        val first = paragraphs.first { from in it.first..it.second }
        val last = paragraphs.first { to in it.first..it.second }
        fun plain() = BlockSpan(newId(), BlockType.Paragraph, 0, Align.Start, null)
        val head = SpannableCodec.blockAt(editable, from) ?: plain()
        val tail = SpannableCodec.blockAt(editable, to) ?: plain()
        val startsParagraph = from == first.first
        val endsParagraph = to == last.second
        fun BlockSpan.styledAs(block: Block) =
            copy(type = block.type, indent = block.indent, align = block.align, checked = block.checked)

        val styles = if (fragment.size == 1) {
            val single = fragment.single()
            listOf(if (single.type != BlockType.Paragraph && startsParagraph && endsParagraph) head.styledAs(single) else head)
        } else {
            buildList {
                add(if (startsParagraph) head.styledAs(fragment.first()) else head)
                fragment.subList(1, fragment.lastIndex).forEach {
                    add(BlockSpan(it.id, it.type, it.indent, it.align, it.checked))
                }
                // As Enter splits it: the rest of the paragraph continues it, a to-do unticked.
                val after = if (first == last) {
                    BlockSpan(newId(), head.type, head.indent, head.align, head.checked?.let { false })
                } else {
                    tail
                }
                add(if (endsParagraph) after.styledAs(fragment.last()) else after)
            }
        }

        val inserted = SpannableCodec.render(fragment, editorStyle)
        suppressWatcher = true
        editable.replace(from, to, inserted)
        SpannableCodec.normalize(editable, editorStyle)
        SpannableCodec.restyleParagraphs(editable, from, styles, editorStyle)
        suppressWatcher = false
        // Typing on continues in the formatting just pasted, as it would after that text was typed.
        setSelection(from + inserted.length)

        onBlocksChanged?.invoke(SpannableCodec.parse(editable))
        emitSelectionState()
        refreshAutoEquations()
        refreshLinkStyles()
        refreshVideoEmbeds()
    }

    /**
     * Ctrl+Enter: ends the paragraph at the caret as Enter does, but the paragraph that follows is a
     * plain one — no bullet, number, to-do box, quote, code or heading — which is how a list is left
     * without first emptying an item. It keeps the indent and alignment Enter would carry on. A
     * selection is replaced, as by typing.
     */
    private fun insertPlainParagraph(): Unit = asCommand {
        val editable = text ?: return@asCommand
        if (BaseInputConnection.getComposingSpanStart(editable) >= 0) {
            BaseInputConnection.removeComposingSpans(editable)
        }
        val from = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val to = maxOf(selectionStart, selectionEnd).coerceAtLeast(0)
        // Through the watcher, as the newline Enter types: the new paragraph continues the old one,
        // and is then made a plain one.
        editable.replace(from, to, "\n")
        setSelection(from + 1)
        suppressWatcher = true
        SpannableCodec.updateBlocks(editable, from + 1, from + 1, editorStyle) {
            it.copy(type = BlockType.Paragraph, checked = null)
        }
        suppressWatcher = false
        onBlocksChanged?.invoke(SpannableCodec.parse(editable))
        emitSelectionState()
    }

    private fun insertLink(editable: Editable, label: String, url: String, from: Int, to: Int) {
        val existing = if (from == to) linkAtCaret(editable, from) else null
        val start = existing?.let { editable.getSpanStart(it) } ?: from
        val end = existing?.let { editable.getSpanEnd(it) } ?: to
        if (label.isEmpty()) return
        if (editable.subSequence(start, end).toString() != label) editable.replace(start, end, label)
        SpannableCodec.removeMark(editable, Mark.Link(""), start, start + label.length)
        SpannableCodec.applyMark(editable, Mark.Link(url), start, start + label.length)
        SpannableCodec.normalize(editable, editorStyle)
        setSelection(start + label.length)
    }

    private fun linkAtCaret(editable: Editable, offset: Int): URLSpan? =
        editable.getSpans(0, editable.length, URLSpan::class.java).firstOrNull {
            offset in editable.getSpanStart(it)..editable.getSpanEnd(it)
        }

    /** These colour spans are a view of bare YouTube URLs, not stored document marks. */
    private fun refreshLinkStyles() {
        val editable = text ?: return
        editable.getSpans(0, editable.length, AutoVideoLinkSpan::class.java)
            .forEach(editable::removeSpan)
        findVideoLinks(editable.toString()).forEach { link ->
            val marked = editable.getSpans(link.start, link.end, URLSpan::class.java).any {
                editable.getSpanStart(it) <= link.start && editable.getSpanEnd(it) >= link.end
            }
            if (!marked) editable.setSpan(
                AutoVideoLinkSpan(linkColor, link.url), link.start, link.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
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
        // These cheap zero-width spans are rebuilt from the candidate whose renderer remains. They
        // never cross a newline, because Android measures replacement spans once per paragraph.
        editable.getSpans(0, editable.length, HiddenEquationSourceSpan::class.java).forEach { span ->
            editable.removeSpan(span)
            spansChanged = true
        }
        editable.getSpans(0, editable.length, LiveEquationSpan::class.java).forEach { span ->
            val start = editable.getSpanStart(span)
            val end = editable.getSpanEnd(span)
            val candidate = candidates.firstOrNull {
                it.start == span.sourceStart && it.end == span.sourceEnd &&
                    it.renderStart == start && it.renderEnd == end && it.latex == span.latex &&
                    equationRenderKey(editable, it).let { key ->
                        span.renderSizePx == key.sizePx && span.renderColor == key.color
                    }
            }
            if (candidate == null || isEditing(candidate)) {
                editable.removeSpan(span)
                spansChanged = true
            } else {
                applyHiddenEquationSource(editable, candidate)
            }
        }

        val missing = candidates.filterNot(::isEditing).filterNot { candidate ->
            editable.getSpans(candidate.renderStart, candidate.renderEnd, LiveEquationSpan::class.java).any {
                editable.getSpanStart(it) == candidate.renderStart &&
                    editable.getSpanEnd(it) == candidate.renderEnd &&
                    it.sourceStart == candidate.start && it.sourceEnd == candidate.end &&
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
        if (editable.getSpans(candidate.renderStart, candidate.renderEnd, LiveEquationSpan::class.java).any {
                editable.getSpanStart(it) == candidate.renderStart &&
                    editable.getSpanEnd(it) == candidate.renderEnd &&
                    it.sourceStart == candidate.start && it.sourceEnd == candidate.end
            }
        ) return
        val span = LiveEquationSpan(
            latex = candidate.latex,
            renderSizePx = key.sizePx,
            renderColor = key.color,
            sourceStart = candidate.start,
            sourceEnd = candidate.end,
        )
        span.show(renderer)
        editable.setSpan(
            span,
            candidate.renderStart,
            candidate.renderEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        applyHiddenEquationSource(editable, candidate)
    }

    /** Hides a block's delimiters and non-anchor lines without swallowing paragraph separators. */
    private fun applyHiddenEquationSource(
        editable: Editable,
        candidate: AutoEquationCandidate,
    ) {
        if (candidate.renderStart == candidate.start && candidate.renderEnd == candidate.end) return

        var hiddenStart = -1
        for (offset in candidate.start..candidate.end) {
            val hidden = offset < candidate.end &&
                offset !in candidate.renderStart until candidate.renderEnd &&
                editable[offset] != '\n'
            if (hidden && hiddenStart < 0) hiddenStart = offset
            if (!hidden && hiddenStart >= 0) {
                editable.setSpan(
                    HiddenEquationSourceSpan(),
                    hiddenStart,
                    offset,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                hiddenStart = -1
            }
        }
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
        ) * EQUATION_FONT_SCALE
    }

    private fun refreshEquationLayout() {
        requestLayout()
        invalidate()
    }

    /**
     * Draws each pasted video link as its thumbnail while leaving the URL itself as ordinary text.
     *
     * The same shape as [refreshAutoEquations]. A card is absent whenever the focused caret or
     * selection touches its range, so the URL comes back the moment someone goes to edit it; a link
     * whose thumbnail has not arrived simply never gets a span and stays readable.
     *
     * Every span is rebuilt rather than reconciled, because `SpannableCodec.normalize` has already
     * stripped them. What makes that affordable is that the bitmaps are already decoded —
     * [VideoThumbnails.cached] never touches disk — so a keystroke costs a `findVideoLinks` scan and
     * a handful of `setSpan` calls.
     */
    private fun refreshVideoEmbeds() {
        val editable = text ?: return
        val source = videoThumbnails
        val existing = editable.getSpans(0, editable.length, VideoEmbedSpan::class.java)
        val links = if (source == null) emptyList() else findVideoLinks(editable.toString())
        val cardWidth = embedMaxWidthPx()

        var changed = false
        existing.forEach { span ->
            val start = editable.getSpanStart(span)
            val end = editable.getSpanEnd(span)
            val link = links.firstOrNull {
                it.start == start && it.end == end && it.videoId == span.videoId
            }
            if (link == null || isEditing(link) || span.maxWidthPx != cardWidth) {
                editable.removeSpan(span)
                changed = true
            }
        }

        if (source == null || cardWidth <= 0) {
            if (changed) refreshEquationLayout()
            return
        }

        links.filterNot(::isEditing).forEach { link ->
            val covered = editable.getSpans(link.start, link.end, VideoEmbedSpan::class.java).any {
                editable.getSpanStart(it) == link.start && editable.getSpanEnd(it) == link.end
            }
            if (covered) return@forEach

            val thumbnail = source.cached(link.videoId)
            if (thumbnail == null) {
                // Posted rather than called straight back: the callback lands on the main thread
                // while this pass may still be walking the buffer, and re-entering it from inside
                // its own loop would apply spans against offsets it has not finished with.
                source.request(link.videoId) { post { refreshVideoEmbeds() } }
                return@forEach
            }
            editable.setSpan(
                VideoEmbedSpan(
                    videoId = link.videoId,
                    url = link.url,
                    thumbnail = thumbnail,
                    maxWidthPx = cardWidth,
                    density = resources.displayMetrics.density,
                ),
                link.start,
                link.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            changed = true
        }
        if (changed) refreshEquationLayout()
    }

    /** The content width a card may occupy — see [VideoEmbedSpan.maxWidthPx] for why it is capped. */
    private fun embedMaxWidthPx(): Int = width - totalPaddingLeft - totalPaddingRight

    private fun isEditing(link: VideoLink): Boolean =
        rangeIsBeingEdited(link.start, link.end, hasFocus(), selectionStart, selectionEnd)

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

    /**
     * Keeps the user's selection intact while making a partially selected formula atomic.
     *
     * The old path returned the first equation touched by any selection. With Select all, that
     * collapsed the formatting target to the first formula, so later formulas and prose never saw
     * the chosen size. A real selection remains the target and grows only where one of its edges
     * cuts through a formula; a caret still resolves to the single formula beside it.
     */
    private fun equationFormattingRange(editable: Editable, from: Int, to: Int): EquationRange? {
        if (from == to) {
            SpannableCodec.equationAt(editable, from, to)?.let { return it }
            return findAutoEquationCandidates(editable.toString())
                .firstOrNull { it.isBeingEdited(true, from, to) }
                ?.let { EquationRange(it.latex, it.start, it.end) }
        }

        var expandedStart = from
        var expandedEnd = to
        var touchesEquation = false

        editable.getSpans(0, editable.length, EquationSpan::class.java).forEach { span ->
            val start = editable.getSpanStart(span)
            val end = editable.getSpanEnd(span)
            if (start < to && end > from) {
                touchesEquation = true
                expandedStart = minOf(expandedStart, start)
                expandedEnd = maxOf(expandedEnd, end)
            }
        }
        findAutoEquationCandidates(editable.toString()).forEach { candidate ->
            if (candidate.start < to && candidate.end > from) {
                touchesEquation = true
                expandedStart = minOf(expandedStart, candidate.start)
                expandedEnd = maxOf(expandedEnd, candidate.end)
            }
        }

        return if (touchesEquation) {
            EquationRange(latex = "", start = expandedStart, end = expandedEnd)
        } else {
            null
        }
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
                linkText = if (caret) linkAtCaret(editable, from)?.let {
                    editable.subSequence(editable.getSpanStart(it), editable.getSpanEnd(it)).toString()
                }.orEmpty() else editable.subSequence(from, to).toString(),
                linkUrl = if (caret) linkAtCaret(editable, from)?.url
                    else marks.filterIsInstance<Mark.Link>().firstOrNull()?.href,
                editorFocused = hasFocus(),
            ),
        )
    }

    private companion object {
        const val MAX_INDENT = 8
        const val MAX_PASTED_HTML_CHARS = 4 * 1024 * 1024
        const val AUTO_EQUATION_CACHE_SIZE = 48
    }

    private data class EquationRenderKey(val latex: String, val sizePx: Float, val color: Int)
    private data class PendingAutoEquation(
        val candidate: AutoEquationCandidate,
        val key: EquationRenderKey,
    )
}

private class AutoVideoLinkSpan(color: Int, val url: String) : ForegroundColorSpan(color), Derived

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
