package com.vivenotes.ink

import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.geometry.ImmutableTriangle
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.MutableVec
import androidx.ink.geometry.PartitionedMesh
import androidx.ink.strokes.ExperimentalInkEraserApi
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.ink.strokes.createClosedShape
import com.vivenotes.data.automaticColorOr
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot

/**
 * A finished stroke together with the row it came from.
 *
 * [Stroke] carries no identity of its own — it is brush plus inputs plus a derived mesh — so erasing
 * and syncing need the row id alongside it. Kept as a pair rather than pushed into the model, since
 * `Stroke` holds a native mesh and has no business in a type that must stay portable.
 */
data class PageStroke(
    val id: String,
    val stroke: Stroke,
    /** Translation from this projection's stroke coordinates into page coordinates. */
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    /** Axis-aligned page transform. Resize operations compose into these values. */
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    /** Stable codec metadata needed when a selected stroke is duplicated. */
    val brushFamily: String = "pressure-pen",
    val brushVersion: Int = InkCodec.BRUSH_VERSION,
    val stabilization: Int = 0,
    /**
     * Whether this stroke's colour was the automatic one — carried alongside the mesh so the draw
     * pass can re-resolve it, and so a duplicate keeps the intent rather than the answer.
     *
     * The [stroke]'s own brush still holds a concrete colour, because a `Brush` has nowhere to put
     * "it depends"; this is what says whether that colour is a decision or a derivation. See
     * [com.vivenotes.data.automaticColorOr].
     */
    val colorFollowsTheme: Boolean? = null,
    val groupId: String? = null,
    /**
     * What makes this projection *this* projection, for as long as it is on the page.
     *
     * **This used to be `System.identityHashCode(stroke)`, and that was a trap.** Selection, the
     * lasso's move preview and recognition are all keyed on [projectionKey], so deriving it from the
     * allocation meant any code that rebuilt a `Stroke` silently renumbered every selection pointing
     * at it. Nothing failed loudly: the selection simply stopped matching, and
     * `InkSelectionRenderer.renderInkSelection` — which filters its input by exactly this key —
     * rendered an empty square and handed it to the formula model, which read the blank page it was
     * given. Recolouring a held selection did it, and so did the first version of Switch Background's
     * ink fix.
     *
     * Carrying the number instead means a projection keeps its identity through anything `copy` can
     * do to it — a rebound brush, a new colour, a move, a regroup — because those all produce the
     * same projection wearing different paint. Only a genuinely *new* projection mints a new number,
     * which is the constructor's default and, explicitly, the pieces an erase splits a stroke into.
     */
    val projection: Int = newProjection(),
) {
    /**
     * This projection's page-space rectangle, or null once a cut has left it with no geometry.
     *
     * Computed once per instance rather than per call. `computeBoundingBox` is a JNI hop into the
     * mesh, and the two callers that matter ask for it on *every* stroke: the draw pass, which culls
     * against the window on every frame, and lasso replay, which measures what an operation is about
     * to move. At page scale that was tens of thousands of crossings per frame.
     *
     * Outside the constructor on purpose — it is derived, so it stays out of `equals`, `hashCode` and
     * `copy`, and a `copy` that changes the offset, the scale or the stroke gets a fresh one.
     */
    internal val pageBounds: InkBounds? by lazy {
        stroke.shape.computeBoundingBox()?.let {
            InkBounds(
                left = it.xMin * scaleX + offsetX,
                top = it.yMin * scaleY + offsetY,
                right = it.xMax * scaleX + offsetX,
                bottom = it.yMax * scaleY + offsetY,
            )
        }
    }
}

/** One point in the page coordinate system, where persisted ink and lasso paths live. */
data class InkPoint(val x: Float, val y: Float)

/**
 * Identifies one live projection, including a disconnected piece that shares its stored row id.
 *
 * [projection] was `System.identityHashCode(stroke)` and is now a number the projection carries —
 * see [PageStroke.projection] for what that bought.
 */
data class InkProjectionKey(val strokeId: String, val projection: Int)

/** A completed lasso drag, ready to apply immediately and persist for replay. */
data class InkLassoMove(
    val path: List<InkPoint>,
    val targetIds: Set<String>,
    val projections: Set<InkProjectionKey>,
    val dx: Float,
    val dy: Float,
)

/** A completed corner-handle drag, scaling selected projections around the opposite corner. */
data class InkLassoResize(
    val path: List<InkPoint>,
    val targetIds: Set<String>,
    val projections: Set<InkProjectionKey>,
    val anchor: InkPoint,
    val scaleX: Float,
    val scaleY: Float,
)

/** Page-space rectangle used by the selection affordance and its move/resize hit targets. */
data class InkBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val center: InkPoint get() = InkPoint((left + right) / 2f, (top + bottom) / 2f)

    fun contains(point: InkPoint): Boolean =
        point.x in left..right && point.y in top..bottom

    fun translated(dx: Float, dy: Float): InkBounds =
        InkBounds(left + dx, top + dy, right + dx, bottom + dy)

    fun scaled(anchor: InkPoint, scaleX: Float, scaleY: Float): InkBounds {
        val x1 = anchor.x + (left - anchor.x) * scaleX
        val x2 = anchor.x + (right - anchor.x) * scaleX
        val y1 = anchor.y + (top - anchor.y) * scaleY
        val y2 = anchor.y + (bottom - anchor.y) * scaleY
        return InkBounds(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
    }
}

data class InkLassoSelection(
    val path: List<InkPoint>,
    val targetIds: Set<String>,
    val projections: Set<InkProjectionKey>,
    val bounds: InkBounds,
)

/** Mints a projection number. Process-local and never stored — a projection lives on a page, not in a file. */
internal fun newProjection(): Int = projectionCounter.incrementAndGet()

private val projectionCounter = AtomicInteger()

