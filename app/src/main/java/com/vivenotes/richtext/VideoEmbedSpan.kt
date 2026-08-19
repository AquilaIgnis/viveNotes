package com.vivenotes.richtext

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.style.ReplacementSpan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where a video thumbnail comes from, as the editor needs to see it.
 *
 * Declared by the consumer rather than by the implementation — `com.vivenotes.data`.`VideoThumbnailStore`
 * is the one that fetches and caches — so this package stays free of anything that opens a socket,
 * and an editor handed no source simply draws no cards. That null is deliberately load-bearing: it
 * is how the Settings toggle turns the feature off, and it is why a test or a preview of
 * [OutlineEditText] never reaches the network by accident.
 *
 * [cached] must never block: it is called from a text-change pass that runs on every keystroke.
 * [request] is the slow half, and reports back by calling [onReady] on the main thread once — or
 * never, for a video whose thumbnail could not be fetched.
 */
interface VideoThumbnails {

    /** The bitmap if it is already in memory, else null. Never touches disk or network. */
    fun cached(videoId: String): Bitmap?

    /**
     * Starts a fetch for [videoId] unless one is already running or has recently failed.
     *
     * Idempotent, because the caller is a per-keystroke pass and will ask again for every character
     * typed after the link.
     */
    fun request(videoId: String, onReady: () -> Unit)
}

/**
 * A YouTube link drawn as its thumbnail — the pasted URL is still the text underneath.
 *
 * **The URL is the truth; the card is a view.** Exactly the split [LiveEquationSpan] makes over
 * `$x^2$`: nothing is written into [com.vivenotes.model.PageDoc], so there is no new mark, no
 * schema move, and no document an older build cannot open. It also means the preview costs nothing
 * to undo — deleting the card deletes the URL, because they are the same characters — and that a
 * link typed into a note years ago lights up the moment this build opens it.
 *
 * [Derived] for the reason every preview here is: `SpannableCodec` rebuilds derived spans on every
 * normalise pass and skips them when reading marks back, so this can never be mistaken for
 * formatting the user applied and can never survive into a parsed [com.vivenotes.model.Block].
 *
 * The bitmap is held rather than fetched, so this class does no I/O at all. A span only exists once
 * its picture does — an unfetchable video simply keeps its URL visible as text, which is the same
 * answer [RenderedEquationSpan] gives for LaTeX that will not parse.
 */
