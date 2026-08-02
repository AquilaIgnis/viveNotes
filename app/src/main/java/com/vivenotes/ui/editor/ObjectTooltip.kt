package com.vivenotes.ui.editor

import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vivenotes.ink.InkLassoSelection
import com.vivenotes.ink.PageStroke
import com.vivenotes.ui.icons.MaterialSymbols
import kotlin.math.roundToInt

internal const val OBJECT_TOOLTIP_TAG = "object-tooltip"
internal const val OBJECT_COLOR_TAG = "object-tooltip-color"
internal const val OBJECT_COPY_TAG = "object-tooltip-copy"
internal const val OBJECT_GROUP_TAG = "object-tooltip-group"
internal const val OBJECT_DELETE_TAG = "object-tooltip-delete"

private val INK_COLORS = listOf(
    "White" to 0xFFFFFFFF.toInt(),
    "Black" to 0xFF000000.toInt(),
    "Gray" to 0xFF6B7280.toInt(),
    "Red" to 0xFFEF4444.toInt(),
    "Orange" to 0xFFF59E0B.toInt(),
    "Green" to 0xFF22C55E.toInt(),
    "Cyan" to 0xFF06B6D4.toInt(),
    "Blue" to 0xFF3B82F6.toInt(),
    "Purple" to 0xFF8B5CF6.toInt(),
    "Pink" to 0xFFEC4899.toInt(),
)

/** Context actions for a lasso-selected ink object, positioned against its live screen bounds. */
@Composable
internal fun InkObjectTooltip(
    selection: InkLassoSelection,
    strokes: List<PageStroke>,
    selectionBoundsInView: () -> RectF?,
    viewportSize: IntSize,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onRecolor: (Int) -> Unit,
    onGroup: () -> Unit,
    onUngroup: () -> Unit,
) {
    val selected = strokes.filter { it.id in selection.targetIds }
    val selectedColors = selected.map { it.stroke.brush.colorIntArgb }.distinct()
    val swatch = selectedColors.singleOrNull()?.let(::Color) ?: Color.White
    val groups = selected.map(PageStroke::groupId).distinct()
    val isOneGroup = selection.targetIds.size > 1 && groups.size == 1 && groups.single() != null
    var paletteOpen by remember { mutableStateOf(false) }
    var tooltipSize by remember { mutableStateOf(IntSize.Zero) }

    Surface(
        color = Color(0xFF202124),
        contentColor = Color(0xFFE8EAED),
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 8.dp,
        modifier = Modifier
            .testTag(OBJECT_TOOLTIP_TAG)
            .onSizeChanged { tooltipSize = it }
            .offsetForSelection(
                tooltipSize = tooltipSize,
                viewportSize = viewportSize,
                selectionBoundsInView = selectionBoundsInView,
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box {
                IconButton(
                    onClick = { paletteOpen = true },
                    modifier = Modifier
                        .size(32.dp)
                        .testTag(OBJECT_COLOR_TAG)
                        .semantics { contentDescription = "Change selected ink color" },
                ) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(swatch),
                    )
                }
                DropdownMenu(
                    expanded = paletteOpen,
                    onDismissRequest = { paletteOpen = false },
                ) {
                    INK_COLORS.chunked(5).forEach { row ->
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                            row.forEach { (name, argb) ->
                                Box(
                                    Modifier
                                        .padding(4.dp)
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(Color(argb))
                                        .semantics { contentDescription = name }
                                        .clickable {
                                            paletteOpen = false
                                            onRecolor(argb)
                                        },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(
                Modifier
                    .padding(horizontal = 4.dp)
                    .width(1.dp)
                    .height(22.dp)
                    .background(Color(0xFF5F6368)),
            )

            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(32.dp).testTag(OBJECT_COPY_TAG),
            ) {
                Icon(
                    MaterialSymbols.ContentCopy,
                    contentDescription = "Copy selected ink",
                    modifier = Modifier.size(18.dp),
                )
            }

            if (selection.targetIds.size > 1) {
                TextButton(
                    onClick = if (isOneGroup) onUngroup else onGroup,
                    contentPadding = PaddingValues(horizontal = 7.dp),
                    modifier = Modifier.height(32.dp).testTag(OBJECT_GROUP_TAG),
                ) {
                    Text(
                        text = if (isOneGroup) "Ungroup" else "Group",
                        color = Color(0xFFE8EAED),
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    )
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp).testTag(OBJECT_DELETE_TAG),
            ) {
                Icon(
                    MaterialSymbols.Delete,
                    contentDescription = "Delete selected ink",
                    tint = Color(0xFFFFA8A8),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private fun Modifier.offsetForSelection(
    tooltipSize: IntSize,
    viewportSize: IntSize,
    selectionBoundsInView: () -> RectF?,
): Modifier = this.then(
    Modifier.offset {
        val bounds = selectionBoundsInView() ?: return@offset IntOffset.Zero
        val margin = 8.dp.roundToPx()
        val gap = 12.dp.roundToPx()
        val width = tooltipSize.width
        val height = tooltipSize.height
        val maximumX = (viewportSize.width - width - margin).coerceAtLeast(margin)
        val x = (bounds.centerX() - width / 2f).roundToInt().coerceIn(margin, maximumX)
        val above = bounds.top.roundToInt() - height - gap
        val below = bounds.bottom.roundToInt() + gap
        val maximumY = (viewportSize.height - height - margin).coerceAtLeast(margin)
        val y = (if (above >= margin) above else below).coerceIn(margin, maximumY)
        IntOffset(x, y)
    },
)
