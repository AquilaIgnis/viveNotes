package st.unamedtba.richtext

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import st.unamedtba.model.Align
import st.unamedtba.model.BlockType

/**
 * Marks a span as generated from block metadata rather than from a user's inline formatting.
 *
 * Derived spans are thrown away and rebuilt on every normalise pass, and are skipped when reading
 * marks back out. Without this distinction a heading's bold would be indistinguishable from bold
 * the user applied, and would survive as an inline mark after the heading was removed.
 */
interface Derived

/**
 * Carries a block's identity and paragraph attributes through editing.
 *
 * This is the only paragraph span that is not derived: it *is* the block metadata. Holding the id
 * here means block identity survives a render/parse round trip instead of being regenerated,
 * which keeps ids stable for future per-block sync.
 */
class BlockSpan(
    val id: String,
    val type: BlockType,
    val indent: Int,
    val align: Align,
    val checked: Boolean?,
) : android.text.style.ParagraphStyle {

    fun copy(
        type: BlockType = this.type,
        indent: Int = this.indent,
        align: Align = this.align,
        checked: Boolean? = this.checked,
    ) = BlockSpan(id, type, indent, align, checked)
}

/** Draws "1." style ordinals. Android ships [android.text.style.BulletSpan] but no numbered equivalent. */
class NumberSpan(
    private val ordinal: Int,
    private val gapWidth: Int,
) : LeadingMarginSpan, Derived {

    override fun getLeadingMargin(first: Boolean): Int = gapWidth

    override fun drawLeadingMargin(
        canvas: Canvas,
        paint: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: Layout?,
    ) {
        // Only the first line of a wrapped paragraph gets the ordinal.
        if (!first || (text as? Spanned)?.getSpanStart(this) != start) return
        val label = "$ordinal."
        val previousStyle = paint.style
        canvas.drawText(label, x + dir * (gapWidth - paint.measureText(label) - gapWidth / 6f), baseline.toFloat(), paint)
        paint.style = previousStyle
    }
}

/** Draws an unchecked/checked box ahead of a to-do block. */
class TodoSpan(
    private val checked: Boolean,
    private val gapWidth: Int,
    private val color: Int,
) : LeadingMarginSpan, Derived {

    override fun getLeadingMargin(first: Boolean): Int = gapWidth

    override fun drawLeadingMargin(
        canvas: Canvas,
        paint: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: Layout?,
    ) {
        if (!first || (text as? Spanned)?.getSpanStart(this) != start) return
        val size = (paint.textSize * 0.7f)
        val left = x + dir * (gapWidth * 0.25f)
        val top1 = baseline - size * 0.85f
        val previousColor = paint.color
        val previousStyle = paint.style
        val previousWidth = paint.strokeWidth

        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.09f
        canvas.drawRoundRect(left, top1, left + size, top1 + size, size * 0.15f, size * 0.15f, paint)

        if (checked) {
            paint.strokeWidth = size * 0.14f
            canvas.drawLine(left + size * 0.22f, top1 + size * 0.52f, left + size * 0.42f, top1 + size * 0.74f, paint)
            canvas.drawLine(left + size * 0.42f, top1 + size * 0.74f, left + size * 0.80f, top1 + size * 0.26f, paint)
        }

        paint.color = previousColor
        paint.style = previousStyle
        paint.strokeWidth = previousWidth
    }
}

class HeadingBoldSpan : StyleSpan(Typeface.BOLD), Derived

class HeadingSizeSpan(scale: Float) : RelativeSizeSpan(scale), Derived

class CodeTypefaceSpan : TypefaceSpan("monospace"), Derived

class CodeBackgroundSpan(color: Int) : BackgroundColorSpan(color), Derived

/** Size reduction paired with sub/superscript. Never read back as a mark. */
class ScriptSizeSpan : RelativeSizeSpan(0.75f)

val BlockType.headingScale: Float?
    get() = when (this) {
        BlockType.Heading1 -> 1.55f
        BlockType.Heading2 -> 1.30f
        BlockType.Heading3 -> 1.12f
        else -> null
    }

val BlockType.isList: Boolean
    get() = this == BlockType.Bullet || this == BlockType.Numbered || this == BlockType.Todo
