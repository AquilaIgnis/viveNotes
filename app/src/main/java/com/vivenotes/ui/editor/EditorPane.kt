package com.vivenotes.ui.editor

import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.Flow
import com.vivenotes.data.EditorDefaults
import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.model.PageStyle
import com.vivenotes.model.PrintMargins
import com.vivenotes.model.RuleLines
import com.vivenotes.richtext.EditorStyle
import com.vivenotes.richtext.FormatCommand
import com.vivenotes.richtext.OutlineEditText
import com.vivenotes.richtext.SelectionState
import com.vivenotes.ui.OutlineBox
import com.vivenotes.ui.theme.CanvasColors
import com.vivenotes.ui.theme.LocalCanvasColors
import com.vivenotes.ui.theme.paintedWith
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

private val CANVAS_MIN_HEIGHT = 700.dp
private val CANVAS_TRAILING_SPACE = 320.dp

/** Blank canvas kept to the right of the widest container, so there is room to start another. */
private val CANVAS_TRAILING_WIDTH = 200.dp
private val CANVAS_MIN_WIDTH = 720.dp
private val GRIP_HEIGHT = 18.dp
private val RESIZE_HANDLE_WIDTH = 18.dp

/**
 * Ceilings imposed by [Constraints], which packs both axes into a single Int.
 *
 * The allowance on one axis depends on the other: while one stays under [CONSTRAINT_NARROW_PX] the
 * other may reach [CONSTRAINT_SOLE_PX], and past that both drop to [CONSTRAINT_PAIRED_PX]. A page
 * that grows as you scroll will find that edge eventually, and the old fixed 20,000dp height was
 * already only legal because the canvas happened to stay narrow — a wide sheet carrying tall content
 * would have thrown at layout time.
 */
private const val CONSTRAINT_NARROW_PX = 8_191
private const val CONSTRAINT_SOLE_PX = 262_143
private const val CONSTRAINT_PAIRED_PX = 32_767

/**
 * The largest canvas that can actually be laid out, at this zoom.
 *
 * Zoom belongs in the arithmetic because [Zoomed] reports the *scaled* size to its parent, so it is
 * the scaled size that has to be packable — a canvas that is legal at 100% can overflow at 400%.
 */
private fun Density.clampToConstraints(size: DpSize, zoom: Float): DpSize {
    fun limit(dp: Dp, ceiling: Int): Dp = minOf(dp, (ceiling / zoom).toDp())

    val width = limit(size.width, CONSTRAINT_PAIRED_PX)
    val widthPx = width.toPx() * zoom
    val heightCeiling = if (widthPx <= CONSTRAINT_NARROW_PX) CONSTRAINT_SOLE_PX else CONSTRAINT_PAIRED_PX
    return DpSize(width, limit(size.height, heightCeiling))
}

/**
 * The page canvas: title, timestamp, ruled background, and free-form text containers.
 *
 * A page is not one linear document. Text lives in independently positioned containers
 * ("outlines"): tapping empty canvas starts a new one, and each can be dragged and resized.
 * Each container is an [OutlineEditText] hosted through [AndroidView] — the one place the Compose
 * shell hands off to a View, so that span-based editing, IME handling, selection UI and
 * accessibility come from the platform rather than being reimplemented.
 */
