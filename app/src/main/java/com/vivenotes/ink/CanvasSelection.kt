package com.vivenotes.ink

import com.vivenotes.model.Outline

/**
 * What is selected on the page, across every kind of object on it — `docs/plan.md` AD7.
 *
 * AD7's first consequence, made a type: *"selection is a page-level concept, not a per-layer one. A
 * lasso that returns only ink is a lasso that will need rewriting the first time a shape is inside
 * it."* Before this, ink's selection lived in `LassoGesture` and a shape's was a bare id on
 * `NotesUiState`, so the two could not describe one loop drawn around both — and each new object kind
 * would have arrived with a third.
 *
 * **Two id sets rather than a set of sealed object references.** Everything downstream is already
 * *these strokes* plus *these shapes* — different repositories, different transforms, ink persisting
 * a move for replay where a shape simply translates its segments — so a sealed set would be unpacked
 * into these two at every use site to say the same thing.
 *
 * [bounds] and [path] are in **page units**, the space ink is stored in and the space an
 * `Outline.Shape`'s coordinates are already in, so no kind has to convert to be selected.
 *
 * The ink half is handed to the existing ink operations unchanged, through [inkHalf] — the history,
 * repository and replay paths know [InkLassoSelection] and have no reason to learn this.
 */
data class CanvasSelection(
    /** The loop that made the selection. Empty when a tap made it: there was no loop. */
    val path: List<InkPoint> = emptyList(),
    val inkIds: Set<String> = emptySet(),
    val shapeIds: Set<String> = emptySet(),
    /** Live ink projections, including pieces that share a row id after a partial erase. */
    val projections: Set<InkProjectionKey> = emptySet(),
    val bounds: InkBounds,
) {
    val isEmpty: Boolean get() = inkIds.isEmpty() && shapeIds.isEmpty()

    /** True when only one kind is held, which is what decides the per-kind half of the toolkit. */
    val isInkOnly: Boolean get() = shapeIds.isEmpty() && inkIds.isNotEmpty()
    val isShapeOnly: Boolean get() = inkIds.isEmpty() && shapeIds.isNotEmpty()

    fun holdsShape(shapeId: String): Boolean = shapeId in shapeIds

    /** The ink half, in the shape the ink move/resize/replay path already takes. */
    fun inkHalf(): InkLassoSelection? = if (inkIds.isEmpty()) {
        null
    } else {
        InkLassoSelection(path = path, targetIds = inkIds, projections = projections, bounds = bounds)
    }

    fun translated(dx: Float, dy: Float): CanvasSelection = copy(
        path = path.map { InkPoint(it.x + dx, it.y + dy) },
        bounds = bounds.translated(dx, dy),
    )

    fun scaled(anchor: InkPoint, scaleX: Float, scaleY: Float): CanvasSelection = copy(
        path = path.map {
            InkPoint(
                anchor.x + (it.x - anchor.x) * scaleX,
                anchor.y + (it.y - anchor.y) * scaleY,
            )
        },
        bounds = bounds.scaled(anchor, scaleX, scaleY),
    )

    /**
     * Re-reads the selection against the page it describes, dropping what is gone and re-measuring
     * what moved.
     *
     * Returns null when nothing it named survives, which is what makes a delete or an undo dismiss the
     * selection rather than leave a rectangle floating over nothing. Ink expands through `groupId`,
     * because touching any member of a group selects all of it (`selectWithLasso`); a shape is already
     * one object and has nothing to expand into.
     */
    fun reconcile(strokes: List<PageStroke>, shapes: List<Outline.Shape>): CanvasSelection? {
        val liveShapes = shapes.filter { it.id in shapeIds }
        val directInk = strokes.filter { it.id in inkIds }
        if (liveShapes.isEmpty() && directInk.isEmpty()) return null

        val groups = directInk.mapNotNull(PageStroke::groupId).toSet()
        val expandedInk = strokes.filter { it.id in inkIds || it.groupId != null && it.groupId in groups }
        val measured = expandedInk.mapNotNull(PageStroke::pageBounds) + liveShapes.map(Outline.Shape::pageBounds)
        val union = measured.unionBounds() ?: return null

        return copy(
            inkIds = expandedInk.map(PageStroke::id).toSet(),
            shapeIds = liveShapes.map(Outline.Shape::id).toSet(),
            projections = expandedInk.map(PageStroke::projectionKey).toSet(),
            bounds = union,
        )
    }

    companion object {
        /** One tapped shape. The whole of a tap selection: no loop, no ink. */
        fun ofShape(shape: Outline.Shape): CanvasSelection = CanvasSelection(
            shapeIds = setOf(shape.id),
            bounds = shape.pageBounds(),
        )
    }
}

