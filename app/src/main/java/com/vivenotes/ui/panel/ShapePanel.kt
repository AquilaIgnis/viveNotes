package com.vivenotes.ui.panel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vivenotes.model.ink.LineType
import com.vivenotes.data.ShapeSettings
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.ShapeTracing
import com.vivenotes.model.ink.trace
import com.vivenotes.ui.icons.MaterialSymbols
import kotlin.math.abs
import kotlin.math.sign

/** Test tags for the parts of the pane that show state without showing text. */
object ShapePanelTags {
    const val PREVIEW = "shape-preview"
    const val GRID = "shape-grid"
    /** The swatch on the Fill color row, which opens the wheel and wears the fill. */
    const val FILL = "shape-fill"

    /** The way back to an unfilled shape, inside that wheel's popup. */
    const val NO_FILL = "shape-fill-none"
    const val CUSTOM_COLOR = "shape-color-custom"

    fun kind(kind: ShapeKind) = "shape-kind-${kind.name}"
    fun page(index: Int) = "shape-page-$index"
    fun lineType(lineType: LineType) = "shape-line-${lineType.name}"
    fun color(argb: Int) = "shape-color-$argb"
}

/**
 * The Insert Shape pane, laid out as `docs/references/shapes-tooltip.jpeg` lays it out —
 * `docs/inkPlan.md` §5.4.
 *
 * **Show 3D lines** is crossed out in the reference, so it is absent entirely rather than disabled —
 * the treatment the middle pen kind gets in [PenPanelContent].
 *
 * **Fill colour** was given the other treatment, placed and inert, on the reasoning that a shape was
 * stored as ink and a stroke has no fill. That stopped being true when SD1 was reversed and a shape
 * became an object carrying `fillArgb`, so it is live as of 2026-08-06. What the reference shows is
 * the *default* — none — and that is still where a shape starts, so the screenshot and the shipped
 * behaviour continue to agree.
 *
 * It is **one swatch**, not a swatch and a row: see [FillSwatch]. Border colour keeps its row, and
 * that asymmetry is the point — the two settings are no longer two copies of the same control.
 *
 * The picker is paged because the reference is, and page 1 is inferred — only page 2 was captured.
 *
 * The border colour row shares the pens' rolling palette rather than keeping one of its own. By ID5
 * the colours someone reaches for are a property of the user, not of a tool, so a colour mixed
 * holding a pen is there when a shape is drawn — and a colour mixed for a fill joins it too.
 */
@Composable
fun ColumnScope.ShapePanelContent(
    shape: ShapeSettings,
    palette: List<Int>,
    onChange: (ShapeSettings) -> Unit,
    onAddColor: (Int) -> Unit = {},
) {
    var page by remember { mutableIntStateOf(shape.kind.page) }

    ShapePreview(shape)

    Spacer(Modifier.height(10.dp))
    ShapeGrid(
        page = page,
        current = shape.kind,
        onPick = { onChange(shape.copy(kind = it)) },
        onPage = { page = it },
    )

    Spacer(Modifier.height(6.dp))
    PageDots(current = page, count = ShapeKind.PAGE_COUNT, onPick = { page = it })

    Spacer(Modifier.height(8.dp))
    LineTypePicker(shape.lineType, ShapePanelTags::lineType) { onChange(shape.copy(lineType = it)) }

    Spacer(Modifier.height(4.dp))
    PanelSlider(
        field = "Border width",
        label = "Border width",
        value = shape.borderWidth,
        range = ShapeSettings.MIN_BORDER_WIDTH..ShapeSettings.MAX_BORDER_WIDTH,
        onChange = { onChange(shape.copy(borderWidth = it)) },
        sizePreviewColor = Color(shape.borderColorArgb),
    )

    Spacer(Modifier.height(2.dp))
    Text(
        text = "Border color",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 2.dp),
    )
    ColorSwatches(
        palette = palette,
        current = shape.borderColorArgb,
        // A tap is an explicit choice, so it must survive a later theme change.
        onPick = { onChange(shape.copy(borderColorArgb = it, colorFollowsTheme = false)) },
        onAddColor = onAddColor,
        colorTag = ShapePanelTags::color,
        customTag = ShapePanelTags.CUSTOM_COLOR,
    )

    Spacer(Modifier.height(6.dp))
    // Live since 2026-08-06 — SD7 amended. It was placed and inert on the reasoning that a shape was
    // stored as ink and a stroke has no fill, which stopped being true when SD1 was reversed. What
    // the reference actually shows is the *default*: none, which is still where a shape starts.
    //
    // One button, not a button and a row (2026-08-07, by request). The palette row that used to sit
    // under this is gone: a fill is one value and the control shows it, the way the object toolkit's
    // fill button already did. Everything the row could reach is a tap further in, on the wheel.
    PanelSetting(label = "Fill color") {
        FillSwatch(
            fill = shape.fillArgb,
            // Where the wheel opens when there is no fill to open on. The border is the only other
            // colour the shape has, and it is one the user has usually already chosen — which beats
            // a constant, and beats the old row's habit of starting wherever the palette happened
            // to begin.
            seedArgb = shape.borderColorArgb,
            onChange = { onChange(shape.copy(fillArgb = it)) },
            onAddColor = onAddColor,
        )
    }
    Spacer(Modifier.height(6.dp))
}

