package com.vivenotes.ink

import androidx.ink.geometry.AffineTransform
import androidx.ink.strokes.ExperimentalInkEraserApi
import androidx.ink.strokes.Stroke

/**
 * A finished stroke together with the row it came from.
 *
 * [Stroke] carries no identity of its own — it is brush plus inputs plus a derived mesh — so erasing
 * and syncing need the row id alongside it. Kept as a pair rather than pushed into the model, since
 * `Stroke` holds a native mesh and has no business in a type that must stay portable.
 */
data class PageStroke(val id: String, val stroke: Stroke)

/** The strokes that existed at erase time and actually overlap this mask. */
internal fun List<PageStroke>.targetsFor(mask: Stroke): List<String> =
    filter { it.stroke.shape.computeCoverageIsGreaterThan(mask.shape, 0f) }.map(PageStroke::id)

/** Replays one persisted normal-eraser operation without changing stroke identity or inputs. */
@OptIn(ExperimentalInkEraserApi::class)
internal fun List<PageStroke>.subtract(mask: Stroke, targetIds: Collection<String>): List<PageStroke> {
    val targets = targetIds.toSet()
    if (targets.isEmpty()) return this
    return map { pageStroke ->
        if (pageStroke.id !in targets) {
            pageStroke
        } else {
            pageStroke.copy(
                stroke = pageStroke.stroke.subtract(
                    maskShape = mask.shape,
                    maskToWorldTransform = AffineTransform.IDENTITY,
                    strokeToWorldTransform = AffineTransform.IDENTITY,
                ),
            )
        }
    }
}
