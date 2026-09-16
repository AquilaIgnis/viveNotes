package com.vivenotes.ink

/**
 * The one edge an infinite canvas still has: its origin corner.
 *
 * The page grows right and downward without limit, but it does not grow backwards. Page coordinates
 * start at (0, 0) and both scroll states start there too, so anything placed at a negative
 * coordinate can never be scrolled to, selected or dragged back — while still being saved, loaded
 * and counted for ever after.
 *
 * That had happened twice on real pages by the time this existed: ink dragged off the top-left
 * corner by a lasso, and strokes drawn by a pen that left the window mid-stroke. So the rule is
 * enforced in three places, each for a different reason:
 *
 * 1. At the gesture, so the preview matches what will be committed. A drag clamped only on the lift
 *    follows the finger past the wall and then snaps back.
 * 2. At the draw seam, because a stroke's points are what the pen produced and there is no honest
 *    way to fix them afterwards — the part that was on the page would have to move too.
 * 3. At each kind's document funnel, which makes it an invariant rather than a habit.
 *
 * Ink is the exception to the third and gets [clampTranslation] on replay instead — see
 * `replayMove`. Every stored ink move passes through replay on load, so that is ink's one door.
 *
 * Everything here is in page units (dp).
 */
object PageBounds {

    /** The origin corner. Named rather than written as `0f`, so the rule reads as a rule. */
    const val MIN_X = 0f
    const val MIN_Y = 0f

    fun clampX(x: Float): Float = x.coerceAtLeast(MIN_X)

    fun clampY(y: Float): Float = y.coerceAtLeast(MIN_Y)

    fun clamp(point: InkPoint): InkPoint = InkPoint(clampX(point.x), clampY(point.y))

    /**
     * How far right and down a corner at ([left], [top]) has to move to be on the page. Zero on both
     * axes when it already is, which is the answer nearly every call gets.
     */
    fun correctionFor(left: Float, top: Float): InkPoint =
        InkPoint(maxOf(0f, MIN_X - left), maxOf(0f, MIN_Y - top))

    /**
     * As much of ([dx], [dy]) as keeps [bounds] on the page.
     *
     * Each axis is capped independently, so a drag towards the top left corner slides along whichever
     * edge it reaches first rather than stopping dead — which is what a window does when you push it
     * into the corner of a screen, and what the hand expects.
     *
     * A rectangle that is *already* off the page raises the floor above zero, so the same call that
     * limits a live drag also pulls old content back inside. That is deliberate: it is how a page
     * written by a build that had no such rule heals itself.
     */
    fun clampTranslation(bounds: InkBounds, dx: Float, dy: Float): InkPoint =
        clampTranslation(bounds.left, bounds.top, dx, dy)

    /**
     * The same, for a caller that knows only the near corner.
     *
     * Which is all a translation limit ever reads: a rectangle's far edges cannot reach a wall its
     * near ones are being held off. Offered so that a kind whose height the document only
     * approximates — a table, a text container — does not have to invent a bottom edge in order to
     * ask a question that has nothing to do with one.
     */
    fun clampTranslation(left: Float, top: Float, dx: Float, dy: Float): InkPoint = InkPoint(
        maxOf(dx, MIN_X - left),
        maxOf(dy, MIN_Y - top),
    )

    /**
     * As much of ([scaleX], [scaleY]) as keeps [bounds] on the page when scaled about [anchor].
     *
     * Only a corner drag that pulls the far side of the rectangle towards the origin can offend, so
     * the limit exists only on the axes where the anchor lies to the right of, or below, the edge
     * being moved: `left' = anchor + (left - anchor) × scale ≥ 0` gives
     * `scale ≤ anchor / (anchor - left)`.
     *
     * Only ever limits growth, and never shrinks anything. An edge already off the page is left
     * alone: no positive scale about an anchor on the near side can bring it back, so the honest
     * limit there is zero — and zero collapses the object into its anchor. Bringing content back is
     * [clampTranslation]'s job.
     *
     * With that case excluded the ratio cannot fall below 1, so this can only stop a corner drag
     * short of the wall.
     */
    fun clampScale(
        bounds: InkBounds,
        anchor: InkPoint,
        scaleX: Float,
        scaleY: Float,
    ): InkPoint = InkPoint(
        scaleX.coerceAtMost(limitFor(anchor.x, bounds.left, MIN_X)),
        scaleY.coerceAtMost(limitFor(anchor.y, bounds.top, MIN_Y)),
    )

    private fun limitFor(anchor: Float, edge: Float, wall: Float): Float {
        // Already off the page: no scale about this anchor brings it back, and the arithmetic
        // answer — zero — would collapse the object into the anchor. Left to the translation.
        if (edge < wall) return Float.MAX_VALUE
        val reach = anchor - edge
        // The anchor is on the wall side of the edge, so scaling pushes that edge further out, not
        // in. Nothing to limit.
        if (reach <= 0f) return Float.MAX_VALUE
        return (anchor - wall) / reach
    }
}