/**
 * The whole of Fill colour: a swatch that *is* the fill, opening the wheel.
 *
 * It replaced a 🚫 button plus a ten-swatch row. The row was the same palette the border already
 * shows directly above it, so the pane asked the same question twice and spent a third of its height
 * on the second copy; and the 🚫 could only ever say "not filled", never *what* the fill was.
 *
 * Which puts **"No fill" inside the popup** rather than beside the swatch, and it has to be
 * somewhere: a shape starts with no inside and has to be able to get back there, and the wheel has
 * no colour that means "none" — an absent fill is not a transparent one. It leads the popup for the
 * reason it used to lead the palette.
 *
 * The rolling palette (ID5) is still charged on the way out, and still only for a colour the wheel
 * was *left* on — but not when the way out was No fill, which is a rejection of whatever was mixed
 * to get there.
 */
@Composable
private fun FillSwatch(
    fill: Int?,
    seedArgb: Int,
    onChange: (Int?) -> Unit,
    onAddColor: (Int) -> Unit,
) {
    var wheelOpen by remember { mutableStateOf(false) }
    // What the wheel has been left on, as in [ColorSwatches]: null until it is touched, which is what
    // makes opening the picker and thinking better of it cost nothing.
    var mixed by remember { mutableStateOf<Int?>(null) }

    fun closeWheel() {
        wheelOpen = false
        mixed?.let(onAddColor)
        mixed = null
    }

    fun clearFill() {
        wheelOpen = false
        mixed = null
        onChange(null)
    }

    Box {
        Box(
            modifier = Modifier
                .size(28.dp)
                .testTag(ShapePanelTags.FILL)
                .clip(RoundedCornerShape(6.dp))
                .background(fill?.let(::Color) ?: Color.Transparent)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                .clickable { wheelOpen = true }
                .semantics {
                    contentDescription = if (fill == null) "No fill" else "Fill color"
                },
            contentAlignment = Alignment.Center,
        ) {
            // Only when there is nothing to show. A swatch wearing the fill says it better than a
            // glyph can, which is why the glyph is the empty case rather than the label.
            if (fill == null) {
                Icon(
                    imageVector = MaterialSymbols.Block,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Both ways out lead to the same place, as in [ColorSwatches]: Done and a tap outside are the
        // same decision, so a colour must not depend on which one closed the picker.
        FloatingSettingsPanel(
            expanded = wheelOpen,
            onDismissRequest = { closeWheel() },
            title = "Fill color",
        ) {
            NoFillEntry(selected = fill == null, onClick = { clearFill() })
            Spacer(Modifier.height(10.dp))
            ColorWheelContent(
                initialArgb = fill ?: seedArgb,
                onPick = {
                    mixed = it
                    onChange(it)
                },
                onDone = { closeWheel() },
            )
        }
    }
}

/** "No fill", which is a value rather than the absence of one — and the only way back to it. */
@Composable
private fun NoFillEntry(selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ShapePanelTags.NO_FILL)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            )
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MaterialSymbols.Block,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "No fill",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * What the armed shape will look like, drawn with the settings below it.
 *
 * Worth the space for the reason [PenPanelContent]'s stroke preview is: "border width 10" means
 * nothing until you see it.
 *
 * **A scaled-down page, not a small shape.** The shape is traced at [NOMINAL_WIDTH] × [NOMINAL_HEIGHT]
 * page units and the whole thing — geometry *and* border — is scaled to fit the box, which is the
 * same projection `Zoomed` applies to the canvas. Drawing a shrunken shape while stroking it at its
 * true page width instead is what the first version did, and at width 10 it rendered a cube as a
 * black blob: the border was a sixth of the shape rather than a twentieth. What is shown now is what
 * a shape of ordinary size on the page actually looks like.
 */
@Composable
private fun ShapePreview(shape: ShapeSettings) {
    val color = Color(shape.borderColorArgb)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(48.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .testTag(ShapePanelTags.PREVIEW),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val scale = minOf(
                size.width * 0.62f / NOMINAL_WIDTH.toPx(),
                size.height * 0.70f / NOMINAL_HEIGHT.toPx(),
            )
            val width = NOMINAL_WIDTH.toPx() * scale
            val height = NOMINAL_HEIGHT.toPx() * scale
            val left = (size.width - width) / 2f
            val top = (size.height - height) / 2f
            drawTracing(
                tracing = trace(shape.kind, left, top, left + width, top + height),
                color = color,
                width = shape.borderWidth.dp.toPx() * scale,
                lineType = shape.lineType,
                fill = shape.fillArgb?.let(::Color),
            )
        }
    }
}

/** An ordinary shape on the page, which is what the preview is a scaled view of. */
private val NOMINAL_WIDTH: Dp = 220.dp
private val NOMINAL_HEIGHT: Dp = 150.dp

/**
 * The chips, six to a row as the reference has them.
 *
 * Each one is a [Canvas] running the same [trace] the page insert runs, so a chip **is** its shape
 * rather than a picture of it — §5.4 SD6. That is also why there are no drawables here: there is no
 * Material Symbol for a wedge with dotted hidden edges, and a hand-drawn glyph per kind would be a
 * chance each to drift from the geometry it illustrates.
 *
 * **Swipe sideways to change page**, as well as tapping the dots. The dots are 8dp targets and the
 * grid is the whole width of the pane, so the gesture is by far the larger of the two — the dots
 * stay because they are what says there is a second page at all.
 */
@Composable
private fun ShapeGrid(
    page: Int,
    current: ShapeKind,
    onPick: (ShapeKind) -> Unit,
    onPage: (Int) -> Unit,
) {
    // Read by the gesture rather than captured: the handler is keyed on nothing so that a page turn
    // cannot cancel the swipe that asked for it, which means it outlives the value it needs.
    val currentPage = rememberUpdatedState(page)
    val currentOnPage = rememberUpdatedState(onPage)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ShapePanelTags.GRID)
            .pointerInput(Unit) {
                var travelled = 0f
                detectHorizontalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragEnd = {
                        // One page per swipe however far it went: the pages are a short list to be
                        // flicked through, not a strip to be scrolled along.
                        if (abs(travelled) >= SWIPE_THRESHOLD.toPx()) {
                            val next = currentPage.value - travelled.sign.toInt()
                            if (next in 0 until ShapeKind.PAGE_COUNT) currentOnPage.value(next)
                        }
                    },
                ) { change, amount ->
                    travelled += amount
                    // Taken, so the pane it sits in cannot read the same drag as a scroll.
                    change.consume()
                }
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ShapeKind.onPage(page).chunked(COLUMNS).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.forEach { kind ->
                    ShapeChip(kind = kind, selected = kind == current, onPick = { onPick(kind) })
                }
                // Keeps a short last row left-aligned under the one above instead of stretched.
                repeat(COLUMNS - row.size) { Spacer(Modifier.size(CHIP_SIZE)) }
            }
        }
    }
}