/**
 * The shared prime object clipboard's contents — `docs/diagram.md`.
 *
 * One clipboard holding every kind, rather than one per kind, so that a loop drawn round a stroke and
 * a shape copies both and pastes both. Shallow by design: a native `Stroke` is immutable and an
 * `Outline.Shape` is a data class, so nothing here can be mutated behind the clipboard's back.
 */
data class CanvasClipboard(
    val strokes: List<PageStroke> = emptyList(),
    val shapes: List<Outline.Shape> = emptyList(),
) {
    val isEmpty: Boolean get() = strokes.isEmpty() && shapes.isEmpty()
}

/**
 * One loop, one selection, however many kinds of thing are inside it — AD7's first row.
 *
 * The ink half is the existing [selectWithLasso] on strokes, untouched. The shape half applies the
 * *same* rule ink uses ([isInsideLasso]): every point of the object must be in or near the loop, so a
 * shape that is only half circled is left alone exactly as a half-circled stroke is. Anything else and
 * the lasso would feel like two different tools depending on what was under it.
 */
internal fun selectWithLasso(
    strokes: List<PageStroke>,
    shapes: List<Outline.Shape>,
    path: List<InkPoint>,
    edgeTolerance: Float = DEFAULT_LASSO_EDGE_TOLERANCE,
): CanvasSelection? {
    if (path.size < 3) return null
    val ink = strokes.selectWithLasso(path, edgeTolerance)
    val caughtShapes = shapes.filter { it.isInsideLasso(path, edgeTolerance) }
    if (ink == null && caughtShapes.isEmpty()) return null

    val measured = (ink?.let { listOf(it.bounds) } ?: emptyList()) + caughtShapes.map(Outline.Shape::pageBounds)
    val union = measured.unionBounds() ?: return null
    return CanvasSelection(
        path = path,
        inkIds = ink?.targetIds.orEmpty(),
        shapeIds = caughtShapes.map(Outline.Shape::id).toSet(),
        projections = ink?.projections.orEmpty(),
        bounds = union,
    )
}

/** Every vertex of every segment inside the loop, the rule a stroke is held to. */
private fun Outline.Shape.isInsideLasso(polygon: List<InkPoint>, edgeTolerance: Float): Boolean {
    if (segments.isEmpty()) return false
    segments.forEach { segment ->
        val points = segment.polyline()
        for (index in points.indices step 2) {
            val point = InkPoint(points[index], points[index + 1])
            if (!pointInOrNearPolygon(point, polygon, edgeTolerance)) return false
        }
    }
    return true
}

/** A shape's stored bounds, in the page units the selection works in. */
internal fun Outline.Shape.pageBounds(): InkBounds =
    InkBounds(left = x, top = y, right = x + width, bottom = y + height)

internal fun List<InkBounds>.unionBounds(): InkBounds? {
    val first = firstOrNull() ?: return null
    return drop(1).fold(first) { result, next ->
        InkBounds(
            left = minOf(result.left, next.left),
            top = minOf(result.top, next.top),
            right = maxOf(result.right, next.right),
            bottom = maxOf(result.bottom, next.bottom),
        )
    }
}
