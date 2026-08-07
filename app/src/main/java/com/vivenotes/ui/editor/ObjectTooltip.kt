package com.vivenotes.ui.editor

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import com.vivenotes.model.ink.LineType
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.ui.panel.LineTypePicker
import com.vivenotes.ui.panel.pathEffect
import com.vivenotes.ui.panel.PanelSlider
import com.vivenotes.data.ShapeSettings
import kotlin.math.roundToInt

internal const val OBJECT_TOOLTIP_TAG = "object-tooltip"
internal const val OBJECT_COLOR_TAG = "object-tooltip-color"
internal const val OBJECT_COPY_TAG = "object-tooltip-copy"
internal const val OBJECT_GROUP_TAG = "object-tooltip-group"
internal const val OBJECT_DELETE_TAG = "object-tooltip-delete"
internal const val OBJECT_THICKNESS_TAG = "object-tooltip-thickness"
internal const val OBJECT_FILL_TAG = "object-tooltip-fill"
internal const val OBJECT_FILL_NONE_TAG = "object-tooltip-fill-none"
internal const val OBJECT_LINE_TYPE_TAG = "object-tooltip-line-type"
internal const val OBJECT_SELECT_ALL_TAG = "object-tooltip-select-all"

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

/*
 * The ink-only wrapper that used to sit here is gone. Its whole job was deriving a swatch and a
 * grouping state from a stroke list, which it could only ever do for ink — `EditorPane` now derives
 * both from the page's `CanvasSelection`, which holds either kind, and calls [ObjectTooltip] directly.
 * One bar, one call site (AD7).
 */

/**
 * The base object toolkit — `docs/diagram.md`, and `docs/plan.md` AD7.
 *
 * Colour, copy, delete: the three things that mean something for *anything* placed on the canvas.
 * Deliberately not ink's own — it takes a swatch and three callbacks rather than a stroke list, so a
 * shape raises the same bar ink does.
 *
 * **Extended, not edited.** Anything true of one kind and not another goes in [extras], between copy
 * and delete: grouping for ink, which is meaningless for a shape that is already one object, and line
 * thickness for a shape, which is meaningless for a stroke whose width is baked into its mesh. Adding
 * a kind means passing different [extras], never adding another flag here — which is what stopped
 * this growing a `canGroup` for every kind that ever arrives.
 *
 * An action a kind cannot perform is absent for it rather than shown and dead, the same rule the
 * ribbon follows for crossed-out controls.
 */
