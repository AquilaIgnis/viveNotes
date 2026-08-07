package com.vivenotes.ink

import com.vivenotes.data.RulerKind
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A ruler lying on the page — `docs/rulerPlan.md`.
 *
 * Page units throughout (RD3), so it stays where it was put when the page scrolls or zooms, and so
 * its graduations are a real inch apart at 100%.
 *
 * Everything here is arithmetic on a rotated frame, with no Android in it, because this is the part
 * that cannot be eyeballed and the emulator is not always available — RD8.
 *
 * @param centerX the middle of the straight ruler, or the centre of the semicircle's flat edge.
 * @param angleRadians rotation about that centre. At 0 the ruler lies along the page's x axis.
 * @param sizeDp the span: edge length for [RulerKind.Straight], diameter for [RulerKind.Protractor].
 */
data class Ruler(
    val centerX: Float,
    val centerY: Float,
    val angleRadians: Float,
    val kind: RulerKind,
    val sizeDp: Float,
) {

    /** Half the span: the distance from the centre to either end, or the arc's radius. */
    val reach: Float get() = sizeDp / 2f

    /**
     * The point in the ruler's own frame: `+x` along it, `+y` across it.
     *
     * The one conversion everything else is written in terms of, so the rotation is in one place
     * rather than repeated with a sign error in half of them.
     */
    fun toLocal(point: InkPoint): InkPoint {
        val dx = point.x - centerX
        val dy = point.y - centerY
        val cos = cos(-angleRadians)
        val sin = sin(-angleRadians)
        return InkPoint(dx * cos - dy * sin, dx * sin + dy * cos)
    }

    /** The inverse of [toLocal]. */
    fun toPage(local: InkPoint): InkPoint {
        val cos = cos(angleRadians)
        val sin = sin(angleRadians)
        return InkPoint(
            centerX + local.x * cos - local.y * sin,
            centerY + local.x * sin + local.y * cos,
        )
    }

    /**
     * Whether a finger landing here takes hold of the ruler.
     *
     * The body, which is the whole of it — there is nothing else to grab, because there are no
     * handles. One finger on it slides it, two turn it (RD4).
     */
    fun grabs(point: InkPoint): Boolean {
        val local = toLocal(point)
        return when (kind) {
            RulerKind.Straight -> abs(local.x) <= reach && abs(local.y) <= BAND_DP / 2f
            RulerKind.Protractor -> local.y <= 0f && hypot(local.x, local.y) <= reach
        }
    }

    /**
     * Whether a stroke starting here is a ruled one — RD5.
     *
     * The ruler is *solid*: on it counts, not merely beside it. [tolerance] is how far past the edge
     * still catches, so a stroke that starts a shade off the ruler is still the line you meant.
     */
    fun engages(point: InkPoint, tolerance: Float): Boolean {
        val local = toLocal(point)
        return when (kind) {
            RulerKind.Straight ->
                abs(local.x) <= reach + tolerance && abs(local.y) <= BAND_DP / 2f + tolerance
            RulerKind.Protractor ->
                local.y <= tolerance && hypot(local.x, local.y) <= reach + tolerance
        }
    }

    /**
     * The point on the drawing edge that [point] becomes.
     *
     * A straight ruler has two long edges and snaps to whichever is nearer, so it draws from either
     * side; ends are clamped, because a ruler runs out. The semicircle projects radially onto its
     * arc, and a point past either end of the arc lands on that end for the same reason.
     */
    fun snap(point: InkPoint): InkPoint {
        val local = toLocal(point)
        return when (kind) {
            RulerKind.Straight -> toPage(
                InkPoint(
                    x = local.x.coerceIn(-reach, reach),
                    // Sitting exactly on the centre line is the one ambiguous case; it takes the
                    // `+` edge rather than flickering between the two.
                    y = if (local.y < 0f) -BAND_DP / 2f else BAND_DP / 2f,
                ),
            )
            RulerKind.Protractor -> {
                val distance = hypot(local.x, local.y)
                // Dead centre has no direction to project along, and neither does anything below
                // the flat edge: both take the nearer end of the arc.
                if (local.y > 0f || distance == 0f) {
                    toPage(InkPoint(if (local.x < 0f) -reach else reach, 0f))
                } else {
                    toPage(InkPoint(local.x / distance * reach, local.y / distance * reach))
                }
            }
        }
    }

    /**
     * Where the degree dial sits, in the ruler's own frame.
     *
     * Dead centre on the straightedge, which is where the reference plate puts it. The semicircle's
     * centre is the middle of its *flat edge*, so a dial there would straddle the edge and sit on
     * top of the centre mark the protractor is lined up by — it goes up inside the disc instead,
     * clear of both that mark and the numbers around the arc.
     */
    fun dialCenter(): InkPoint = when (kind) {
        RulerKind.Straight -> InkPoint(0f, 0f)
        RulerKind.Protractor -> InkPoint(0f, -reach * PROTRACTOR_DIAL_INSET)
    }

    /**
     * Whether a finger landed on the dial — the degree readout.
     *
     * Its target is half again the circle that is drawn, because the drawn size comes from the
     * reference and the reference was not drawn for fingers.
     */
    fun grabsDial(point: InkPoint): Boolean {
        val local = toLocal(point)
        val dial = dialCenter()
        return hypot(local.x - dial.x, local.y - dial.y) <= DIAL_RADIUS_DP * DIAL_TOUCH_SLACK
    }

    fun movedBy(dx: Float, dy: Float): Ruler = copy(centerX = centerX + dx, centerY = centerY + dy)

    /** Turned by [radians] about its own centre — what twisting two fingers on it means. */
    fun turnedBy(radians: Float): Ruler = copy(angleRadians = angleRadians + radians)

    /**
     * The angle the dial reads: 0–359 degrees, measured from the ruler lying flat.
     *
     * **Counter-clockwise positive**, which is the negative of the stored angle. The store is in
     * screen coordinates, where `+y` points down and a positive rotation therefore swings the right
     * end *down*; a protractor does not read that way, and a ruler tipped down to the right is 315°
     * and not 45°. Getting this backwards is invisible until someone reads the dial.
     *
     * Normalised rather than accumulated, so a ruler twisted twice round says 20° rather than 740°.
     */
    fun degrees(): Int {
        val raw = (-Math.toDegrees(angleRadians.toDouble())).toInt() % 360
        return if (raw < 0) raw + 360 else raw
    }

    /**
     * The next eighth turn — what tapping the dial does.
     *
     * *Next multiple of 45°*, not plus 45°, and the difference only shows after a free twist: from a
     * hand-turned 13° a tap gives a clean 45° rather than a scarcely better 58°. From any of the
     * eight positions it is exactly a 45° step, which is what it says on the tin.
     */
    fun turnedToNextEighth(): Ruler {
        val step = Math.PI.toFloat() / 4f
        // *Down* the stored angle, because the dial counts the other way: a tap has to read
        // 0 → 45 → 90, which in screen coordinates means turning anticlockwise.
        //
        // Floor of a nudged angle: the nudge is what makes an eighth advance to the next one
        // instead of rounding onto itself, and the floor is what makes a free angle tidy up rather
        // than jump two steps.
        val next = kotlin.math.floor((angleRadians - step * EIGHTH_EPSILON) / step)
        return copy(angleRadians = next * step)
    }

    companion object {
        /** Radius of the degree dial, a third of the band across — the proportion in the reference. */
        const val DIAL_RADIUS_DP = 18.5f

        /** How much bigger the dial's touch target is than its drawing. */
        const val DIAL_TOUCH_SLACK = 1.6f

        /** How far up inside the half-disc the semicircle's dial sits, as a fraction of its radius. */
        const val PROTRACTOR_DIAL_INSET = 0.42f

        /** Keeps a ruler already sitting on an eighth from being rounded onto itself. */
        private const val EIGHTH_EPSILON = 0.001f

        /**
         * How wide the straight ruler's body is, in page dp — 0.7 of an inch.
         *
         * Ten to one against the default span, which is roughly a real ruler's proportion, and wide
         * enough to carry a graduated scale in from both edges with the numerals between them.
         */
        const val BAND_DP = 112f

        /** How far past the drawing edge a stroke still counts as ruled — about ⅙ of an inch. */
        const val SNAP_TOLERANCE_DP = 28f
    }
}

/** Where a ruler is lying, with no opinion about which ruler it is — RD2. */
data class RulerPlacement(val centerX: Float, val centerY: Float, val angleRadians: Float)
