package com.vivenotes.ui.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import com.vivenotes.data.ViewSettings
import kotlin.math.abs

/**
 * What one step of a pinch does to the viewport: the zoom it reached, and how far the page has to
 * scroll to keep the point under the fingers still under them.
 *
 * Both halves, because they are one decision. Zoom alone scales the page about its top-left corner,
 * so pinching the bottom of a long page would send what you were looking at off the screen.
 */
internal data class PinchStep(val zoom: Float, val dx: Float, val dy: Float)

/**
 * Resolves one pinch sample into a zoom and a scroll.
 *
 * All of it is in **view pixels**, which is what makes it this short: `scroll + focus` is the content
 * pixel under the fingers, and changing the zoom by a factor multiplies every content pixel
 * coordinate by that same factor. No density and no page units are involved — the conversion those
 * would need is exactly the step this avoids.
 *
 * [focus] is the centroid *before* this sample and [pan] is how far it has moved since, so the
 * fingers now sit at `focus + pan`. Splitting them is what lets one expression hold both halves of a
 * pinch: the content point at `scroll + focus` has to end up under `focus + pan`.
 *
 * Clamping to the zoom range happens here rather than at the caller, so a pinch that runs past 400%
 * stops scaling *and* stops scrolling, instead of continuing to chase a zoom it will not get.
 *
 * Pure arithmetic with no Android in it, so it is checked by a JVM unit test — which matters here
 * because the gesture that feeds it can only be exercised on a device (R10).
 */
internal fun pinchStep(
    zoom: Float,
    scrollX: Float,
    scrollY: Float,
    focus: Offset,
    pan: Offset,
    zoomChange: Float,
): PinchStep {
    val next = (zoom * zoomChange).coerceIn(ViewSettings.MIN_ZOOM, ViewSettings.MAX_ZOOM)
    val applied = next / zoom
    return PinchStep(
        zoom = next,
        dx = (scrollX + focus.x) * applied - focus.x - pan.x - scrollX,
        dy = (scrollY + focus.y) * applied - focus.y - pan.y - scrollY,
    )
}

/**
 * Two fingers on the page: pinch to zoom, and move them together to pan.
 *
 * **Why this is a hand-written detector on the [PointerEventPass.Initial] pass**, rather than
 * `detectTransformGestures`:
 *
 * - *Initial*, because it has to win. The page is a stack of gesture handlers that each own the whole
 *   pointer — the scroll containers, the canvas tap that opens a text container, and the ink
 *   overlay's own dispatcher — and a sibling of a hit node never sees the event at all. Only an
 *   ancestor is guaranteed to be asked, and only the Initial pass reaches it *before* its children.
 *   Consuming there is what tells all of them to let go, which they already know how to do: every
 *   gesture in `InkOverlay` abandons itself on a second contact.
 * - *Two pointers, not one.* `detectTransformGestures` pans with a single finger, which is the page's
 *   own scroll and the ink overlay's pan — it would take both.
 *
 * [onEnd] fires once per pinch that actually happened, and is where the zoom gets written down: the
 * live steps are transient state, so a gesture is one preference write rather than sixty.
 */
internal suspend fun PointerInputScope.detectPinchZoom(
    onPinch: (focus: Offset, pan: Offset, zoomChange: Float) -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        // Unconsumed is not required: the first finger is somebody else's until a second one lands,
        // and by then whoever took it has already consumed it.
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)

        var zooming = false
        var spread = 1f
        var travel = Offset.Zero

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val down = event.changes.count { it.pressed }
            if (down == 0) break
            if (down < 2) {
                // Down to one finger. A pinch does not resume when a second returns — that is a new
                // gesture — and until one arrives this is a plain drag that belongs to the page.
                if (zooming) break
                continue
            }

            val zoomChange = event.calculateZoom()
            val pan = event.calculatePan()
            val focus = event.calculateCentroid(useCurrent = false)
            if (!focus.isSpecified) continue

            if (!zooming) {
                // The same slop test `detectTransformGestures` applies, and for the same reason: two
                // fingers resting on the page are not yet a gesture, and treating them as one would
                // make the page twitch every time a hand steadied itself on it.
                spread *= zoomChange
                travel += pan
                val spreadInPixels = abs(1 - spread) * event.calculateCentroidSize(useCurrent = false)
                if (spreadInPixels > viewConfiguration.touchSlop ||
                    travel.getDistance() > viewConfiguration.touchSlop
                ) {
                    zooming = true
                }
            }

            if (zooming) {
                onPinch(focus, pan, zoomChange)
                // Every pointer, not just the two being measured: what this says is "the gesture is
                // mine", and a third finger left unconsumed is one the page would still scroll from.
                event.changes.forEach { if (it.pressed) it.consume() }
            }
        }

        if (zooming) onEnd()
    }
}