@Composable
internal fun ObjectTooltip(
    /**
     * The colour the selection is drawn in, or **null for a kind that has no colour of its own** —
     * `docs/textBoxPlan.md` TD4, and the first time anything drops a member of the base bar.
     *
     * Not a retreat from SD8's "a base plus per-kind extras, never a base with more flags". [extras]
     * is where a kind's *own* actions go; this is the base saying an action does not apply, which is
     * the rule the bar already follows for Group and for Line thickness — applied for the first time
     * to something it ships with. A `canRecolor` flag would be the wrong shape; an absent swatch says
     * it in the type.
     *
     * A text box is the case: its colour is a mark on a run (D7, the Home tab's font colour), so a
     * container-level colour would either fight the ribbon or silently restyle every run inside it.
     */
    swatch: Color?,
    selectionBoundsInView: () -> RectF?,
    viewportSize: IntSize,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onRecolor: (Int) -> Unit,
    /** The kind-specific half of the toolkit. Empty for a selection holding more than one kind. */
    extras: @Composable RowScope.() -> Unit = {},
) {
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
            // Absent, not disabled, for a kind with no colour of its own — and the divider goes
            // with it, or the bar opens on a rule with nothing to its left.
            if (swatch != null) {
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
            }

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

            extras()

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

/**
 * Ink's half of the toolkit: several strokes can be one object.
 *
 * Absent for shapes — not disabled — because a shape is already a single object and has nothing to
 * group with. `docs/diagram.md` puts grouping outside the base toolkit for exactly that reason.
 */
@Composable
internal fun RowScope.GroupAction(
    isOneGroup: Boolean,
    onGroup: () -> Unit,
    onUngroup: () -> Unit,
) {
    TextButton(
        onClick = if (isOneGroup) onUngroup else onGroup,
        contentPadding = PaddingValues(horizontal = 7.dp),
        modifier = Modifier.height(32.dp).testTag(OBJECT_GROUP_TAG),
    ) {
        Text(
            text = if (isOneGroup) "Ungroup" else "Group",
            color = Color(0xFFE8EAED),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * The TextBox's half of the toolkit — `docs/diagram.md`: *"select all : selects all text on TextBox
 * container"*, and the only action the class adds.
 *
 * A word rather than a glyph, like Group beside it: there is no Material Symbol that reads as "select
 * all" without a caption, and the bar has room for a short one.
 */
@Composable
internal fun RowScope.SelectAllAction(onSelectAll: () -> Unit) {
    TextButton(
        onClick = onSelectAll,
        contentPadding = PaddingValues(horizontal = 7.dp),
        modifier = Modifier.height(32.dp).testTag(OBJECT_SELECT_ALL_TAG),
    ) {
        Text(
            text = "Select all",
            color = Color(0xFFE8EAED),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * A shape's half of the toolkit: the border it is stroked with — `docs/diagram.md`, Shapes Class.
 *
 * **Not the same setting as the pane's Border width**, which is the user's default for the *next*
 * shape and lives in DataStore (`docs/inkPlan.md` SD4). This edits the object in the document. The two
 * look alike deliberately — it is the same [PanelSlider] — and must not be merged: one is a property
 * of the user, the other of the page, and collapsing them is a sync bug, not a refactor.
 *
 * The button previews its own value rather than carrying an icon, the way the colour button is a
 * swatch: a bar at the current width says more than a glyph, and adds no asset.
 */
@Composable
internal fun RowScope.ThicknessAction(width: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier
                .size(32.dp)
                .testTag(OBJECT_THICKNESS_TAG)
                .semantics { contentDescription = "Change border width" },
        ) {
            Box(
                Modifier
                    .width(18.dp)
                    .height(width.dp.coerceIn(1.dp, 10.dp))
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFE8EAED)),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.width(220.dp).padding(horizontal = 12.dp, vertical = 4.dp)) {
                PanelSlider(
                    field = "Border width",
                    label = "Border width",
                    value = width,
                    range = ShapeSettings.MIN_BORDER_WIDTH..ShapeSettings.MAX_BORDER_WIDTH,
                    onChange = onChange,
                )
            }
        }
    }
}

/**
 * A shape's fill — `docs/inkPlan.md` §5.4 SD7, which this is the reversal of.
 *
 * **Only for a shape with an inside.** A line, an arrow and an L have none, and the caller leaves this
 * out for them rather than showing it dead: an action a kind cannot perform is absent for it, which is
 * the rule the whole bar follows. `Outline.Shape.canFill` is what answers that.
 *
 * The button is the fill itself, as the base bar's colour button is the border — a swatch says what
 * a glyph would only label. "None" leads the palette because it is where every shape starts and the
 * one value that cannot be mixed: an absent fill is not a transparent one, and a chequer of the
 * surface behind it is how you say so without a word.
 */
@Composable
internal fun RowScope.FillAction(fill: Int?, onChange: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier
                .size(32.dp)
                .testTag(OBJECT_FILL_TAG)
                .semantics { contentDescription = "Change fill color" },
        ) {
            if (fill == null) {
                Icon(
                    MaterialSymbols.Block,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(fill)),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                TextButton(
                    onClick = {
                        open = false
                        onChange(null)
                    },
                    modifier = Modifier.testTag(OBJECT_FILL_NONE_TAG),
                ) {
                    Text("No fill", style = MaterialTheme.typography.labelLarge)
                }
            }
            INK_COLORS.chunked(5).forEach { row ->
                Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                    row.forEach { (name, argb) ->
                        Box(
                            Modifier
                                .padding(4.dp)
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(argb))
                                .semantics { contentDescription = "$name fill" }
                                .clickable {
                                    open = false
                                    onChange(argb)
                                },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A shape's line type: solid, dashed or dotted.
 *
 * The same [LineTypePicker] the Shape pane uses, for the reason [ThicknessAction] reuses the pane's
 * slider — and with the same warning attached. The pane's picker is how *you* like to draw shapes and
 * lives in DataStore (SD4); this one edits the border of the object in the document. They look alike
 * because they are the same question asked about two different things, and merging them would be a
 * sync bug rather than a refactor.
 *
 * The button draws the current type rather than naming it, which is also what the picker inside does:
 * a dashed line is a picture of itself.
 */
@Composable
internal fun RowScope.LineTypeAction(current: LineType, onChange: (LineType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier
                .size(32.dp)
                .testTag(OBJECT_LINE_TYPE_TAG)
                .semantics { contentDescription = "Change line type" },
        ) {
            Canvas(Modifier.size(width = 18.dp, height = 18.dp)) {
                val stroke = 2.dp.toPx()
                drawLine(
                    color = Color(0xFFE8EAED),
                    start = Offset(0f, center.y),
                    end = Offset(size.width, center.y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                    pathEffect = current.pathEffect(stroke),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                LineTypePicker(
                    current = current,
                    tag = ObjectLineTypeTags::lineType,
                ) {
                    open = false
                    onChange(it)
                }
            }
        }
    }
}

/** Test tags for the toolkit's line-type samples, kept off [PenPanelTags] so the two never collide. */
internal object ObjectLineTypeTags {
    fun lineType(lineType: LineType) = "object-tooltip-line-${lineType.name}"
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
