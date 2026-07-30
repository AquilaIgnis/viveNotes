package st.unamedtba.ui.editor

import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.Flow
import st.unamedtba.model.Block
import st.unamedtba.richtext.EditorStyle
import st.unamedtba.richtext.FormatCommand
import st.unamedtba.richtext.OutlineEditText
import st.unamedtba.richtext.SelectionState
import st.unamedtba.ui.OutlineBox
import st.unamedtba.ui.theme.LocalCanvasColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CANVAS_MIN_HEIGHT = 700.dp
private val CANVAS_MAX_HEIGHT = 20_000.dp
private val CANVAS_TRAILING_SPACE = 320.dp
private val GRIP_HEIGHT = 18.dp
private val RESIZE_HANDLE_WIDTH = 18.dp

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
    onTitleChange: (String) -> Unit,
    outlines: List<OutlineBox>,
    pageRevision: Int,
    initialBlocksFor: (String) -> List<Block>,
    commands: Flow<FormatCommand>,
    onBlocksChanged: (String, List<Block>) -> Unit,
    onSelectionChanged: (SelectionState) -> Unit,
    onCreateOutline: (Float, Float) -> String,
    onMoveOutline: (String, Float, Float) -> Unit,
    onResizeOutline: (String, Float) -> Unit,
    onSetOutlineMinHeight: (String, Float) -> Unit,
    onOutlineBlurred: (String) -> Unit,
    showRuleLines: Boolean,
    modifier: Modifier = Modifier,
) {
    val canvas = LocalCanvasColors.current
    val density = LocalDensity.current
    val primary = MaterialTheme.colorScheme.primary

    var focusedEditor by remember { mutableStateOf<OutlineEditText?>(null) }
    var focusedOutlineId by remember { mutableStateOf<String?>(null) }
    /** Container to grab focus once composed — the one the user just created by tapping. */
    var pendingFocusId by remember { mutableStateOf<String?>(null) }
    val heights = remember { mutableStateMapOf<String, Int>() }

    val style = remember(density, primary) {
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
        commands.collect { command -> focusedEditor?.apply(command) }
    }

    val lowestContent = outlines.maxOfOrNull { box ->
        box.y.dp + with(density) { (heights[box.id] ?: 0).toDp() }
    } ?: 0.dp
    // Clamped as a backstop: the canvas is sized from its children's measured heights, so any
    // child that sizes itself from the canvas would otherwise grow without bound until the
    // height overflows what Constraints can pack.
    val canvasHeight = (lowestContent + CANVAS_TRAILING_SPACE)
        .coerceIn(CANVAS_MIN_HEIGHT, CANVAS_MAX_HEIGHT)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(canvas.background),
    ) {
        if (showRuleLines) {
            RuleLines(color = canvas.ruleLine)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            PageHeader(
                title = title,
                createdAt = createdAt,
                onTitleChange = onTitleChange,
                modifier = Modifier.padding(start = 40.dp, end = 40.dp, top = 24.dp),
            )

            Spacer(Modifier.height(16.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(canvasHeight),
            ) {
                // Placed first so it sits beneath the containers: taps that land on a container
                // reach its editor, and only taps on bare canvas create a new one.
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(pageRevision) {
                            detectTapGestures { offset ->
                                val x = with(density) { offset.x.toDp().value }
                                val y = with(density) { offset.y.toDp().value }
                                pendingFocusId = onCreateOutline(x - 8f, y - 8f)
                            }
                        },
                )

                outlines.forEach { box ->
                    key(pageRevision, box.id) {
                        OutlineContainer(
                            box = box,
                            initialBlocks = initialBlocksFor(box.id),
                            editorStyle = style,
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

/** Test tags for the container's drag targets. */
internal object OutlineTags {
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
    focused: Boolean,
    requestFocus: Boolean,
    onFocusHandled: () -> Unit,
    onFocused: (OutlineEditText) -> Unit,
    onBlurred: () -> Unit,
    onBlocksChanged: (List<Block>) -> Unit,
    onSelectionChanged: (SelectionState) -> Unit,
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
            .onSizeChanged { onMeasured(it.height) },
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
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        setLineSpacing(0f, 1.25f)
                        typeface = Typeface.SANS_SERIF
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

/** The ruled/grid page background from the reference UI. */
@Composable
private fun RuleLines(color: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val step: Dp = 26.dp
        val stepPx = step.toPx()
        var y = stepPx
        while (y < size.height) {
            drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += stepPx
        }
        var x = stepPx
        while (x < size.width) {
            drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
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
