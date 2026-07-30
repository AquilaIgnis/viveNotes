package st.unamedtba.richtext

import android.graphics.Typeface
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import st.unamedtba.model.Align
import st.unamedtba.model.Block
import st.unamedtba.model.BlockType
import st.unamedtba.model.Mark
import st.unamedtba.model.Run
import st.unamedtba.model.newId

/** Pixel metrics the codec needs; supplied by the view so the codec stays density-agnostic. */
data class EditorStyle(
    val indentStepPx: Int,
    val listGapPx: Int,
    val bulletRadiusPx: Int,
    val accentColor: Int,
    val codeBackgroundColor: Int,
    val quoteColor: Int,
)

/**
 * Converts between [Block] lists and [Spannable] text.
 *
 * The whole outline lives in one [android.widget.EditText]: blocks are paragraphs separated by
 * newlines, block attributes ride on a [BlockSpan], and inline marks are ordinary character spans
 * so that Android's own text machinery extends them as the user types.
 */
object SpannableCodec {

    fun render(blocks: List<Block>, style: EditorStyle): SpannableStringBuilder {
        val out = SpannableStringBuilder()

        // Spans are collected while the text is assembled and applied only once it is complete.
        // Inline marks are end-inclusive so that typing after bold text stays bold, which also
        // means applying one before appending the next run would let it swallow that run.
        val markRanges = mutableListOf<Triple<Mark, Int, Int>>()
        val blockRanges = mutableListOf<Triple<BlockSpan, Int, Int>>()

        blocks.forEachIndexed { index, block ->
            val start = out.length
            block.runs.forEach { run ->
                val runStart = out.length
                out.append(run.text)
                run.marks.forEach { mark -> markRanges += Triple(mark, runStart, out.length) }
            }
            // An empty block still needs a paragraph to hang its BlockSpan on.
            blockRanges += Triple(
                BlockSpan(block.id, block.type, block.indent, block.align, block.checked),
                start,
                out.length,
            )
            if (index != blocks.lastIndex) out.append('\n')
        }

        blockRanges.forEach { (span, start, end) ->
            out.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        markRanges.forEach { (mark, start, end) -> applyMark(out, mark, start, end) }

        if (blocks.isEmpty()) {
            out.setSpan(BlockSpan(newId(), BlockType.Paragraph, 0, Align.Start, null), 0, 0, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        applyDerived(out, style)
        return out
    }

    fun parse(text: Spanned): List<Block> = paragraphs(text).map { (start, end) ->
        val meta = text.getSpans(start, end, BlockSpan::class.java)
            .firstOrNull { text.getSpanStart(it) <= start && text.getSpanEnd(it) >= end }
        Block(
            id = meta?.id ?: newId(),
            type = meta?.type ?: BlockType.Paragraph,
            indent = meta?.indent ?: 0,
            align = meta?.align ?: Align.Start,
            checked = meta?.checked,
            runs = readRuns(text, start, end),
        )
    }

    /**
     * Re-establishes the invariant that every paragraph has exactly one [BlockSpan] covering it,
     * then rebuilds all derived spans.
     *
     * Editing constantly violates that invariant — splitting a paragraph with Enter leaves the new
     * half uncovered, and merging with Backspace leaves two spans overlapping. Rather than trying
     * to patch each edit, the editor normalises after every change. New paragraphs inherit their
     * predecessor's attributes, which is what makes pressing Enter inside a bulleted list continue
     * the list.
     */
    fun normalize(editable: Editable, style: EditorStyle) {
        val paragraphs = paragraphs(editable)

        val existing = editable.getSpans(0, editable.length, BlockSpan::class.java)
            .associateBy { editable.getSpanStart(it) }
        editable.getSpans(0, editable.length, BlockSpan::class.java).forEach(editable::removeSpan)

        var inherited: BlockSpan? = null
        for ((start, end) in paragraphs) {
            val previous = existing[start]
                ?: existing.entries.firstOrNull { it.key in start..end }?.value
            val meta = when {
                previous != null -> previous
                // A paragraph created mid-edit continues the block it was split from, except that
                // a new to-do starts unchecked rather than inheriting a tick.
                inherited != null -> BlockSpan(newId(), inherited.type, inherited.indent, inherited.align, inherited.checked?.let { false })
                else -> BlockSpan(newId(), BlockType.Paragraph, 0, Align.Start, null)
            }
            editable.setSpan(meta, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            inherited = meta
        }

        applyDerived(editable, style)
    }

    /** Replaces the [BlockSpan] on every paragraph intersecting [selStart]..[selEnd]. */
    fun updateBlocks(
        editable: Editable,
        selStart: Int,
        selEnd: Int,
        style: EditorStyle,
        transform: (BlockSpan) -> BlockSpan,
    ) {
        for ((start, end) in paragraphs(editable)) {
            if (end < selStart || start > selEnd) continue
            val current = editable.getSpans(start, end, BlockSpan::class.java)
                .firstOrNull { editable.getSpanStart(it) <= start && editable.getSpanEnd(it) >= end }
                ?: BlockSpan(newId(), BlockType.Paragraph, 0, Align.Start, null)
            editable.removeSpan(current)
            editable.setSpan(transform(current), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        applyDerived(editable, style)
    }

    fun blockAt(text: Spanned, position: Int): BlockSpan? {
        val (start, end) = paragraphs(text).firstOrNull { position in it.first..it.second } ?: return null
        return text.getSpans(start, end, BlockSpan::class.java)
            .firstOrNull { text.getSpanStart(it) <= start && text.getSpanEnd(it) >= end }
    }

    // --- inline marks --------------------------------------------------------------------------

    fun applyMark(text: Spannable, mark: Mark, start: Int, end: Int) {
        if (start >= end) return
        // INCLUSIVE at the end so typing immediately after formatted text continues the format,
        // which is what every word processor does.
        val flags = Spannable.SPAN_EXCLUSIVE_INCLUSIVE
        when (mark) {
            Mark.Bold -> text.setSpan(StyleSpan(Typeface.BOLD), start, end, flags)
            Mark.Italic -> text.setSpan(StyleSpan(Typeface.ITALIC), start, end, flags)
            Mark.Underline -> text.setSpan(UnderlineSpan(), start, end, flags)
            Mark.Strikethrough -> text.setSpan(StrikethroughSpan(), start, end, flags)
            Mark.Subscript -> {
                text.setSpan(SubscriptSpan(), start, end, flags)
                text.setSpan(ScriptSizeSpan(), start, end, flags)
            }
            Mark.Superscript -> {
                text.setSpan(SuperscriptSpan(), start, end, flags)
                text.setSpan(ScriptSizeSpan(), start, end, flags)
            }
            is Mark.TextColor -> text.setSpan(ForegroundColorSpan(mark.argb), start, end, flags)
            is Mark.Highlight -> text.setSpan(BackgroundColorSpan(mark.argb), start, end, flags)
            is Mark.FontSize -> text.setSpan(AbsoluteSizeSpan(mark.sp, true), start, end, flags)
            is Mark.FontFamily -> text.setSpan(TypefaceSpan(mark.name), start, end, flags)
            is Mark.Link -> text.setSpan(URLSpan(mark.href), start, end, flags)
        }
    }

    fun removeMark(text: Spannable, mark: Mark, start: Int, end: Int) {
        text.getSpans(start, end, Any::class.java).forEach { span ->
            if (span is Derived || markOf(span) != mark.erased()) return@forEach
            splitAround(text, span, start, end)
        }
    }

    /** Strips every inline mark in the range, leaving block attributes and derived spans alone. */
    fun clearMarks(text: Spannable, start: Int, end: Int) {
        if (start >= end) return
        text.getSpans(start, end, Any::class.java).forEach { span ->
            if (span is Derived || span is BlockSpan) return@forEach
            if (markOf(span) == null && span !is ScriptSizeSpan) return@forEach
            splitAround(text, span, start, end)
        }
    }

    fun marksAt(text: Spanned, start: Int, end: Int): Set<Mark> {
        // A mark counts as active only if it covers the entire selection; partial coverage reads
        // as "not set", so toggling applies it to everything rather than clearing it.
        return text.getSpans(start, end, Any::class.java)
            .filterNot { it is Derived }
            .mapNotNull { span ->
                val mark = markOf(span) ?: return@mapNotNull null
                if (start == end) {
                    if (text.getSpanStart(span) <= start && text.getSpanEnd(span) >= start) mark else null
                } else {
                    if (text.getSpanStart(span) <= start && text.getSpanEnd(span) >= end) mark else null
                }
            }
            .toSet()
    }

    private fun markOf(span: Any): Mark? = when (span) {
        is StyleSpan -> when (span.style) {
            Typeface.BOLD -> Mark.Bold
            Typeface.ITALIC -> Mark.Italic
            else -> null
        }
        is UnderlineSpan -> Mark.Underline
        is StrikethroughSpan -> Mark.Strikethrough
        is SubscriptSpan -> Mark.Subscript
        is SuperscriptSpan -> Mark.Superscript
        is ForegroundColorSpan -> Mark.TextColor(span.foregroundColor)
        is BackgroundColorSpan -> Mark.Highlight(span.backgroundColor)
        is AbsoluteSizeSpan -> Mark.FontSize(span.size)
        is URLSpan -> Mark.Link(span.url)
        is TypefaceSpan -> span.family?.let { Mark.FontFamily(it) }
        else -> null
    }

    /** Parameterised marks compare by kind when removing, so any colour clears any other colour. */
    private fun Mark.erased(): Mark = when (this) {
        is Mark.TextColor -> Mark.TextColor(0)
        is Mark.Highlight -> Mark.Highlight(0)
        is Mark.FontSize -> Mark.FontSize(0)
        is Mark.FontFamily -> Mark.FontFamily("")
        is Mark.Link -> Mark.Link("")
        else -> this
    }

    /** Trims a span back so it no longer covers [start]..[end], keeping any overhang on either side. */
    private fun splitAround(text: Spannable, span: Any, start: Int, end: Int) {
        val spanStart = text.getSpanStart(span)
        val spanEnd = text.getSpanEnd(span)
        val flags = text.getSpanFlags(span)
        text.removeSpan(span)
        if (spanStart < start) text.setSpan(cloneSpan(span), spanStart, start, flags)
        if (spanEnd > end) text.setSpan(cloneSpan(span), end, spanEnd, flags)
    }

    private fun cloneSpan(span: Any): Any = when (span) {
        is StyleSpan -> StyleSpan(span.style)
        is UnderlineSpan -> UnderlineSpan()
        is StrikethroughSpan -> StrikethroughSpan()
        is SubscriptSpan -> SubscriptSpan()
        is SuperscriptSpan -> SuperscriptSpan()
        is ScriptSizeSpan -> ScriptSizeSpan()
        is ForegroundColorSpan -> ForegroundColorSpan(span.foregroundColor)
        is BackgroundColorSpan -> BackgroundColorSpan(span.backgroundColor)
        is AbsoluteSizeSpan -> AbsoluteSizeSpan(span.size, span.dip)
        is URLSpan -> URLSpan(span.url)
        is TypefaceSpan -> TypefaceSpan(span.family)
        else -> span
    }

    private fun readRuns(text: Spanned, start: Int, end: Int): List<Run> {
        if (start >= end) return emptyList()

        // Every span boundary inside the paragraph starts a new run.
        val boundaries = sortedSetOf(start, end)
        text.getSpans(start, end, Any::class.java).forEach { span ->
            if (span is Derived || span is BlockSpan || markOf(span) == null) return@forEach
            text.getSpanStart(span).coerceIn(start, end).let(boundaries::add)
            text.getSpanEnd(span).coerceIn(start, end).let(boundaries::add)
        }

        val points = boundaries.toList()
        val runs = mutableListOf<Run>()
        for (i in 0 until points.lastIndex) {
            val from = points[i]
            val to = points[i + 1]
            if (from >= to) continue
            val marks = marksAt(text, from, to)
            val chunk = text.subSequence(from, to).toString()
            // Merge into the previous run when the formatting is unchanged, so the stored document
            // has one run per distinct style rather than one per span boundary.
            val last = runs.lastOrNull()
            if (last != null && last.marks == marks) {
                runs[runs.lastIndex] = last.copy(text = last.text + chunk)
            } else {
                runs += Run(chunk, marks)
            }
        }
        return runs
    }

    // --- derived paragraph spans ---------------------------------------------------------------

    private fun applyDerived(text: Spannable, style: EditorStyle) {
        text.getSpans(0, text.length, Any::class.java).forEach { span ->
            val removable = span is Derived ||
                span is BulletSpan ||
                span is QuoteSpan ||
                span is AlignmentSpan.Standard ||
                span is LeadingMarginSpan.Standard
            if (removable) text.removeSpan(span)
        }

        var ordinal = 0
        var previousWasNumbered = false

        for ((start, end) in paragraphs(text)) {
            val meta = text.getSpans(start, end, BlockSpan::class.java)
                .firstOrNull { text.getSpanStart(it) <= start && text.getSpanEnd(it) >= end }
                ?: continue
            val flags = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE

            // Numbering restarts whenever a run of numbered blocks is interrupted.
            if (meta.type == BlockType.Numbered) {
                ordinal = if (previousWasNumbered) ordinal + 1 else 1
            }
            previousWasNumbered = meta.type == BlockType.Numbered

            val baseIndent = meta.indent * style.indentStepPx
            when (meta.type) {
                BlockType.Bullet ->
                    text.setSpan(BulletSpanCompat(style, baseIndent), start, end, flags)
                BlockType.Numbered -> {
                    if (baseIndent > 0) text.setSpan(LeadingMarginSpan.Standard(baseIndent), start, end, flags)
                    text.setSpan(NumberSpan(ordinal, style.listGapPx), start, end, flags)
                }
                BlockType.Todo -> {
                    if (baseIndent > 0) text.setSpan(LeadingMarginSpan.Standard(baseIndent), start, end, flags)
                    text.setSpan(TodoSpan(meta.checked == true, style.listGapPx, style.accentColor), start, end, flags)
                }
                BlockType.Quote -> {
                    text.setSpan(QuoteSpan(style.quoteColor), start, end, flags)
                    if (baseIndent > 0) text.setSpan(LeadingMarginSpan.Standard(baseIndent), start, end, flags)
                }
                BlockType.Code -> {
                    text.setSpan(CodeTypefaceSpan(), start, end, flags)
                    text.setSpan(CodeBackgroundSpan(style.codeBackgroundColor), start, end, flags)
                    if (baseIndent > 0) text.setSpan(LeadingMarginSpan.Standard(baseIndent), start, end, flags)
                }
                else -> if (baseIndent > 0) text.setSpan(LeadingMarginSpan.Standard(baseIndent), start, end, flags)
            }

            meta.type.headingScale?.let { scale ->
                text.setSpan(HeadingSizeSpan(scale), start, end, flags)
                text.setSpan(HeadingBoldSpan(), start, end, flags)
            }

            if (meta.align != Align.Start) {
                val alignment = when (meta.align) {
                    Align.Center -> android.text.Layout.Alignment.ALIGN_CENTER
                    Align.End -> android.text.Layout.Alignment.ALIGN_OPPOSITE
                    Align.Start -> android.text.Layout.Alignment.ALIGN_NORMAL
                }
                text.setSpan(AlignmentSpan.Standard(alignment), start, end, flags)
            }
        }
    }

    @Suppress("FunctionName")
    private fun BulletSpanCompat(style: EditorStyle, extraIndent: Int): BulletSpan =
        BulletSpan(style.listGapPx + extraIndent, style.accentColor, style.bulletRadiusPx)

    /**
     * Paragraph ranges as [start, end) where end excludes the trailing newline. A trailing newline
     * at the very end of the text yields a final empty paragraph, matching how the user sees it.
     */
    fun paragraphs(text: CharSequence): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        var start = 0
        for (i in text.indices) {
            if (text[i] == '\n') {
                result += start to i
                start = i + 1
            }
        }
        result += start to text.length
        return result
    }
}