internal val PageStroke.projectionKey: InkProjectionKey
    get() = InkProjectionKey(id, projection)

/** What makes two projections, decoded on either side of a rebuild, the same projection. */
private data class ProjectionIdentity(
    val strokeId: String,
    /** Which of that row's projections this is, in draw order — a partial erase leaves several. */
    val ordinal: Int,
    val bounds: InkBounds?,
)

/**
 * The same page, wearing the projection numbers [previous] gave the projections it still holds.
 *
 * Rebuilding a page from Room mints a fresh [PageStroke.projection] for every stroke on it, which is
 * right when the page is being opened and wrong when it is already on screen: a lasso selection
 * lives in `EditorPane` and is a set of [InkProjectionKey]s, so a silent renumbering is a selection
 * that stops matching anything — the exact failure [PageStroke.projection] exists to prevent, and
 * one that shows up as recognition reading a blank square rather than as anything failing. Absorbing
 * a stroke another device drew must not cost the user the selection they are holding.
 *
 * A projection is the same one when it comes from the same row, is the same one *of* that row, and
 * occupies the same page rectangle. The last clause is what keeps this conservative: an operation
 * pulled from another device that cut, moved or resized a stroke changes its bounds, so it mints a
 * new number exactly as a local erase would, and only ink nobody touched keeps its identity.
 *
 * [PageStroke.pageBounds] is computed once per instance and the draw pass asks every stroke for it
 * on the next frame regardless, so this reads what was going to be read anyway.
 */
internal fun List<PageStroke>.keepingProjectionsOf(previous: List<PageStroke>): List<PageStroke> {
    if (isEmpty() || previous.isEmpty()) return this
    val ordinals = HashMap<String, Int>()
    val held = HashMap<ProjectionIdentity, Int>(previous.size)
    previous.forEach { stroke ->
        val ordinal = ordinals.getOrDefault(stroke.id, 0)
        ordinals[stroke.id] = ordinal + 1
        held[ProjectionIdentity(stroke.id, ordinal, stroke.pageBounds)] = stroke.projection
    }
    ordinals.clear()
    return map { stroke ->
        val ordinal = ordinals.getOrDefault(stroke.id, 0)
        ordinals[stroke.id] = ordinal + 1
        val kept = held[ProjectionIdentity(stroke.id, ordinal, stroke.pageBounds)]
        if (kept == null || kept == stroke.projection) stroke else stroke.copy(projection = kept)
    }
}

internal fun PageStroke.strokeToPageTransform(): AffineTransform =
    ImmutableAffineTransform(scaleX, 0f, offsetX, 0f, scaleY, offsetY)

private fun PageStroke.pageToStrokeTransform(): AffineTransform =
    strokeToPageTransform().computeInverse()

/**
 * Whether this stroke still has any geometry.
 *
 * [Stroke.subtract] can cut a mesh away entirely, and Ink's shape comparisons do not tolerate the
 * result: `computeCoverageIsGreaterThan` reaches a native `CHECK failed: !meshes_.empty()` and
 * **aborts the process** rather than returning false. So an empty mesh has to be caught here, in
 * Kotlin, before it is ever handed to one.
 *
 * `computeBoundingBox` is the safe test — it returns null for an empty mesh, which is what
 * [pageBounds] already relies on.
 */
internal val Stroke.hasGeometry: Boolean
    get() = shape.computeBoundingBox() != null

/**
 * Whether this stroke's page-space geometry is touched at all by an eraser mask.
 *
 * Guarded on both sides because this runs over *every* stroke on the page: one empty mesh anywhere
 * in the list took the whole app down on the next eraser touch, wherever that touch landed.
 */
internal fun PageStroke.touches(mask: Stroke): Boolean {
    if (!stroke.hasGeometry || !mask.hasGeometry) return false
    return stroke.shape.computeCoverageIsGreaterThan(
        other = mask.shape,
        coverageThreshold = 0f,
        otherShapeToThis = pageToStrokeTransform(),
    )
}

/** The strokes that existed at erase time and actually overlap this mask. */
internal fun List<PageStroke>.targetsFor(mask: Stroke): List<String> =
    filter { it.touches(mask) }.map(PageStroke::id).distinct()

/**
 * Whether this stroke can only be drawn from the outlines of its mesh.
 *
 * `SelfOverlap.DISCARD` forces the path renderer — the mesh renderer refuses the mode, and that is
 * the documented trade for a highlighter that does not double its opacity where it crosses itself.
 * The path renderer draws from outlines, and draws *nothing* for a mesh that has none.
 *
 * That matters because [Stroke.split] returns pieces with no outlines, while [Stroke.subtract]
 * keeps them. Splitting one of these strokes therefore does not cut it — it erases it from the
 * screen entirely, while leaving its geometry intact and its row in the database.
 */
private val Stroke.isDrawnFromOutlines: Boolean
    get() = brush.family.coats.any { coat ->
        coat.paintPreferences.all { it.selfOverlap == SelfOverlap.DISCARD }
    }

/**
 * Replays one persisted normal-eraser operation without changing stroke identity or inputs.
 *
 * Splitting the cut into its disconnected regions is what gives each piece its own projection, so
 * that a later Object erase or lasso can take one of them. A stroke that is drawn from its outlines
 * cannot afford that (see [isDrawnFromOutlines]) and stays a single projection: one highlighter is
 * one object however many times it has been cut.
 */