@Composable
private fun ShapeChip(kind: ShapeKind, selected: Boolean, onPick: () -> Unit) {
    val color = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val stroke = with(LocalDensity.current) { CHIP_STROKE.toPx() }
    val inset = with(LocalDensity.current) { CHIP_INSET.toPx() }
    val extent = with(LocalDensity.current) { CHIP_SIZE.toPx() } - inset * 2f
    // Traced once per kind and size rather than on every draw: the grid redraws whenever the
    // selection moves, and re-deriving every shape on the page for a highlight change is work for
    // nothing.
    val tracing = remember(kind, extent) { trace(kind, inset, inset, inset + extent, inset + extent) }

    Box(
        modifier = Modifier
            .size(CHIP_SIZE)
            .testTag(ShapePanelTags.kind(kind))
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            )
            .selectable(selected = selected, onClick = onPick)
            .semantics {
                contentDescription = kind.label
                this.selected = selected
            },
    ) {
        Canvas(Modifier.size(CHIP_SIZE)) {
            drawTracing(tracing, color, stroke, LineType.Solid)
        }
    }
}

/** Which page of the picker is showing. Two dots, exactly as the reference draws them. */
@Composable
private fun PageDots(current: Int, count: Int, onPick: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val active = index == current
            Box(
                modifier = Modifier
                    .padding(horizontal = 5.dp)
                    .size(if (active) 9.dp else 8.dp)
                    .testTag(ShapePanelTags.page(index))
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    )
                    .clickable { onPick(index) }
                    .semantics {
                        contentDescription = "Shape page ${index + 1}"
                        selected = active
                    },
            )
        }
    }
}

