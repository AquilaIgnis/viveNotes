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

/**
 * Removes only the disconnected regions touched by an Object-mode eraser mask.
 *
 * A partially erased stroke still has one database id and one input batch, but its mesh may have
 * several disconnected regions. Splitting at zero tolerance makes those regions independent; each
 * survivor becomes its own [PageStroke] projection while retaining the shared storage id needed to
 * replay later operations.
 */
@OptIn(ExperimentalInkEraserApi::class)
internal fun List<PageStroke>.eraseObjects(
    mask: Stroke,
    targetIds: Collection<String>,
): List<PageStroke> {
    val targets = targetIds.toSet()
    if (targets.isEmpty()) return this
    return flatMap { pageStroke ->
        if (pageStroke.id !in targets) {
            listOf(pageStroke)
        } else {
            pageStroke.stroke
                .split(strokeToWorldTransform = AffineTransform.IDENTITY, tolerance = 0f)
                .filterNot { component ->
                    component.shape.computeCoverageIsGreaterThan(mask.shape, 0f)
                }
                .map { component -> pageStroke.copy(stroke = component) }
        }
    }
}