@OptIn(ExperimentalInkEraserApi::class)
internal fun List<PageStroke>.subtract(mask: Stroke, targetIds: Collection<String>): List<PageStroke> {
    val targets = targetIds.toSet()
    if (targets.isEmpty() || !mask.hasGeometry) return this
    return flatMap { pageStroke ->
        if (pageStroke.id !in targets) {
            listOf(pageStroke)
        } else {
            val cut = pageStroke.stroke.subtract(
                maskShape = mask.shape,
                maskToWorldTransform = AffineTransform.IDENTITY,
                strokeToWorldTransform = pageStroke.strokeToPageTransform(),
            )
            if (cut.isDrawnFromOutlines) {
                // Erased down to nothing is erased. The other branch gets this for free — an empty
                // mesh splits into no components — but this one would keep a stroke with no
                // geometry, invisible on the page and fatal to the next comparison it meets.
                // A fresh projection, not the one that went in: what comes out has different
                // geometry, and anything still holding the old key is holding a shape that no
                // longer exists. See [PageStroke.projection].
                if (cut.hasGeometry) {
                    listOf(pageStroke.copy(stroke = cut, projection = newProjection()))
                } else {
                    emptyList()
                }
            } else {
                // And one each, so the pieces are told apart — the case the key exists for.
                cut.split(strokeToWorldTransform = pageStroke.strokeToPageTransform(), tolerance = 0f)
                    .map { component ->
                        pageStroke.copy(stroke = component, projection = newProjection())
                    }
            }
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
 *
 * A stroke drawn from its outlines is not split — splitting would blank it (see
 * [isDrawnFromOutlines]) — so it is the object, whole, and touching it removes all of it.
 */
@OptIn(ExperimentalInkEraserApi::class)
internal fun List<PageStroke>.eraseObjects(
    mask: Stroke,
    targetIds: Collection<String>,
): List<PageStroke> {
    val targets = targetIds.toSet()
    if (targets.isEmpty() || !mask.hasGeometry) return this
    return flatMap { pageStroke ->
        if (pageStroke.id !in targets) {
            listOf(pageStroke)
        } else if (pageStroke.stroke.isDrawnFromOutlines) {
            if (pageStroke.touches(mask)) emptyList() else listOf(pageStroke)
        } else {
            pageStroke.stroke
                .split(strokeToWorldTransform = pageStroke.strokeToPageTransform(), tolerance = 0f)
                // A component with no geometry is nothing to keep, and nothing safe to compare.
                .filter { it.hasGeometry }
                .filterNot { component ->
                    component.shape.computeCoverageIsGreaterThan(
                        other = mask.shape,
                        coverageThreshold = 0f,
                        otherShapeToThis = pageStroke.pageToStrokeTransform(),
                    )
                }
                // One projection per surviving region, as in [subtract].
                .map { component -> pageStroke.copy(stroke = component, projection = newProjection()) }
        }
    }
}

/** One stored Object-mode erase standing for "remove this piece", and the page it leaves behind. */
internal data class InkPieceErase(
    val mask: Stroke,
    val rowId: String,
    val after: List<PageStroke>,
)

/** What deleting a set of projections costs: some erase operations, and the page they produce. */
internal data class InkProjectionDelete(
    val erases: List<InkPieceErase>,
    /** Rows nothing of which survives the deletion — tombstoned by the caller, not erased. */
    val wholeRows: List<String>,
    val after: List<PageStroke>,
)

/**
 * How to delete exactly [held], given what is live on the page.
 *
 * **A piece of ink has no storable form of its own, so removing one can only be another erase.**
 * `ink_strokes` holds a brush and an input batch and the mesh is rebuilt from them; a projection is
 * "row X, minus its replayed erases, the component containing this region" and there is nothing about
 * it to write down. A row every piece of which is going can therefore be tombstoned — that is an
 * ordinary delete and stays one — while a row that keeps a piece has to be told what to lose in the
 * only language the log has. `memory/lassoProjectionPlan.md` §5.
 *
 * The language it has is [eraseObjects], which removes every disconnected component its mask
 * *touches*. Touching is the whole requirement — no coverage is measured — so the mask is a **dot on
 * the piece**, placed on a vertex of the piece's own mesh ([pointOnInk]). Deleting a piece is then
 * recorded as the eraser having been tapped on it, which the log, page-open replay, the undo ring and
 * the sync client all already understand, and which needed no new operation kind and no migration.
 *
 * **Every dot is proved before it is offered**, by [eraserDotAvoiding]: it must take the piece and
 * leave that row's other pieces alone. A stored operation proved against the state it was made in
 * replays identically, because replay reproduces that state — and this is the one place the two can
 * be compared, so it is the place to compare them.
 *
 * One erase per piece rather than one for the gesture: a mask is a stroke, a stroke is one continuous
 * path, and a path visiting two pieces runs through the gap the eraser cut between them. The caller
 * folds them into a single undo.
 */
internal fun List<PageStroke>.planProjectionDelete(held: Set<InkProjectionKey>): InkProjectionDelete {
    val byRow = groupBy(PageStroke::id)
    val wholeRows = byRow.filterValues { pieces -> pieces.all { it.projectionKey in held } }.keys.toList()
    var current = this
    val erases = mutableListOf<InkPieceErase>()
    byRow.forEach { (rowId, pieces) ->
        val doomed = pieces.filter { it.projectionKey in held }
        // Nothing of this row is held, or all of it is: the tombstone above says it more cheaply.
        if (doomed.isEmpty() || doomed.size == pieces.size) return@forEach
        val survivors = pieces.filterNot { it.projectionKey in held }
        doomed.forEach { piece ->
            // Built against the *original* pieces, applied to the running page: `eraseObjects` mints
            // fresh projection numbers for the components it keeps, so a key looked up in `current`
            // after the first cut would name nothing. The mask is geometry and does not care.
            val mask = piece.eraserDotAvoiding(survivors) ?: return@forEach
            current = current.eraseObjects(mask, listOf(rowId))
            erases += InkPieceErase(mask, rowId, current)
        }
    }
    val after = if (wholeRows.isEmpty()) current else current.filterNot { it.id in wholeRows.toSet() }
    return InkProjectionDelete(erases, wholeRows, after)
}

/**
 * A round mask that takes this projection and none of [others], or null if no such mask was proved.
 *
 * Grown rather than guessed: the smallest dot is tried first, and a size is only accepted once the
 * mesh has confirmed both halves of the claim. Growing stops at the first size that overreaches,
 * because every larger one overreaches further — the eraser that cut these pieces apart was at least
 * `EraserSettings`' 4 dp across, so a dot this size sitting on one piece cannot reach the next.
 *
 * Returning null leaves the piece on the page. That is the safe direction: a delete that quietly did
 * nothing is a delete the user can repeat, where a mask that could not be proved is ink disappearing
 * that nobody circled.
 */
private fun PageStroke.eraserDotAvoiding(others: List<PageStroke>): Stroke? {
    val point = pointOnInk() ?: return null
    val inputs = MutableStrokeInputBatch()
        .apply { add(InputToolType.UNKNOWN, point.x, point.y, 0L) }
        .toImmutable()
    var size = DOT_MASK_MIN_DP
    while (size <= DOT_MASK_MAX_DP) {
        // Through the codec, because what replay applies is the mask as it comes back off disk and
        // not the one built here — `StrokeInputBatch.encode` is the library's own packing and a
        // point that survives it by a hair is a delete that lands on one device and not the next.
        val mask = InkCodec.reloadedEraseMask(inputs, size)
        if (mask != null && mask.hasGeometry && touches(mask)) {
            return if (others.none { it.touches(mask) }) mask else null
        }
        size *= 2f
    }
    return null
}

/**
 * A page-space point certainly on this projection's ink, or null once it has no mesh left.
 *
 * **Found by asking the mesh, not by reading it.** The direct answer — a vertex out of
 * `PartitionedMesh.renderGroupMeshes` — is `@RestrictTo(LIBRARY_GROUP)` and lint refuses it, rightly:
 * a restricted accessor can change or vanish between alphas, and this decides what an *erase* is
 * allowed to remove. `computeCoverageIsGreaterThan` against a probe triangle is public, is the same
 * question, and is already how [LassoShape.crosses] talks to a mesh with no outlines.
 *
 * The candidates are the stroke's **own input samples**, which is what makes one round of probes
 * enough rather than a search: `Stroke.split` gives each piece a new shape but *"retains the original
 * inputs"*, so every sample of the whole stroke is offered to every piece of it and each piece
 * answers for the stretch that is its. Samples run down the centre of the ink at drawing resolution,
 * so a piece with any length to it is hit almost at once.
 *
 * The box sweep behind it is for the piece too short to hold a sample — a sliver the eraser left
 * between two of them. It walks the piece's own rectangle, which is tight around a sliver, so the
 * expensive path is the one that has almost nothing to search.
 */
internal fun PageStroke.pointOnInk(): InkPoint? {
    val bounds = pageBounds ?: return null
    val inputs = stroke.inputs
    val sample = StrokeInput()
    repeat(inputs.size) { index ->
        inputs.populate(index, sample)
        val point = InkPoint(sample.x * scaleX + offsetX, sample.y * scaleY + offsetY)
        if (covers(point)) return point
    }
    val step = maxOf(PROBE_REACH, minOf(bounds.right - bounds.left, bounds.bottom - bounds.top) / 2f)
    var y = bounds.top
    while (y <= bounds.bottom) {
        var x = bounds.left
        while (x <= bounds.right) {
            val point = InkPoint(x, y)
            if (covers(point)) return point
            x += step
        }
        y += step
    }
    return null
}

/** Whether this projection's mesh has ink at a page-space point, asked as a probe with area. */
private fun PageStroke.covers(point: InkPoint): Boolean = stroke.shape.computeCoverageIsGreaterThan(
    triangle = ImmutableTriangle(
        ImmutableVec(point.x - PROBE_REACH, point.y - PROBE_REACH),
        ImmutableVec(point.x + PROBE_REACH, point.y - PROBE_REACH),
        ImmutableVec(point.x, point.y + PROBE_REACH),
    ),
    coverageThreshold = 0f,
    triangleToThis = pageToStrokeTransform(),
)

/**
 * How far a probe reaches around the point it is asking about, in page units.
 *
 * A triangle needs area to intersect anything, and this is the mesh's own tolerance
 * (`InkCodec.EPSILON`) — smaller asks a question finer than the geometry was built to answer, and
 * larger starts reaching over the gap the eraser cut.
 */
private const val PROBE_REACH = 0.25f

/**
 * The smallest dot a piece is deleted with, in page units.
 *
 * Small on purpose: it has only to intersect the component it sits on, and every page unit of it is
 * page units nearer the piece next door.
 */
private const val DOT_MASK_MIN_DP = 1f

/** Where growing stops. Past this a dot is no longer smaller than the cut that made the pieces. */
private const val DOT_MASK_MAX_DP = 4f

/**
 * Selects objects whose visible ink outline is enclosed by the free-form lasso.
 *
 * **A loop takes projections; only a group takes rows.** A partial erase leaves one row wearing
 * several disconnected projections, and this used to widen every hit back out to its row — so a loop
 * drawn round one half of a cut line selected both halves, and the rectangle spanned the gap between
 * them. That widening was written when a piece of erased ink could not be *reached* by the lasso at
 * all (`Stroke.split` returns pieces with no outlines and the exact test walked outlines), so for two
 * weeks it had nothing to widen; the moment [LassoShape.encloses] made pieces selectable it became
 * the behaviour the user sees. `memory/lassoProjectionPlan.md` §1.
 *
 * It was there for a reason that was real and is answered elsewhere: delete, colour and grouping are
 * properties of the stored row, so a selection narrower than a row could name geometry they would
 * overrun. Colour and `groupId` *are* the row and say so; delete is projection-scoped in
 * `NotesViewModel.deleteInkSelection`. Pre-widening the selection to protect three operations left
 * the other three — move, resize and recognition, all keyed on [projectionKey] — acting on ink the
 * hand never circled.
 *
 * It was also already inconsistent with replay: [replayMove] re-derives its targets geometrically,
 * so the widened set moved on screen and only the circled piece moved on the next page open. The
 * gesture and the replay now ask the same question.
 *
 * A group still expands, because a group is the user saying these rows are one object — the one
 * expansion that comes from an intent rather than from an implementation detail of the eraser.
 */
internal fun List<PageStroke>.selectWithLasso(
    path: List<InkPoint>,
    edgeTolerance: Float = DEFAULT_LASSO_EDGE_TOLERANCE,
): InkLassoSelection? {
    if (path.size < 3) return null
    val lasso = LassoShape(path, edgeTolerance)
    val hits = filter { stroke -> lasso.contains(stroke) }
    if (hits.isEmpty()) return null
    val hitGroups = hits.mapNotNull(PageStroke::groupId).toSet()
    val selected = if (hitGroups.isEmpty()) {
        hits
    } else {
        val hitKeys = hits.mapTo(HashSet(hits.size), PageStroke::projectionKey)
        filter { it.projectionKey in hitKeys || it.groupId != null && it.groupId in hitGroups }
    }
    val bounds = selected.mapNotNull(PageStroke::pageBounds).unionBounds() ?: return null
    return InkLassoSelection(
        path = path,
        targetIds = selected.map(PageStroke::id).toSet(),
        projections = selected.map(PageStroke::projectionKey).toSet(),
        bounds = bounds,
    )
}

/**
 * Rebinds selected immutable meshes to a brush carrying the requested colour.
 *
 * **Clears the automatic flag**, because picking a colour off the palette is exactly the act that
 * makes one deliberate — leaving it set would have the stroke go back to the canvas's ink the next
 * time the background was switched, silently discarding the choice just made. Explicitly `false`
 * rather than null: this stroke's intent is now known, and null means only "never recorded".
 */
internal fun List<PageStroke>.recolor(ids: Collection<String>, colorArgb: Int): List<PageStroke> {
    val targets = ids.toSet()
    return map { pageStroke ->
        if (pageStroke.id in targets) {
            pageStroke.copy(
                stroke = pageStroke.stroke.copy(
                    pageStroke.stroke.brush.copyWithColorIntArgb(colorArgb),
                ),
                colorFollowsTheme = false,
            )
        } else {
            pageStroke
        }
    }
}

/**
 * What to paint an automatic stroke with on this canvas, leaving the stroke itself alone.
 *
 * **A view, not an edit, and deliberately not a new list of [PageStroke]s.** The obvious shape for
 * this — map the list, rebinding automatic strokes to a themed brush — is wrong in a way that does
 * not show up on screen: [projectionKey] is `System.identityHashCode(stroke)`, so handing the page a
 * themed copy silently renumbers every projection. A lasso selection captured against those copies
 * then matches nothing in the list the view model holds, which is what
 * `InkSelectionRenderer.renderInkSelection` filters by — so recognition renders a blank square and
 * reads an empty page, and the lasso's own move preview stops finding the strokes it is moving.
 *
 * So identity stays with the stored stroke and only the paint is derived. The themed [Stroke] is
 * built once per stroke and cached by identity; `Stroke.copy(brush)` rebinds the existing immutable
 * mesh rather than rebuilding it, so this costs an object, not a re-tessellation.
 *
 * Scoped to one canvas colour — build a new painter when the canvas flips, which is what the call
 * site's `remember` key does.
 */
internal class CanvasInkPainter(private val canvasInkArgb: Int) {

    private val painted = IdentityHashMap<Stroke, Stroke>()

    fun paint(pageStroke: PageStroke): Stroke {
        val stroke = pageStroke.stroke
        val resolved = automaticColorOr(
            stored = stroke.brush.colorIntArgb,
            followsTheme = pageStroke.colorFollowsTheme,
            canvasInk = canvasInkArgb,
        )
        // Already the right colour — the common case on a page of deliberately coloured ink, and
        // worth not filling the cache for.
        if (resolved == stroke.brush.colorIntArgb) return stroke
        return painted.getOrPut(stroke) {
            stroke.copy(stroke.brush.copyWithColorIntArgb(resolved))
        }
    }
}

internal fun List<PageStroke>.regroup(groups: Map<String, String?>): List<PageStroke> = map { stroke ->
    if (stroke.id in groups) stroke.copy(groupId = groups[stroke.id]) else stroke
}

/**
 * Makes a self-contained translated copy, baking the live page transform into its input coordinates.
 *
 * A decoded [Stroke] is not guaranteed to satisfy [MutableStrokeInputBatch]'s stricter append
 * contract. AndroidX's storage format lossily delta-encodes elapsed time: two valid stationary
 * samples at 55 and 56 ms were observed decoding as two identical x-y-55 triplets. The immutable
 * stroke still renders, so that defect stays dormant until paste rebuilds its inputs here and the
 * mutable batch rejects the second triplet. A sufficiently small composed transform can collapse
 * positions in the same way. This immutable-to-mutable boundary therefore drops duplicate
 * x-y-time triplets after applying the transform; they carry no additional geometry.
 */
internal fun PageStroke.translatedCopy(dx: Float, dy: Float): Stroke {
    val moved = MutableStrokeInputBatch()
    val source = stroke.inputs
    val seen = HashSet<InputTripletKey>(source.size)
    repeat(source.size) { index ->
        val input = source[index]
        val x = input.x * scaleX + offsetX + dx
        val y = input.y * scaleY + offsetY + dy
        val key = InputTripletKey(x.inputKeyBits(), y.inputKeyBits(), input.elapsedTimeMillis)
        if (!seen.add(key)) return@repeat
        moved.add(
            type = input.toolType,
            x = x,
            y = y,
            elapsedTimeMillis = input.elapsedTimeMillis,
            strokeUnitLengthCm = input.strokeUnitLengthCm,
            pressure = input.pressure,
            tiltRadians = input.tiltRadians,
            orientationRadians = input.orientationRadians,
        )
    }
    moved.setNoiseSeed(source.getNoiseSeed())
    return Stroke(stroke.brush, moved.toImmutable())
}

private data class InputTripletKey(val xBits: Int, val yBits: Int, val elapsedTimeMillis: Long)

/** AndroidX Ink compares positions numerically, so canonicalise the two bit patterns for zero. */
private fun Float.inputKeyBits(): Int = if (this == 0f) 0 else toRawBits()

/** Applies the exact live projection set captured when the gesture began. */
internal fun List<PageStroke>.moveSelected(move: InkLassoMove): List<PageStroke> = map { stroke ->
    if (stroke.projectionKey in move.projections) {
        stroke.copy(offsetX = stroke.offsetX + move.dx, offsetY = stroke.offsetY + move.dy)
    } else {
        stroke
    }
}

/** Applies a world-space corner resize after each selected stroke's existing page transform. */
internal fun List<PageStroke>.resizeSelected(resize: InkLassoResize): List<PageStroke> = map { stroke ->
    if (stroke.projectionKey in resize.projections) {
        stroke.scaledAround(resize.anchor, resize.scaleX, resize.scaleY)
    } else {
        stroke
    }
}

private fun PageStroke.scaledAround(anchor: InkPoint, x: Float, y: Float): PageStroke = copy(
    scaleX = scaleX * x,
    scaleY = scaleY * y,
    offsetX = anchor.x + (offsetX - anchor.x) * x,
    offsetY = anchor.y + (offsetY - anchor.y) * y,
)

/**
 * Replays a persisted move against the projections that existed inside its original lasso.
 *
 * **This is where the page's origin corner is enforced for ink** — [PageBounds]. A shape, a table or
 * an equation is a position in the document and can be held to the rule where it is written down;
 * ink is a stroke plus every operation ever replayed over it, so the only place its final position
 * is known is here, at the end of the fold that produces it.
 *
 * For a move recorded by a build that has the rule, this changes nothing: the gesture had already
 * clamped the delta against a selection the ink was part of, so the same limit computed again is the
 * same limit. It fires only for the pages that were dragged off the corner before the rule existed,
 * and it brings the whole moved set back **together**, by the one delta it shares, rather than
 * shuffling each stroke to the wall on its own.
 */
internal fun List<PageStroke>.replayMove(
    path: List<InkPoint>,
    targetIds: Collection<String>,
    dx: Float,
    dy: Float,
): List<PageStroke> {
    val targets = targetIds.toSet()
    if (path.size < 3 || targets.isEmpty()) return this
    // Decided once and remembered: the lasso test walks every outline vertex of every stroke, and
    // the clamp below needs to know the answer before it can measure what is moving.
    val lasso = LassoShape(path)
    val selected = map { it.id in targets && lasso.contains(it) }
    val delta = movingBounds(selected)
        ?.let { PageBounds.clampTranslation(it, dx, dy) }
        ?: InkPoint(dx, dy)
    return mapIndexed { index, stroke ->
        if (selected[index]) {
            stroke.copy(offsetX = stroke.offsetX + delta.x, offsetY = stroke.offsetY + delta.y)
        } else {
            stroke
        }
    }
}

/** Replays a persisted resize against the live projections inside its original lasso. */
internal fun List<PageStroke>.replayResize(
    path: List<InkPoint>,
    targetIds: Collection<String>,
    anchor: InkPoint,
    scaleX: Float,
    scaleY: Float,
): List<PageStroke> {
    val targets = targetIds.toSet()
    if (path.size < 3 || targets.isEmpty()) return this
    // A plain move stores no scale, and every stored move is replayed through here — so on most
    // pages this is the whole of the work and none of it changes anything. Provably nothing:
    // `clampScale` only ever *lowers* a scale towards 1 and never below it, so an identity scale
    // survives the clamp, and `scaledAround` by 1 returns the stroke it was given. On the page this
    // was found on it was 228 ms of a 3.8 s open, spent to arrive back where it started.
    if (scaleX == 1f && scaleY == 1f) return this
    val lasso = LassoShape(path)
    val selected = map { it.id in targets && lasso.contains(it) }
    // Held to the origin corner for the reason [replayMove] gives, and by the same one measurement:
    // a resize about a far corner drags the near edges towards it.
    val scale = movingBounds(selected)
        ?.let { PageBounds.clampScale(it, anchor, scaleX, scaleY) }
        ?: InkPoint(scaleX, scaleY)
    return mapIndexed { index, stroke ->
        if (selected[index]) stroke.scaledAround(anchor, scale.x, scale.y) else stroke
    }
}

/** The rectangle around everything a replayed operation is about to move, in page units. */
private fun List<PageStroke>.movingBounds(selected: List<Boolean>): InkBounds? =
    filterIndexed { index, _ -> selected[index] }
        .mapNotNull(PageStroke::pageBounds)
        .unionBounds()

private fun pointInPolygon(point: InkPoint, polygon: List<InkPoint>): Boolean {
    var inside = false
    var previous = polygon.last()
    polygon.forEach { current ->
        val crosses = (current.y > point.y) != (previous.y > point.y)
        if (crosses) {
            val crossingX = (previous.x - current.x) * (point.y - current.y) /
                (previous.y - current.y) + current.x
            if (point.x < crossingX) inside = !inside
        }
        previous = current
    }
    return inside
}

/**
 * A lasso polygon, prepared once so that testing a whole page against it does not walk every mesh.
 *
 * The exact test in [containsExactly] reads every outline vertex of every stroke through JNI, which
 * is the right answer and far too much of it: replaying the page-sized lasso that repairs a drawing's
 * position cost 253 ms of a page open, and an interactive lasso pays the same per gesture. Both
 * shortcuts below are *decisions*, never approximations — each one reaches a conclusion the exact
 * walk would have reached, using the bounding box the stroke already cached.
 *
 * A projection with no outlines — a piece of erased ink — is answered by [encloses] instead, which is
 * the same question put to the mesh.
 */
internal class LassoShape(
    val path: List<InkPoint>,
    private val edgeTolerance: Float = DEFAULT_LASSO_EDGE_TOLERANCE,
) {
    val usable: Boolean = path.size >= 3

    // The polygon's own extent, grown by the tolerance, so "outside this box" means "outside the
    // tolerant test" and not merely "outside the polygon".
    private val minX = path.minOf { it.x } - edgeTolerance
    private val minY = path.minOf { it.y } - edgeTolerance
    private val maxX = path.maxOf { it.x } + edgeTolerance
    private val maxY = path.maxOf { it.y } + edgeTolerance

    /**
     * Whether every turn goes the same way, and so whether a box may be accepted whole.
     *
     * A convex polygon contains the convex hull of any points it contains, so four corners inside it
     * put the entire rectangle inside it — and every outline vertex lies within that rectangle. A
     * lasso drawn by hand usually is not convex, and then only [couldContain] applies.
     */
    val acceptsWholeBox: Boolean = isConvex(path)

    /**
     * Whether any part of a stroke this size could be inside the lasso at all.
     *
     * False is a decision, not a guess: the bounding box is tight, so each of its edges is touched by
     * some outline vertex, and a box reaching past the grown extent therefore has a vertex the exact
     * walk would have failed on.
     */
    fun couldContain(bounds: InkBounds): Boolean =
        bounds.left >= minX && bounds.top >= minY && bounds.right <= maxX && bounds.bottom <= maxY

    fun contains(stroke: PageStroke): Boolean {
        if (!usable) return false
        val bounds = stroke.pageBounds ?: return false
        if (!couldContain(bounds)) return false
        // Tested without the tolerance, so this only ever concludes what the tolerant walk would
        // also have concluded.
        if (acceptsWholeBox && corners(bounds).all { pointInPolygon(it, path) }) return true
        return stroke.containsExactly(path, edgeTolerance) ?: encloses(stroke)
    }

    /**
     * The same question asked of a projection that has no outlines to walk — a piece of erased ink.
     *
     * **This is why erased ink could not be lassoed at all.** `Stroke.split` — which is what gives
     * each surviving region of a partially erased stroke its own projection ([subtract]) — returns
     * pieces whose meshes carry *no outlines*, the same fact that stops a cut highlighter from being
     * split ([isDrawnFromOutlines]). [containsExactly] walks outlines and so walked nothing, and
     * every piece of erased ink answered "not inside" to every loop drawn round it. It went unnoticed
     * because the convex shortcut above answers first for a rectangle or a triangle, which is every
     * lasso a test ever draws; a loop drawn by hand is never convex.
     *
     * Two tests, because neither alone is containment:
     * - [region] answers *overlaps the loop at all*. Coverage cannot answer more than that: it is the
     *   fraction of the mesh's triangles that **intersect** the region, so a piece lying half outside
     *   still measures 1.0 — measured on a device rather than assumed.
     * - [crosses] answers *reaches over the loop's edge*. A piece is by construction one connected
     *   region, so a piece that overlaps the loop without crossing its edge lies wholly inside it.
     *
     * **Two shapes of this were tried and are wrong.** Subtracting the region from the piece and
     * asking what is left outside cuts nothing at all: a region from `createClosedShape` carries no
     * outlines either, and that is what `Stroke.subtract` cuts with. Drawing the loop's edge as one
     * closed *stroke* and testing against that mesh looks right and is not reproducible: a stroke's
     * mesh is built through the brush's modeler, so it lags the input and depends on the timestamps
     * the path is fed with — the same loop came out truncated at x=39 of 45 with one clock and at
     * y=67 of 70 with another. [crosses] uses plain triangles, which are geometry and nothing else.
     *
     * The edge tolerance is deliberately not applied here: a hairline edge means ink must be inside
     * the loop rather than inside-or-within-a-few-dp of it, so grazing the edge of a piece of erased
     * ink misses where grazing an untouched stroke would catch. Widening the quads would trade that
     * for the opposite error — rejecting ink that is genuinely inside — and this is the half that
     * costs a user nothing but a slightly wider loop.
     */
    private fun encloses(stroke: PageStroke): Boolean {
        val region = region ?: return false
        val bounds = stroke.pageBounds ?: return false
        val shape = stroke.stroke.shape
        val toStroke = stroke.pageToStrokeTransform()
        if (!shape.computeCoverageIsGreaterThan(region, 0f, toStroke)) return false
        return !crosses(shape, bounds, toStroke)
    }

    /**
     * Whether any ink of [shape] lies on the loop's own edge, tested one segment at a time.
     *
     * Each segment is covered by a thin quad — two triangles, because a triangle is the shape the
     * mesh can be asked about without a rotation convention in between. Segments whose own extent
     * cannot reach [bounds] are skipped before any of that is built, which is what keeps this cheap
     * on a hand-drawn loop: those run to hundreds of samples, and a piece of ink is near a handful.
     */
    private fun crosses(
        shape: PartitionedMesh,
        bounds: InkBounds,
        toStroke: AffineTransform,
    ): Boolean {
        val reach = EDGE_WIDTH / 2f
        closedPath.zipWithNext().forEach { (a, b) ->
            val nearBounds = minOf(a.x, b.x) - reach <= bounds.right &&
                maxOf(a.x, b.x) + reach >= bounds.left &&
                minOf(a.y, b.y) - reach <= bounds.bottom &&
                maxOf(a.y, b.y) + reach >= bounds.top
            if (!nearBounds) return@forEach
            val length = hypot(b.x - a.x, b.y - a.y)
            if (length == 0f) return@forEach
            val nx = -(b.y - a.y) / length * reach
            val ny = (b.x - a.x) / length * reach
            val corner1 = ImmutableVec(a.x + nx, a.y + ny)
            val corner2 = ImmutableVec(b.x + nx, b.y + ny)
            val corner3 = ImmutableVec(b.x - nx, b.y - ny)
            val corner4 = ImmutableVec(a.x - nx, a.y - ny)
            val touched = shape.computeCoverageIsGreaterThan(
                ImmutableTriangle(corner1, corner2, corner3), 0f, toStroke,
            ) || shape.computeCoverageIsGreaterThan(
                ImmutableTriangle(corner1, corner3, corner4), 0f, toStroke,
            )
            if (touched) return true
        }
        return false
    }

    /**
     * The path closed back to its first point.
     *
     * Closed explicitly because the polygon tests close it implicitly — a gesture is a run of samples
     * and not a loop — and an open path would leave the region's mouth and a gap in its edge for ink
     * to sit in.
     */
    private val closedPath: List<InkPoint> = if (usable) path + path.first() else path

    /**
     * The loop as a filled shape — built once, and only if a projection ever asks for it.
     *
     * Lazy because a page of ordinary strokes never reaches [encloses]. Null when the path cannot be
     * made into stroke inputs at all, which leaves [encloses] answering what it answered before this
     * existed. Consecutive repeats are dropped and the clock is the sample index: the batch rejects a
     * duplicate input and its documentation asks the caller to sanitise rather than to catch, and the
     * shape this builds depends on the positions alone.
     */
    private val region: PartitionedMesh? by lazy {
        runCatching {
            MutableStrokeInputBatch().apply {
                var previous: InkPoint? = null
                closedPath.forEachIndexed { index, point ->
                    if (point != previous) {
                        add(InputToolType.UNKNOWN, point.x, point.y, index.toLong())
                        previous = point
                    }
                }
            }.toImmutable().createClosedShape()
        }.getOrNull()
    }

    private fun corners(bounds: InkBounds): List<InkPoint> = listOf(
        InkPoint(bounds.left, bounds.top),
        InkPoint(bounds.right, bounds.top),
        InkPoint(bounds.right, bounds.bottom),
        InkPoint(bounds.left, bounds.bottom),
    )

    private companion object {
        /**
         * How wide the loop's own edge is taken to be, in page units.
         *
         * Thin enough to be the line and not a band around it, wide enough that ink crossing the line
         * cannot thread between the two triangles laid along it.
         */
        const val EDGE_WIDTH = 1f
    }
}

private fun isConvex(polygon: List<InkPoint>): Boolean {
    if (polygon.size < 3) return false
    var sign = 0
    polygon.indices.forEach { index ->
        val a = polygon[index]
        val b = polygon[(index + 1) % polygon.size]
        val c = polygon[(index + 2) % polygon.size]
        val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        // Collinear vertices turn neither way and constrain nothing.
        if (cross != 0f) {
            val turn = if (cross > 0f) 1 else -1
            if (sign == 0) sign = turn else if (sign != turn) return false
        }
    }
    return sign != 0
}

/**
 * Tests the actual rendered outline instead of the object's bounding-box corners. Curved lassos
 * can closely enclose triangular or circular ink while naturally excluding the unused corners of
 * that rectangle. The small tolerance absorbs finger/stylus jitter right along a visible edge.
 *
 * **Null means "this projection has no outline to walk", which is not the same as "outside".** A
 * piece of erased ink is exactly that (`Stroke.split` drops outlines), and answering false for it is
 * the bug [LassoShape.encloses] exists to fix — so the two answers are kept apart here rather than
 * collapsed into the `outlineVertexCount > 0` this used to end on.
 */
private fun PageStroke.containsExactly(polygon: List<InkPoint>, edgeTolerance: Float): Boolean? {
    val position = MutableVec()
    var outlineVertexCount = 0
    val shape = stroke.shape
    repeat(shape.getRenderGroupCount()) { groupIndex ->
        repeat(shape.getOutlineCount(groupIndex)) { outlineIndex ->
            repeat(shape.getOutlineVertexCount(groupIndex, outlineIndex)) { vertexIndex ->
                shape.populateOutlinePosition(groupIndex, outlineIndex, vertexIndex, position)
                outlineVertexCount++
                val pagePoint = InkPoint(
                    x = position.x * scaleX + offsetX,
                    y = position.y * scaleY + offsetY,
                )
                if (!pointInOrNearPolygon(pagePoint, polygon, edgeTolerance)) return false
            }
        }
    }
    return if (outlineVertexCount > 0) true else null
}

internal fun pointInOrNearPolygon(
    point: InkPoint,
    polygon: List<InkPoint>,
    edgeTolerance: Float,
): Boolean {
    if (pointInPolygon(point, polygon)) return true
    val toleranceSquared = edgeTolerance.coerceAtLeast(0f).let { it * it }
    if (toleranceSquared == 0f) return false
    var previous = polygon.last()
    polygon.forEach { current ->
        if (point.distanceSquaredToSegment(previous, current) <= toleranceSquared) return true
        previous = current
    }
    return false
}

internal fun InkPoint.distanceSquaredToSegment(start: InkPoint, end: InkPoint): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0f) {
        val pointDx = x - start.x
        val pointDy = y - start.y
        return pointDx * pointDx + pointDy * pointDy
    }
    val fraction = (((x - start.x) * dx + (y - start.y) * dy) / lengthSquared)
        .coerceIn(0f, 1f)
    val nearestX = start.x + fraction * dx
    val nearestY = start.y + fraction * dy
    val pointDx = x - nearestX
    val pointDy = y - nearestY
    return pointDx * pointDx + pointDy * pointDy
}

internal const val DEFAULT_LASSO_EDGE_TOLERANCE = 4f