@Composable
fun EditorPane(
    title: String,
    createdAt: Long,
    defaults: EditorDefaults,
    /** The page's own appearance, from the View tab. */
    style: PageStyle,
    zoom: Float,
    onTitleChange: (String) -> Unit,
    outlines: List<OutlineBox>,
    pageRevision: Int,
    initialBlocksFor: (String) -> List<Block>,
    commands: Flow<FormatCommand>,
    onBlocksChanged: (String, List<Block>) -> Unit,
    onSelectionChanged: (SelectionState) -> Unit,
    /** A mark applied with no selection — the editor's new default, not an edit. */
    onMarkArmed: (Mark) -> Unit,
    onCreateOutline: (Float, Float) -> String,
    onMoveOutline: (String, Float, Float) -> Unit,
    onResizeOutline: (String, Float) -> Unit,
    onSetOutlineMinHeight: (String, Float) -> Unit,
    onOutlineBlurred: (String) -> Unit,
    /** Window width and page width in dp, which is all Zoom to Page Width needs. */
    onCanvasMeasured: (Float, Float) -> Unit,
    /** Drawn while the Paper Size pane is open, so the margins being edited are visible. */
    showPrintMargins: Boolean,
    modifier: Modifier = Modifier,
) {
    val shell = LocalCanvasColors.current
    val canvas = remember(shell, style.backgroundArgb) { shell.paintedWith(style.backgroundArgb) }
    val density = LocalDensity.current
    val primary = MaterialTheme.colorScheme.primary

    var focusedEditor by remember { mutableStateOf<OutlineEditText?>(null) }
    var focusedOutlineId by remember { mutableStateOf<String?>(null) }
    /** Container to grab focus once composed — the one the user just created by tapping. */
    var pendingFocusId by remember { mutableStateOf<String?>(null) }
    val heights = remember { mutableStateMapOf<String, Int>() }

    val editorStyle = remember(density, primary) {
        with(density) {
            EditorStyle(
                indentStepPx = 28.dp.roundToPx(),
                listGapPx = 34.dp.roundToPx(),
                bulletRadiusPx = 3.dp.roundToPx(),
                accentColor = primary.toArgb(),
                codeBackgroundColor = Color.White.copy(alpha = 0.07f).toArgb(),
                quoteColor = primary.toArgb(),
            )
        }
    }

    // Commands are one-shot events, so they are collected rather than read from state —
    // replaying them on recomposition would re-apply formatting.
    LaunchedEffect(Unit) {
        commands.collect { command ->
            val editor = focusedEditor
            if (editor != null) {
                editor.apply(command)
            } else if (command is FormatCommand.SetMark) {
                // Nothing focused, so there is nothing to format — but choosing a font or size
                // from the ribbon still says what the next text should look like.
                onMarkArmed(command.mark)
            }
        }
    }

    val sheet = style.pageSizeDp?.let { (w, h) -> DpSize(w.dp, h.dp) }

    // What the content occupies, measured from the page's own top-left corner and including the
    // band the title sits in. Derived rather than recomputed: the canvas now grows as the user
    // scrolls, so this is read on far more recompositions than it used to be.
    val contentBounds by remember(outlines, style.hideTitle, density) {
        derivedStateOf {
            var width = 0.dp
            var height = if (style.hideTitle) 0.dp else PageStyle.TITLE_BAND_DP.dp
            outlines.forEach { box ->
                width = maxOf(width, (box.x + box.width).dp)
                height = maxOf(height, box.y.dp + with(density) { (heights[box.id] ?: 0).toDp() })
            }
            DpSize(width, height)
        }
    }

    // A chosen paper size binds the page only while the page can hold the content. Once something
    // sits outside it, clipping to it would hide the user's work, so the sheet steps back to being
    // a guide and the canvas covers the content instead. Live state, so dragging a container over
    // the edge and back flips it both ways.
    val fits = sheet != null &&
        contentBounds.width <= sheet.width && contentBounds.height <= sheet.height

    // What the page *is*, as opposed to how much of it can be scrolled: the sheet when one holds
    // the content, otherwise whatever the content needs. This is what Zoom to Page Width fits.
    val pageSize = if (fits) {
        sheet!!
    } else {
        DpSize(
            maxOf(contentBounds.width, sheet?.width ?: 0.dp, CANVAS_MIN_WIDTH),
            maxOf(contentBounds.height, sheet?.height ?: 0.dp, CANVAS_MIN_HEIGHT),
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // Only a page bound by a sheet has an outside. One the content has outgrown is canvas
            // all the way to its edge, and painting a surround there would say otherwise.
            .background(if (fits) MaterialTheme.colorScheme.surfaceContainer else canvas.background),
    ) {
        val horizontal = rememberScrollState()
        val vertical = rememberScrollState()
        // The window onto the page, in page units: what the user can see at this zoom.
        val window = DpSize(maxWidth / zoom, maxHeight / zoom)

        // How far the canvas has been extended to meet the user, counted in whole screenfuls.
        //
        // A high-water mark, so scrolling back does not shrink the canvas out from under the
        // scroll position, and quantised to the screen so this changes about once per screenful
        // rather than once per frame — the difference between one recomposition and sixty.
        var reachedX by remember(pageRevision) { mutableIntStateOf(0) }
        var reachedY by remember(pageRevision) { mutableIntStateOf(0) }
        LaunchedEffect(pageRevision, window, density) {
            snapshotFlow {
                val across = with(density) { window.width.toPx() }
                val down = with(density) { window.height.toPx() }
                val x = if (across > 0f) ((horizontal.value / zoom + across) / across).toInt() else 0
                val y = if (down > 0f) ((vertical.value / zoom + down) / down).toInt() else 0
                x to y
            }.collect { (x, y) ->
                if (x > reachedX) reachedX = x
                if (y > reachedY) reachedY = y
            }
        }

        // A bound page stops at its sheet plus a surround — that is what choosing a size buys. An
        // unbounded one always keeps a screenful in front of wherever the user has got to, which is
        // what makes it feel like it has no end.
        val room = DpSize(pageSize.width + CANVAS_TRAILING_WIDTH, pageSize.height + CANVAS_TRAILING_SPACE)
        val canvasSize = density.clampToConstraints(
            if (fits) {
                DpSize(maxOf(room.width, window.width), maxOf(room.height, window.height))
            } else {
                DpSize(
                    maxOf(room.width, window.width * (reachedX + 1)),
                    maxOf(room.height, window.height * (reachedY + 1)),
                )
            },
            zoom,
        )

        // Reported rather than derived by the caller: these are layout facts, known here and
        // nowhere else. Writing them costs nothing and nothing recomposes from them.
        SideEffect { onCanvasMeasured(maxWidth.value, pageSize.width.value) }

        // Read while drawing rather than while composing, so scrolling re-runs the ruling's draw
        // and nothing above it. Given as a lambda for exactly that reason — calling it here would
        // subscribe the whole page to every scrolled pixel.
        val visibleWindow: () -> Rect = {
            val left = horizontal.value / zoom
            val top = vertical.value / zoom
            with(density) {
                Rect(left, top, left + window.width.toPx(), top + window.height.toPx())
            }
        }

        // Provided so containers and the title read the page's colours, which may be nothing like
        // the shell's — a white page inside a dark app, or a page painted from the Page Color menu.
        CompositionLocalProvider(LocalCanvasColors provides canvas) {
            PageViewport(zoom, horizontal, vertical) {
                // One coordinate space. An outline's stored (x, y) is measured from this corner, and
                // so are the sheet, the ruling and the margin guides drawn behind it — before, the
                // content sat in a box below the title and the two disagreed by the height of the
                // header, which is why the guides never lined up with anything.
                Box(
                    Modifier
                        .size(canvasSize)
                        .testTag(PageTags.CANVAS),
                ) {
                    PageSurface(
                        sheet = sheet,
                        bound = fits,
                        colors = canvas,
                        ruleLines = style.ruleLines,
                        margins = style.margins.takeIf { showPrintMargins },
                        window = visibleWindow,
                    )

                    // Placed above the surface but beneath the containers: taps that land on a
                    // container reach its editor, and only taps on bare canvas create a new one.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(pageRevision, fits, pageSize, style.hideTitle) {
                                detectTapGestures { offset ->
                                    val x = with(density) { offset.x.toDp() }
                                    val y = with(density) { offset.y.toDp() }
                                    // A bound page has edges: there is no page outside the sheet to
                                    // put anything on, and while it is bound the sheet *is* the
                                    // page. The title owns the band at the top.
                                    val offSheet = fits && (x > pageSize.width || y > pageSize.height)
                                    val onTitle = !style.hideTitle && y < PageStyle.TITLE_BAND_DP.dp
                                    if (offSheet || onTitle) return@detectTapGestures
                                    pendingFocusId = onCreateOutline(x.value - 8f, y.value - 8f)
                                }
                            },
                    )

                    if (!style.hideTitle) {
                        PageHeader(
                            title = title,
                            createdAt = createdAt,
                            onTitleChange = onTitleChange,
                            modifier = Modifier
                                .width(pageSize.width)
                                .padding(start = 40.dp, end = 40.dp, top = 24.dp),
                        )
                    }

                    outlines.forEach { box ->
                        key(pageRevision, box.id) {
                            OutlineContainer(
                                box = box,
                                initialBlocks = initialBlocksFor(box.id),
                                editorStyle = editorStyle,
                                defaults = defaults,
                                focused = focusedOutlineId == box.id,
                                requestFocus = pendingFocusId == box.id,
                                onFocusHandled = { pendingFocusId = null },
                                onFocused = { view ->
                                    focusedEditor = view
                                    focusedOutlineId = box.id
                                },
                                onBlurred = {
                                    if (focusedOutlineId == box.id) {
                                        focusedOutlineId = null
                                        focusedEditor = null
                                    }
                                    onOutlineBlurred(box.id)
                                },
                                onBlocksChanged = { blocks -> onBlocksChanged(box.id, blocks) },
                                onSelectionChanged = onSelectionChanged,
                                onMarkArmed = onMarkArmed,
                                onMove = { x, y -> onMoveOutline(box.id, x, y) },
                                onResize = { width -> onResizeOutline(box.id, width) },
                                onSetMinHeight = { height -> onSetOutlineMinHeight(box.id, height) },
                                onMeasured = { heightPx -> heights[box.id] = heightPx },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The window onto the page: it pans in both directions and scales.
 *
 * Both axes scroll because a page has a size of its own — a sheet, or whatever the containers
 * occupy — and at any zoom above what fits, the rest of it still has to be reachable.
 */
@Composable
private fun PageViewport(
    zoom: Float,
    horizontal: ScrollState,
    vertical: ScrollState,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(vertical),
    ) {
        Box(Modifier.horizontalScroll(horizontal)) {
            Zoomed(zoom, content)
        }
    }
}

/**
 * The writable page: its colour, its ruling, and the marks describing the sheet.
 *
 * Drawn behind the content rather than as a background on the same box, because a page bound by a
 * sheet is a rectangle of a fixed size sitting on a larger canvas — the ruling stops at the paper's
 * edge and the area beyond has to read as "off the page".
 *
 * A page the content has outgrown is the other case: every part of it is writable, so it is ruled
 * to its own edge and the sheet is reduced to a dashed outline saying where it would have ended.
 * [PageTags.SURFACE] is therefore the writable area, which is the sheet exactly when [bound].
 */
@Composable
private fun BoxScope.PageSurface(
    sheet: DpSize?,
    bound: Boolean,
    colors: CanvasColors,
    ruleLines: RuleLines,
    margins: PrintMargins?,
    window: () -> Rect,
) {
    Box(
        Modifier
            .then(if (bound && sheet != null) Modifier.size(sheet) else Modifier.matchParentSize())
            .background(colors.background)
            .then(if (bound && sheet != null) Modifier.border(1.dp, colors.ruleLine) else Modifier)
            .testTag(PageTags.SURFACE),
    ) {
        if (ruleLines != RuleLines.None) {
            PageRuling(color = colors.ruleLine, rules = ruleLines, window = window)
        }
    }

    if (sheet != null) {
        // Anchored to the page's corner rather than drawn inside the surface, so they still describe
        // the sheet on a page whose writable area has grown past it.
        Box(Modifier.size(sheet)) {
            if (!bound) SheetEdgeGuide(colors.secondaryText)
            // Nothing prints yet, so the guides are the only thing that makes a margin setting
            // observable. They are drawn only while the pane that edits them is open.
            if (margins != null) MarginGuides(margins, colors.secondaryText)
        }
    }
}

/**
 * Where the chosen sheet ends, on a page whose content has spilled past it.
 *
 * One rectangle, not a run of page breaks: it says "this is where A4 stops", which is the question
 * choosing a size asks. Nothing is clipped and the content past it is as writable as the rest.
 */
@Composable
private fun SheetEdgeGuide(color: Color) {
    Box(
        Modifier
            .fillMaxSize()
            .testTag(PageTags.SHEET_GUIDE)
            .drawBehind {
                drawRect(
                    color = color,
                    style = Stroke(
                        width = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 9f)),
                    ),
                )
            },
    )
}

/** Dashed rules where the printable area would begin. */
@Composable
private fun MarginGuides(margins: PrintMargins, color: Color) {
    Canvas(Modifier.fillMaxSize()) {
        // The page is laid out at a known dp-per-inch, so a margin in inches is a distance on it.
        fun inches(value: Float) = (value * PageStyle.DP_PER_INCH).dp.toPx()

        val inset = Rect(
            left = inches(margins.leftInches),
            top = inches(margins.topInches),
            right = size.width - inches(margins.rightInches),
            bottom = size.height - inches(margins.bottomInches),
        )
        // Margins wider than the sheet leave no printable area; there is nothing to draw, and the
        // field is already flagged as out of range.
        if (inset.width <= 0f || inset.height <= 0f) return@Canvas

        drawRect(
            color = color.copy(alpha = 0.7f),
            topLeft = inset.topLeft,
            size = inset.size,
            style = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))),
        )
    }
}

/**
 * Scales the page.
 *
 * `graphicsLayer` on its own would scale what is drawn but not the space it takes up, so a zoomed
 * page would be clipped to its old bounds and the rest could never be scrolled to. Measuring the
 * content unbounded and reporting its *scaled* size instead makes zoom a layout fact, which is what
 * the surrounding scroll containers need to see. The content keeps its own coordinate system, so
 * taps and drags inside it need no conversion.
 */
@Composable
private fun Zoomed(zoom: Float, content: @Composable () -> Unit) {
    Layout(content = content) { measurables, _ ->
        val placeable = measurables.first().measure(Constraints())
        layout((placeable.width * zoom).roundToInt(), (placeable.height * zoom).roundToInt()) {
            placeable.placeWithLayer(0, 0) {
                scaleX = zoom
                scaleY = zoom
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

/**
 * The default expressed as marks, empty where it already matches the editor's fixed base.
 *
 * Skipping the matching case is what keeps documents clean for anyone who never changes the
 * setting: their text renders from the base and carries no font marks at all. Because that base is
 * a constant, such text also stays put forever, whatever the default becomes later.
 */
private fun EditorDefaults.asMarks(): Set<Mark> = buildSet {
    if (fontFamily != EditorDefaults.FALLBACK_FONT_FAMILY) add(Mark.FontFamily(fontFamily))
    if (fontSize != EditorDefaults.FALLBACK_FONT_SIZE) add(Mark.FontSize(fontSize))
}

/** Test tags for the page itself, whose geometry the View tab changes. */
internal object PageTags {
    /** The writable area — the sheet exactly when the content still fits inside one. */
    const val SURFACE = "page-surface"

    /** The dashed outline of a sheet the content has outgrown; absent while it still fits. */
    const val SHEET_GUIDE = "sheet-edge-guide"

    /** Everything that can be scrolled to, which on an unbounded page grows as you scroll. */
    const val CANVAS = "page-canvas"
}

/** Test tags for the container's drag targets. */
internal object OutlineTags {
    /** The container's own box, whose top-left *is* the outline's stored coordinate. */
    const val CONTAINER = "outline-container"
    const val MOVE = "outline-move-handle"
    const val RESIZE_WIDTH = "outline-width-handle"
    const val RESIZE_HEIGHT = "outline-height-handle"
    const val EDITOR = "outline-editor"
}

@Composable
internal fun OutlineContainer(
    box: OutlineBox,
    initialBlocks: List<Block>,
    editorStyle: EditorStyle,
    /** Defaulted so the container can be exercised in isolation without a preferences store. */
    defaults: EditorDefaults = EditorDefaults(),
    focused: Boolean,
    requestFocus: Boolean,
    onFocusHandled: () -> Unit,
    onFocused: (OutlineEditText) -> Unit,
    onBlurred: () -> Unit,
    onBlocksChanged: (List<Block>) -> Unit,
    onSelectionChanged: (SelectionState) -> Unit,
    onMarkArmed: (Mark) -> Unit = {},
    onMove: (Float, Float) -> Unit,
    onResize: (Float) -> Unit,
    onSetMinHeight: (Float) -> Unit,
    onMeasured: (Int) -> Unit,
) {
    val canvas = LocalCanvasColors.current
    val density = LocalDensity.current
    val outline = MaterialTheme.colorScheme.primary

    // Drag callbacks live for the lifetime of the pointerInput, so they must read current
    // geometry rather than the values captured when the gesture started.
    val current by rememberUpdatedState(box)
    var view by remember { mutableStateOf<OutlineEditText?>(null) }
    var hasContent by remember { mutableStateOf(initialBlocks.any { it.text.isNotBlank() }) }
    /** Height of the text area alone, used as the baseline the first time it is dragged. */
    var textHeightDp by remember { mutableStateOf(0f) }

    // An empty container is just a caret position, so it shows no chrome at all. Otherwise
    // tapping around the page would litter it with empty rectangles.
    val showChrome = focused && hasContent

    LaunchedEffect(requestFocus, view) {
        if (requestFocus && view != null) {
            view?.requestFocus()
            onFocusHandled()
        }
    }

    Box(
        modifier = Modifier
            .offset(x = box.x.dp, y = box.y.dp)
            .width(box.width.dp + RESIZE_HANDLE_WIDTH)
            .onSizeChanged { onMeasured(it.height) }
            .testTag(OutlineTags.CONTAINER),
    ) {
        Column(Modifier.width(box.width.dp)) {
            // Reserved strip for the move grip. It cannot be floated above the container with a
            // negative offset: a container at the top of the page would put its grip off-canvas
            // where it can never be grabbed.
            Spacer(Modifier.height(GRIP_HEIGHT))

            Box(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged {
                        textHeightDp = with(density) { it.height.toDp().value }
                    },
            ) {
                AndroidView(
                factory = { context ->
                    OutlineEditText(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        background = null
                        setPadding(0, 0, 0, 0)
                        // Fixed, never the current default: this is what every character with no
                        // span of its own renders as, so changing it would restyle old writing.
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, EditorDefaults.FALLBACK_FONT_SIZE.toFloat())
                        setLineSpacing(0f, 1.25f)
                        typeface = Typeface.SANS_SERIF
                        // Named here because a Typeface cannot be mapped back to an id, and the
                        // ribbon has to be able to say what unmarked text is written in.
                        baseFontFamily = EditorDefaults.FALLBACK_FONT_FAMILY
                        gravity = android.view.Gravity.TOP or android.view.Gravity.START
                        inputType = InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        isVerticalScrollBarEnabled = false
                        maxLines = Int.MAX_VALUE
                        // Qualified because the composable's parameters of the same name would
                        // otherwise shadow the view's properties inside apply.
                        this@apply.editorStyle = editorStyle
                        this@apply.onBlocksChanged = { blocks ->
                            hasContent = blocks.any { it.text.isNotBlank() }
                            onBlocksChanged(blocks)
                        }
                        this@apply.onSelectionStateChanged = { state -> onSelectionChanged(state) }
                        this@apply.onMarkArmed = { mark -> onMarkArmed(mark) }
                        setOnFocusChangeListener { v, hasFocus ->
                            if (hasFocus) onFocused(v as OutlineEditText) else onBlurred()
                        }
                        setBlocks(initialBlocks)
                        view = this
                    }
                },
                update = { editor ->
                    editor.editorStyle = editorStyle
                    editor.setTextColor(canvas.text.toArgb())
                    editor.setHintTextColor(canvas.secondaryText.toArgb())
                    editor.defaultMarks = defaults.asMarks()
                    // Applied to the view rather than as a Compose height constraint: constraining
                    // the wrapper only grows the box around the editor, leaving the text area
                    // itself — and its touch target — at the height of its content.
                    editor.minimumHeight = with(density) { box.minHeight.dp.roundToPx() }
                },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(OutlineTags.EDITOR)
                        // Transparent rather than absent so showing the border never shifts text.
                        .border(
                            width = 1.dp,
                            color = if (showChrome) outline.copy(alpha = 0.55f) else Color.Transparent,
                        )
                        .padding(6.dp),
                )
            }

            // Reserved strip for the bottom resize handle, mirroring the spare width kept on the
            // right. The handle cannot simply be overlaid on the editor: that is a real Android
            // View and it consumes the touch, so the drag gesture would never fire. Always
            // present, so focusing a container does not shift it.
            Spacer(Modifier.height(RESIZE_HANDLE_WIDTH))
        }

        if (showChrome) {
            // All three handles are overlays inside a matchParentSize box rather than children of
            // the Column.
            //
            // matchParentSize, not fillMaxHeight: the container's height comes from its children
            // and the canvas's height comes from the container, so a child that sizes itself from
            // the incoming height constraint feeds back into the canvas and grows every frame.
            // matchParentSize measures against the parent without contributing to it. Overlaying
            // also means chrome appearing on focus never reflows the text.
            Box(modifier = Modifier.matchParentSize()) {

                // Move: a grip bar floated above the container's top edge.
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .width(box.width.dp)
                        .height(GRIP_HEIGHT)
                        .testTag(OutlineTags.MOVE)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(outline.copy(alpha = 0.24f))
                        .pointerInput(box.id) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val dx = with(density) { dragAmount.x.toDp().value }
                                val dy = with(density) { dragAmount.y.toDp().value }
                                onMove(current.x + dx, current.y + dy)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(32.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(outline.copy(alpha = 0.85f)),
                    )
                }

                // Width: right edge.
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .width(RESIZE_HANDLE_WIDTH)
                        .fillMaxHeight()
                        .testTag(OutlineTags.RESIZE_WIDTH)
                        .pointerInput(box.id) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val dx = with(density) { dragAmount.x.toDp().value }
                                onResize(current.width + dx)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(4.dp)
                            .height(34.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(outline.copy(alpha = 0.85f)),
                    )
                }

                // Height: bottom edge. Sets a floor rather than a fixed height, so dragging it
                // shorter than the text can never clip what the user wrote.
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .width(box.width.dp)
                        .height(RESIZE_HANDLE_WIDTH)
                        .testTag(OutlineTags.RESIZE_HEIGHT)
                        .pointerInput(box.id) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val dy = with(density) { dragAmount.y.toDp().value }
                                // Start from the height the text actually occupies, so the first
                                // drag frame continues from where the box is rather than from 0.
                                val base = if (current.minHeight > 0f) current.minHeight else textHeightDp
                                onSetMinHeight(base + dy)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(34.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(outline.copy(alpha = 0.85f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun PageHeader(
    title: String,
    createdAt: Long,
    onTitleChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val canvas = LocalCanvasColors.current
    Column(modifier) {
        BasicTextField(
            value = title,
            onValueChange = onTitleChange,
            textStyle = TextStyle(
                color = canvas.text,
                fontSize = 26.sp,
                fontWeight = FontWeight.Normal,
            ),
            cursorBrush = SolidColor(canvas.text),
            singleLine = true,
            decorationBox = { inner ->
                Box {
                    if (title.isEmpty()) {
                        Text(
                            text = "Untitled page",
                            style = LocalTextStyle.current.copy(
                                color = canvas.secondaryText,
                                fontSize = 26.sp,
                            ),
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 900.dp),
        )

        Spacer(Modifier.height(2.dp))
        Box(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(canvas.ruleLine),
        )
        Spacer(Modifier.height(6.dp))

        Text(
            text = formatCreated(createdAt),
            style = MaterialTheme.typography.bodySmall,
            color = canvas.secondaryText,
        )
    }
}

/**
 * The ruled or squared page background from the reference UI.
 *
 * Spacing comes from the document, so a page ruled on a tablet is ruled identically on a phone —
 * the lines are part of the page, not of the window it is being read in.
 *
 * Only the lines the window can show are drawn. Ruling the whole page instead cost one line per
 * step of its *height*, which on a page that extends as you scroll has no upper bound; this is
 * bounded by the screen instead, whatever the page has grown to. [window] is called inside the draw
 * scope on purpose — that is what keeps the scroll position off the composition path, so scrolling
 * re-runs this lambda and nothing above it.
 */
@Composable
private fun PageRuling(color: Color, rules: RuleLines, window: () -> Rect) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val stepPx = rules.spacingDp.dp.toPx()
        if (stepPx <= 0f) return@Canvas
        val visible = window().intersect(Rect(Offset.Zero, size))
        if (visible.isEmpty) return@Canvas

        // Snapped to the ruling's own grid, not to the window, so the lines stay where the page puts
        // them however far it has been scrolled. The first line is a whole step in, as it always was.
        var y = maxOf(stepPx, ceil(visible.top / stepPx) * stepPx)
        while (y < visible.bottom) {
            drawLine(color, Offset(visible.left, y), Offset(visible.right, y), strokeWidth = 1f)
            y += stepPx
        }
        if (!rules.squared) return@Canvas
        var x = maxOf(stepPx, ceil(visible.left / stepPx) * stepPx)
        while (x < visible.right) {
            drawLine(color, Offset(x, visible.top), Offset(x, visible.bottom), strokeWidth = 1f)
            x += stepPx
        }
    }
}

private val createdFormat = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
private val createdTimeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

private fun formatCreated(timestamp: Long): String {
    if (timestamp == 0L) return ""
    val date = Date(timestamp)
    return "${createdFormat.format(date)}    ${createdTimeFormat.format(date)}"
}
