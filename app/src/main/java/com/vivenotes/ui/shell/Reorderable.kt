package com.vivenotes.ui.shell

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Drag-to-reorder for rows of a [androidx.compose.foundation.lazy.LazyColumn].
 *
 * Compose has no reorderable lazy list, and the two places that need one here disagree about
 * almost everything — the page list is a flat list of variable-height rows, the notebook rail is a
 * tree whose draggable rows are one notebook's sections sitting between headers that must not
 * move. So this is written against *keys* rather than indices, and the caller says which keys are
 * currently draggable. Everything else in the list is simply not a drop target.
 *
 * Row heights are read from the live layout rather than assumed, because a page row is one, two or
 * three lines tall depending on whether it has a preview.
 *
 * **The list reorders under the finger, not on release.** The caller keeps an optimistic copy of
 * the order and mutates it on every [onMove]; [onSettle] is when that copy should be written down.
 * A gesture the system cancels mid-drag therefore keeps what the user could already see rather
 * than snapping back to an arrangement they had stopped looking at.
 */
@Stable
internal class ReorderState internal constructor(
    private val listState: LazyListState,
    private val keys: () -> List<Any>,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onSettle: () -> Unit,
) {

    /** The row under the finger, or null when nothing is being dragged. */
    var draggedKey: Any? by mutableStateOf(null)
        private set

    /** Where the dragged row's top edge sat in the viewport when the finger went down. */
    private var anchor = 0f
    private var height = 0

    /**
     * How far the finger has travelled since. Kept as state so the row's offset can be recomputed
     * in the draw phase, where it costs a redraw rather than a recomposition of the whole list.
     */
    private var travelled by mutableFloatStateOf(0f)

    val dragging: Boolean get() = draggedKey != null

    fun start(key: Any) {
        val info = listState.itemInfo(key) ?: return
        draggedKey = key
        anchor = info.offset.toFloat()
        height = info.size
        travelled = 0f
    }

    fun drag(dy: Float) {
        if (draggedKey == null) return
        travelled += dy
        swapUnderFinger()
    }

    fun settle() {
        if (draggedKey == null) return
        draggedKey = null
        travelled = 0f
        onSettle()
    }

    /** How far [key]'s row should be drawn from wherever the list has just laid it out. */
    fun offsetFor(key: Any): Float {
        if (key != draggedKey) return 0f
        // Its slot keeps moving — both because the list reorders around it and because auto-scroll
        // slides everything past — so the translation is measured fresh against the anchor the
        // finger is actually holding, not accumulated.
        val laidOutAt = listState.itemInfo(key)?.offset?.toFloat() ?: return 0f
        return anchor + travelled - laidOutAt
    }

    /**
     * Whichever draggable row the dragged one is now centred over trades places with it.
     *
     * Centre-in-bounds rather than edge overlap: overlap is true of two rows at once through the
     * whole of a slow drag, which makes the list flicker between two arrangements.
     */
    private fun swapUnderFinger() {
        val key = draggedKey ?: return
        val order = keys()
        val from = order.indexOf(key)
        if (from < 0) return
        val centre = anchor + travelled + height / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.key != key &&
                centre >= item.offset &&
                centre <= item.offset + item.size &&
                item.key in order
        } ?: return
        val to = order.indexOf(target.key)
        if (to >= 0 && to != from) onMove(from, to)
    }

    /**
     * Scrolls the list when the dragged row is held against an edge, so a page can be moved
     * somewhere that was off screen when the drag began.
     */
    internal suspend fun autoScrollStep(edge: Float, maxStep: Float) {
        if (draggedKey == null || edge <= 0f) return
        val viewport = listState.layoutInfo
        val top = anchor + travelled
        val pastTop = viewport.viewportStartOffset + edge - top
        val pastBottom = (top + height) - (viewport.viewportEndOffset - edge)
        val push = when {
            pastTop > 0f -> -(pastTop / edge).coerceAtMost(1f) * maxStep
            pastBottom > 0f -> (pastBottom / edge).coerceAtMost(1f) * maxStep
            else -> return
        }
        // Rows slid past the stationary finger are new drop targets, so re-test after moving.
        if (listState.scrollBy(push) != 0f) swapUnderFinger()
    }
}

/** How close to an edge the dragged row must be held before the list starts scrolling itself. */
private val AUTO_SCROLL_EDGE = 56.dp

/** Scroll rate at the very edge, per frame. Ramps up across [AUTO_SCROLL_EDGE] rather than snapping on. */
private val AUTO_SCROLL_MAX_STEP = 14.dp

/**
 * @param keys the rows that may be dragged, in the order they are currently shown. Indices handed
 *   to [onMove] index into this list, so it is also what the caller reorders.
 * @param onSettle the drag finished; persist what [onMove] has been building.
 */
@Composable
internal fun rememberReorderState(
    listState: LazyListState,
    keys: List<Any>,
    onMove: (from: Int, to: Int) -> Unit,
    onSettle: () -> Unit,
): ReorderState {
    val currentKeys = rememberUpdatedState(keys)
    val currentMove = rememberUpdatedState(onMove)
    val currentSettle = rememberUpdatedState(onSettle)
    val state = remember(listState) {
        ReorderState(
            listState = listState,
            keys = { currentKeys.value },
            onMove = { from, to -> currentMove.value(from, to) },
            onSettle = { currentSettle.value() },
        )
    }

    val edge = with(LocalDensity.current) { AUTO_SCROLL_EDGE.toPx() }
    val maxStep = with(LocalDensity.current) { AUTO_SCROLL_MAX_STEP.toPx() }
    // Frame-driven rather than driven by drag events, or holding still at the edge — which is
    // exactly what reaching for an off-screen row looks like — would stop scrolling.
    LaunchedEffect(state, state.dragging) {
        if (!state.dragging) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            state.autoScrollStep(edge, maxStep)
        }
    }
    return state
}

/**
 * Marks a row as the one to lift and follow the finger while it is the dragged one.
 *
 * Separate from [reorderHandle] because they go on different things: this on the row, the handle
 * on the grip inside it.
 */
internal fun Modifier.reorderable(state: ReorderState, key: Any): Modifier =
    zIndex(if (state.draggedKey == key) 1f else 0f)
        .graphicsLayer { translationY = state.offsetFor(key) }

/**
 * The grip that starts a drag.
 *
 * Deliberately a handle rather than a long press on the row: the page list already spends its long
 * press on the delete menu, and a list whose rows are also buttons should not make holding one
 * ambiguous.
 *
 * [onGrabbed] runs before the drag does, for a caller whose set of draggable keys depends on which
 * row was picked up — the notebook rail's, which is one notebook's sections and not another's.
 */
internal fun Modifier.reorderHandle(
    state: ReorderState,
    key: Any,
    onGrabbed: () -> Unit = {},
): Modifier =
    pointerInput(state, key) {
        detectDragGestures(
            onDragStart = {
                onGrabbed()
                state.start(key)
            },
            onDragEnd = { state.settle() },
            onDragCancel = { state.settle() },
            onDrag = { change, drag ->
                change.consume()
                state.drag(drag.y)
            },
        )
    }

private fun LazyListState.itemInfo(key: Any): LazyListItemInfo? =
    layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