/**
 * Draws a whole tracing: the visible edges in [lineType], the occluded ones always dotted.
 *
 * The one place the pane and the page have to agree, so it takes the tracing rather than the kind —
 * whatever was traced is what gets drawn, at chip size and at page size alike.
 */
internal fun DrawScope.drawTracing(
    tracing: ShapeTracing,
    color: Color,
    width: Float,
    lineType: LineType,
    /** The inside, painted first so the border is never half-covered by the paint it surrounds. */
    fill: Color? = null,
) {
    if (fill != null) {
        tracing.fill.forEach { region ->
            if (region.size >= 6) {
                drawPath(
                    Path().apply {
                        moveTo(region[0], region[1])
                        for (index in 2 until region.size step 2) lineTo(region[index], region[index + 1])
                        close()
                    },
                    fill,
                )
            }
        }
    }
    tracing.solid.forEach { drawPolyline(it, color, width, lineType.pathEffect(width)) }
    tracing.hidden.forEach { drawPolyline(it, color, width, LineType.Dotted.pathEffect(width)) }
}

private fun DrawScope.drawPolyline(
    points: FloatArray,
    color: Color,
    width: Float,
    effect: PathEffect?,
) {
    if (points.size < 4) return
    val path = Path().apply {
        moveTo(points[0], points[1])
        for (index in 2 until points.size step 2) lineTo(points[index], points[index + 1])
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = width,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = effect,
        ),
    )
}


/** Six to a row, which is what the reference shows and what fits the 320dp panel. */
private const val COLUMNS = 6

/**
 * Far enough that a tap that wandered is not a page turn, short enough to flick.
 *
 * Measured *after* touch slop, which the drag detector eats before reporting anything — so the
 * finger travels this plus about 8dp, and a threshold set by eye against the panel's width comes
 * out longer than it looks.
 */
private val SWIPE_THRESHOLD: Dp = 28.dp

private val CHIP_SIZE: Dp = 44.dp
private val CHIP_INSET: Dp = 9.dp
private val CHIP_STROKE: Dp = 1.6.dp
