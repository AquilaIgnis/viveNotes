package com.vivenotes.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import com.vivenotes.R
import com.vivenotes.ai.InkTextRegion
import kotlin.math.roundToInt

/**
 * What the handwriting on the page says, for a screen reader: a node over each recognized line,
 * described by its text.
 *
 * The ink is painted outside the zoom by [InkOverlay], which has no node per line and is hidden from
 * accessibility. These sit inside the zoom, in page units, so they follow the page as it scrolls and
 * scales. Semantics only: they take no touch, so the pen and the canvas are unaffected.
 *
 * @param modifier has to lay this out at the document's origin and size, or the lines land in the
 *   wrong place and any past this box's bounds are clipped out of the accessibility tree.
 */
@Composable
internal fun InkDescriptions(
    regions: List<InkTextRegion>,
    density: Float,
    modifier: Modifier = Modifier,
) {
    if (regions.isEmpty()) return
    Box(modifier) {
        regions.forEach { region ->
            key(region.id) {
                val spoken = region.spokenText()
                val description = if (spoken == null) {
                    stringResource(R.string.ink_description)
                } else {
                    stringResource(R.string.ink_description_text, spoken)
                }
                Box(
                    Modifier
                        .overPageRect(region.left, region.top, region.right, region.bottom, density)
                        .semantics { contentDescription = description },
                )
            }
        }
    }
}

/**
 * What a screen reader is told a line of handwriting says: the likelier of its two readings, or null
 * when neither is likely enough to be worth hearing. The line is then still announced, as
 * handwriting, so it can be found — only the guess is withheld.
 *
 * A search can afford a wrong reading, since a typo-tolerant query simply misses it. Spoken aloud, a
 * misreading is noise the listener cannot tell from the page.
 */
internal fun InkTextRegion.spokenText(): String? {
    val alternate = alternateText
    val alternateScore = alternateConfidence
    val (best, score) = if (alternate != null && alternateScore != null && alternateScore > confidence) {
        alternate to alternateScore
    } else {
        text to confidence
    }
    return best.takeIf { score >= SPOKEN_INK_CONFIDENCE && it.isNotBlank() }
}

/**
 * Below this, a reading is not spoken. Of 990 prose readings in `simulations/handwriting-search`,
 * two fall under it (the lowest is 0.699); handwritten maths read as text scores 0.11–0.68 where it
 * comes out as noise, and 0.74–0.84 where it is recognisable.
 */
internal const val SPOKEN_INK_CONFIDENCE = 0.7f

/**
 * Exactly over a rectangle given in page units, in a box laid out at the document's origin — how the
 * page's semantics-only nodes cover what they describe.
 */
internal fun Modifier.overPageRect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    density: Float,
): Modifier =
    offset { IntOffset((left * density).roundToInt(), (top * density).roundToInt()) }
        .layout { measurable, _ ->
            val width = ((right - left) * density).roundToInt().coerceAtLeast(0)
            val height = ((bottom - top) * density).roundToInt().coerceAtLeast(0)
            val placeable = measurable.measure(Constraints.fixed(width, height))
            layout(width, height) { placeable.place(0, 0) }
        }
