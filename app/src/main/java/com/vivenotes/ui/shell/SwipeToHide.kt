package com.vivenotes.ui.shell

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** A deliberate leftward drag that does not compete with the panes' vertical scrolling. */
internal fun Modifier.swipeLeft(onSwipeLeft: () -> Unit): Modifier =
    swipeHorizontally(TOWARDS_START, onSwipeLeft)

/**
 * The mirror of [swipeLeft], for anything docked on the *right* edge.
 *
 * Same gesture, same threshold, opposite direction: a pane leaves the way it came in, so the one on
 * the right is pushed off to the right.
 */
internal fun Modifier.swipeRight(onSwipeRight: () -> Unit): Modifier =
    swipeHorizontally(TOWARDS_END, onSwipeRight)

/**
 * Only unconsumed drags arrive here, which is what keeps this off the controls inside a pane: a
 * slider or a text selection handle claims the horizontal drag first, and this never sees it.
 */
private fun Modifier.swipeHorizontally(direction: Float, onSwipe: () -> Unit): Modifier =
    pointerInput(direction, onSwipe) {
        val threshold = 64.dp.toPx()
        var distance = 0f

        detectHorizontalDragGestures(
            onDragStart = { distance = 0f },
            onDragCancel = { distance = 0f },
            onDragEnd = {
                if (distance * direction >= threshold) onSwipe()
                distance = 0f
            },
            onHorizontalDrag = { _, dragAmount -> distance += dragAmount },
        )
    }

private const val TOWARDS_START = -1f
private const val TOWARDS_END = 1f
