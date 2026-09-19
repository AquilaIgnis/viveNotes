package com.vivenotes.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize

/**
 * Gives a canvas layer its document-space extent without constructing a fixed [Constraints] pair.
 *
 * Compose packs both axes of [Constraints] into one value. A page can therefore be legal when it is
 * very wide or very tall, but become unrepresentable when it is both at once. Document coordinates
 * do not have that restriction: a layout can report each measured axis independently. Measuring the
 * layer unbounded and reporting [extent] directly keeps the constraint encoding out of the page's
 * geometry, which is the property an infinite canvas needs.
 */
internal fun Modifier.documentExtent(extent: DpSize): Modifier = layout { measurable, _ ->
    val placeable = measurable.measure(Constraints())
    layout(extent.width.roundToPx(), extent.height.roundToPx()) {
        placeable.place(0, 0)
    }
}

/** Fill an ordinary bounded parent, or report a document extent when the page is virtual. */
internal fun Modifier.fillDocument(extent: DpSize?): Modifier =
    if (extent == null) fillMaxSize() else documentExtent(extent)

/** A page-sized sibling stack whose children are all measured without a packed fixed-size pair. */
@Composable
internal fun DocumentLayout(
    extent: DpSize,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, _ ->
        val placeables = measurables.map { it.measure(Constraints()) }
        layout(extent.width.roundToPx(), extent.height.roundToPx()) {
            placeables.forEach { it.place(0, 0) }
        }
    }
}

/**
 * A drawing surface with the same dual bounded/document-space behaviour as [fillDocument].
 *
 * [Canvas] appends its draw modifier inside the modifier it receives. For a virtual extent the draw
 * modifier must instead wrap [documentExtent], otherwise it would see the zero-sized spacer which is
 * deliberately measured underneath the virtual layout node.
 */
@Composable
internal fun DocumentCanvas(
    extent: DpSize?,
    modifier: Modifier = Modifier,
    onDraw: DrawScope.() -> Unit,
) {
    if (extent == null) {
        Canvas(modifier.fillMaxSize(), onDraw)
    } else {
        androidx.compose.foundation.layout.Spacer(
            modifier
                .drawBehind(onDraw)
                .documentExtent(extent),
        )
    }
}
