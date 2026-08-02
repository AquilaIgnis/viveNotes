package com.vivenotes.ui.shell

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** A deliberate leftward drag that does not compete with the panes' vertical scrolling. */
internal fun Modifier.swipeLeft(onSwipeLeft: () -> Unit): Modifier = pointerInput(onSwipeLeft) {
    val threshold = 64.dp.toPx()
    var distance = 0f

    detectHorizontalDragGestures(
        onDragStart = { distance = 0f },
        onDragCancel = { distance = 0f },
        onDragEnd = {
            if (distance <= -threshold) onSwipeLeft()
            distance = 0f
        },
        onHorizontalDrag = { _, dragAmount -> distance += dragAmount },
    )
}