class VideoEmbedSpan(
    val videoId: String,
    /** What tapping the play badge opens — the pasted URL, so a `t=` offset is honoured. */
    val url: String,
    private val thumbnail: Bitmap,
    /**
     * The widest the card may be drawn, in pixels — the editor's own content width.
     *
     * A [ReplacementSpan] wider than the line it sits on is *clipped*, not wrapped, so this is not
     * a stylistic cap: without it a card in a narrow container or a table cell loses its right-hand
     * edge. Captured at construction and compared on every refresh, which is why the editor rebuilds
     * its cards when its width changes.
     */
    val maxWidthPx: Int,
    private val density: Float,
) : ReplacementSpan(), Derived {

    /** The drawn card, in pixels. 16:9, because that is the frame every thumbnail arrives in. */
    val cardWidthPx: Int = min(maxWidthPx, (MAX_WIDTH_DP * density).roundToInt())
        .coerceAtLeast((MIN_WIDTH_DP * density).roundToInt())

    val cardHeightPx: Int = (cardWidthPx * 9f / 16f).roundToInt()

    private val gapPx: Int = (GAP_DP * density).roundToInt()
    private val cornerPx: Float = CORNER_DP * density
    private val badgeRadiusPx: Float = BADGE_RADIUS_DP * density

    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val cardRect = RectF()
    private val triangle = Path()

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        // The card hangs entirely above the baseline, like an equation's display list, so the line
        // it sits on grows to hold it instead of the card overlapping the text above.
        fm?.let {
            val ascent = -(cardHeightPx + gapPx)
            it.ascent = min(it.ascent, ascent)
            it.top = min(it.top, ascent)
            it.descent = max(it.descent, gapPx)
            it.bottom = max(it.bottom, gapPx)
        }
        return cardWidthPx
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val cardTop = y - cardHeightPx.toFloat()
        cardRect.set(x, cardTop, x + cardWidthPx, cardTop + cardHeightPx)

        shaderPaint.shader = BitmapShader(thumbnail, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            .apply { setLocalMatrix(coverMatrix(cardRect)) }
        canvas.drawRoundRect(cardRect, cornerPx, cornerPx, shaderPaint)
        shaderPaint.shader = null

        // Traced in the text's own colour so the card reads as part of the page in either theme,
        // rather than as a light-mode picture dropped onto a dark one.
        strokePaint.color = paint.color
        strokePaint.alpha = BORDER_ALPHA
        strokePaint.strokeWidth = density
        canvas.drawRoundRect(cardRect, cornerPx, cornerPx, strokePaint)

        drawPlayBadge(canvas, cardRect.centerX(), cardRect.centerY())
    }

    /**
     * Whether ([pointX], [pointY]) — measured from the card's own top-left — is on the play badge.
     *
     * The badge is the only part of the card that takes a tap; the rest of it passes the touch on
     * so the caret can land in the URL and reveal it for editing. Splitting the two is what lets one
     * object be both "open this video" and "this is still just text you can delete".
     */
    fun badgeContains(pointX: Float, pointY: Float): Boolean {
        val dx = pointX - cardWidthPx / 2f
        val dy = pointY - cardHeightPx / 2f
        // Squared, to keep a sqrt out of a hit test that runs on every touch down.
        val reach = badgeRadiusPx * BADGE_TOUCH_SLOP
        return dx * dx + dy * dy <= reach * reach
    }

    /**
     * Scales the thumbnail to cover [into] and centres the overflow.
     *
     * Cover rather than fit, because the fallback thumbnail YouTube always has is 4:3 and letterbox
     * bars inside a 16:9 card look like a broken picture. Cropping a widescreen frame's top and
     * bottom sliver loses nothing anybody was looking at.
     */
    private fun coverMatrix(into: RectF): Matrix {
        val scale = max(into.width() / thumbnail.width, into.height() / thumbnail.height)
        return Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                into.left + (into.width() - thumbnail.width * scale) / 2f,
                into.top + (into.height() - thumbnail.height * scale) / 2f,
            )
        }
    }

    /**
     * A neutral play affordance rather than YouTube's own mark.
     *
     * Deliberately not the red rounded rectangle: reproducing another company's logo inside a
     * document editor is a trademark question this app has no need to answer, and a generic badge is
     * the one that still makes sense when a second provider is added.
     */
    private fun drawPlayBadge(canvas: Canvas, centerX: Float, centerY: Float) {
        fillPaint.color = Color.BLACK
        fillPaint.alpha = BADGE_ALPHA
        canvas.drawCircle(centerX, centerY, badgeRadiusPx, fillPaint)

        val reach = badgeRadiusPx * 0.42f
        triangle.reset()
        // Nudged right by an eighth of its reach: a triangle centred on its bounding box reads as
        // sitting left of centre, because its mass is on the flat side.
        triangle.moveTo(centerX - reach * 0.75f + reach * 0.12f, centerY - reach)
        triangle.lineTo(centerX + reach + reach * 0.12f, centerY)
        triangle.lineTo(centerX - reach * 0.75f + reach * 0.12f, centerY + reach)
        triangle.close()
        fillPaint.color = Color.WHITE
        canvas.drawPath(triangle, fillPaint)
    }

    companion object {
        /**
         * How wide a card gets on a page with room to spare.
         *
         * A container is 720 dp of writing width, so this is half of it — the same proportion
         * `Outline.Image.DEFAULT_WIDTH` picks for an inserted picture, and for the same reason: big
         * enough to recognise the video, small enough not to bury the note it was pasted into.
         */
        const val MAX_WIDTH_DP = 360f

        /** Below this the play badge stops fitting, so a very narrow cell gets a clipped card instead. */
        private const val MIN_WIDTH_DP = 120f

        private const val CORNER_DP = 12f
        private const val GAP_DP = 5f
        private const val BADGE_RADIUS_DP = 21f

        /** Forgiveness around the badge, so the tap target clears the 48 dp minimum. */
        private const val BADGE_TOUCH_SLOP = 1.15f

        private const val BORDER_ALPHA = 46
        private const val BADGE_ALPHA = 150
    }
}
