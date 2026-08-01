package st.unamedtba.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

private val EDGE_WIDTH = 30.dp

/** Test tags for the edge affordances. */
internal object ScrollEdgeTags {
    const val START = "scroll-edge-start"
    const val END = "scroll-edge-end"
}

/**
 * A row that scrolls horizontally and says so.
 *
 * Every strip in this app overflows on a narrow window — the ribbon tabs, the Home and View
 * controls, the section tabs — and a strip clipped at the edge looks exactly like a strip that
 * simply ends. Without these arrows the only way to discover the rest of the View tab is to try
 * dragging a row of buttons and see what happens.
 *
 * They indicate and nothing more: the row is already draggable, and making the arrows *buttons*
 * put a touch target on top of whatever control sits at the edge — "Switch Background", the last
 * item in the View tab, stopped being clickable, which a test caught before it shipped. Without a
 * pointer modifier the fade is invisible to touch and presses land on the control underneath.
 */
@Composable
fun ScrollingRow(
    modifier: Modifier = Modifier,
    state: ScrollState = rememberScrollState(),
    contentPadding: PaddingValues = PaddingValues(),
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    /** What the arrows fade into. Should match whatever the row is drawn on. */
    background: Color = MaterialTheme.colorScheme.surface,
    content: @Composable RowScope.() -> Unit,
) {
    // Derived so this recomposes when an arrow appears or disappears rather than on every scrolled
    // pixel: `canScrollForward` reads the scroll offset, and the ribbon already recomposes on every
    // cursor move without adding a frame-rate subscription to it.
    val atStart by remember(state) { derivedStateOf { !state.canScrollBackward } }
    val atEnd by remember(state) { derivedStateOf { !state.canScrollForward } }

    Box(modifier) {
        Row(
            modifier = Modifier
                .horizontalScroll(state)
                .padding(contentPadding),
            verticalAlignment = verticalAlignment,
            content = content,
        )

        // Overlaid in a box that matches the row's resolved size rather than contributing to it:
        // a child sizing itself from the parent it is measured inside would feed its own height
        // back into the row.
        Box(Modifier.matchParentSize()) {
            ScrollEdge(
                alignment = Alignment.CenterStart,
                visible = !atStart,
                icon = Icons.Default.KeyboardDoubleArrowLeft,
                label = "More to the left",
                tag = ScrollEdgeTags.START,
                background = background,
            )

            ScrollEdge(
                alignment = Alignment.CenterEnd,
                visible = !atEnd,
                icon = Icons.Default.KeyboardDoubleArrowRight,
                label = "More to the right",
                tag = ScrollEdgeTags.END,
                background = background,
            )
        }
    }
}

@Composable
private fun BoxScope.ScrollEdge(
    alignment: Alignment,
    visible: Boolean,
    icon: ImageVector,
    label: String,
    tag: String,
    background: Color,
) {
    val fadeAway = if (alignment == Alignment.CenterStart) {
        listOf(background, background.copy(alpha = 0f))
    } else {
        listOf(background.copy(alpha = 0f), background)
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(alignment),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(EDGE_WIDTH)
                // Painted as a fade rather than a solid block so the controls underneath read as
                // continuing past the edge, which is the thing being communicated.
                .background(Brush.horizontalGradient(fadeAway))
                .testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(18.dp),
            )
        }
    }
}
