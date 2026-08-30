package com.vivenotes.ink

import com.vivenotes.model.Outline
import com.vivenotes.model.ink.ShapeEnd
import com.vivenotes.model.ink.aimEnd
import com.vivenotes.model.ink.ends
import com.vivenotes.model.ink.withEnd

/**
 * [withEnd], with the aimed end held inside the page — `memory/inkPlan.md` §5.4 SD12.
 *
 * **Because the snap turns the line after any clamp on the finger has run.** Clamping where the
 * finger is, which is all the layers can do on their own, stops the *request* leaving the page and
 * not the result: an end aimed onto an eighth-turn is the finger's point rotated by up to
 * [END_SNAP_TOLERANCE][com.vivenotes.model.ink.END_SNAP_TOLERANCE] about the far end, and that
 * rotation can carry it past the origin corner even from a finger held against it. A handle out
 * there is drawn outside the page and cannot be grabbed, which is exactly what [PageBounds] exists
 * to prevent.
 *
 * **Held by shortening, not by clamping.** Moving the point back onto the page would take it off the
 * ray the snap just put it on, which is the one thing the gesture promised — so the line keeps its
 * angle and gives up length instead, stopping with its end on the wall. Re-aiming the shortened
 * point is safe: it lies on the same ray, so it snaps to the same eighth-turn and the arithmetic
 * lands on the same exact unit vector.
 *
 * In `ink/` rather than in the model because the wall is [PageBounds]', and in one place rather than
 * three because both previews and the commit have to agree about where the end went — the layer's,
 * the lasso's, and `NotesViewModel.moveShapeEnd`'s.
 */
fun Outline.Shape.withEndOnPage(end: ShapeEnd, x: Float, y: Float): Outline.Shape {
    val (aimedX, aimedY) = aimEnd(end, x, y)
    val correction = PageBounds.correctionFor(aimedX, aimedY)
    if (correction.x == 0f && correction.y == 0f) return withEnd(end, aimedX, aimedY)

    // The end that is staying put, which is on the page: the ray runs from there to the aimed point,
    // and the wall is somewhere along it.
    val fixed = ends().firstOrNull { it.atEnd != end.atEnd } ?: return withEnd(end, aimedX, aimedY)
    val dx = aimedX - fixed.x
    val dy = aimedY - fixed.y
    var reach = 1f
    if (dx < 0f) reach = minOf(reach, (fixed.x - PageBounds.MIN_X) / -dx)
    if (dy < 0f) reach = minOf(reach, (fixed.y - PageBounds.MIN_Y) / -dy)

    return withEnd(end, fixed.x + dx * reach.coerceIn(0f, 1f), fixed.y + dy * reach.coerceIn(0f, 1f))
}
