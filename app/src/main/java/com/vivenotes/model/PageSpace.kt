package com.vivenotes.model

/**
 * Insert Space — feature E2, `memory/plan.md` phase 5.
 *
 * OneNote's Draw tab has a tool that does something no other tool on a free-form canvas does: it
 * edits the *emptiness*. You draw a line across the page and drag, and everything past that line
 * moves with the drag while everything before it stays — so a paragraph written too close under a
 * diagram can be given room without selecting it, and a gap left by mistake can be closed the same
 * way. Dragging back the other way takes the space away again.
 *
 * **This is a translation applied to a half-plane, and that is the whole of the model.** It is
 * deliberately Android-free and deliberately not a document type: nothing is stored *as* an inserted
 * space, because there is nothing to store — the page after the gesture is a page whose objects have
 * different coordinates, and that is a state every part of the app already understands. A stored
 * "space" would be a second thing that decides where content lives, and everything from export to
 * search to the sync client would have to learn about it.
 *
 * ### Which side of the line an object is on
 *
 * Its **near edge** — the top for a vertical cut, the left for a horizontal one — and nothing else.
 * An object that straddles the line therefore stays put, which is the only answer that keeps the
 * gesture honest: the alternative is a picture that starts above the cut being pushed down and
 * opening a gap where the user was not asking for one. It also means the rule can be stated in one
 * sentence to a user — *everything that starts below the line moves* — which a bounding-box overlap
 * test cannot.
 *
 * This is also the only rule the *document* can answer. A text container's height is whatever its
 * text wraps to and only the canvas knows it ([Outline.Text.minHeight] is a floor, not a height), and
 * a table's is the sum of its row floors — so a far edge is a number the model does not have. A near
 * edge is exact for every kind.
 */
object PageSpace {

    /** Which way the space is being made. Named for the *line*'s orientation, as OneNote's is. */
    enum class Axis {
        /** A horizontal line; content below it moves up or down. */
        Vertical,

        /** A vertical line; content to its right moves left or right. */
        Horizontal,
    }
}

/**
 * One completed Insert Space gesture, in page units (dp).
 *
 * [at] is measured on [axis]'s own coordinate — a y for [PageSpace.Axis.Vertical], an x for
 * [PageSpace.Axis.Horizontal] — because a line across the page has only one. [amount] is signed:
 * positive opens space, negative closes it.
 */
data class SpaceCut(
    val axis: PageSpace.Axis,
    val at: Float,
    val amount: Float,
) {
    /** Nothing to do. A tap with the tool in hand is not a mis-drag, it is simply not a gesture. */
    val isEmpty: Boolean get() = amount == 0f

    val dx: Float get() = if (axis == PageSpace.Axis.Horizontal) amount else 0f

    val dy: Float get() = if (axis == PageSpace.Axis.Vertical) amount else 0f

    /** The coordinate this cut compares against, for an object with its corner at ([x], [y]). */
    fun nearEdge(x: Float, y: Float): Float = if (axis == PageSpace.Axis.Horizontal) x else y

    /** Whether an object with its corner at ([x], [y]) is on the moving side of the line. */
    fun moves(x: Float, y: Float): Boolean = nearEdge(x, y) >= at

    /**
     * As much of [amount] as keeps every moved object on the far side of the line.
     *
     * **The line is the floor, and that is a deliberate choice over OneNote's.** OneNote stops a
     * closing drag when the content below meets the content *above*, which needs both objects'
     * heights; this stops it when the content below meets the line the user drew, which needs
     * neither. The two agree whenever the line is placed against the bottom of the content above it —
     * which is where you put it anyway, because that is the gap you are trying to close — and where
     * they differ this one is the rule you can see: the line is on screen, and content does not cross
     * it.
     *
     * The alternative was to measure the content above, and the model cannot: see [PageSpace] for why
     * a far edge is a number the document does not have.
     *
     * A consequence worth stating, because it is what keeps [com.vivenotes.ink.PageBounds]'s origin
     * rule intact for free: a cut is only ever placed at a non-negative coordinate, so nothing this
     * limits can be pushed above or left of the page's corner.
     *
     * [nearestMovedEdge] is the smallest near edge among the objects that [moves] accepted, or null
     * when nothing moves.
     */
    fun limitedTo(nearestMovedEdge: Float?): SpaceCut {
        // Opening space has no limit — the canvas grows downward and to the right without bound
        if (amount >= 0f || nearestMovedEdge == null) return this
        return copy(amount = amount.coerceAtLeast(at - nearestMovedEdge))
    }
}
