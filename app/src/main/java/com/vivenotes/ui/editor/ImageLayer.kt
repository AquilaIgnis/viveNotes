package com.vivenotes.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vivenotes.data.AttachmentStore
import com.vivenotes.ink.CanvasSelection
import com.vivenotes.ink.InkPoint
import com.vivenotes.ink.PageBounds
import com.vivenotes.ink.pageBounds
import com.vivenotes.model.Outline
import kotlin.math.hypot
import kotlin.math.roundToInt

internal const val IMAGE_LAYER_TAG = "image-layer"

/**
 * The pictures on the page — feature E6, as Prime Objects.
 *
 * **[EquationLayer] is nested inside this one, not beside it.** `memory/plan.md` entry 24 is the whole
 * argument and it cost a day: two full-page layers as siblings means Compose hands every touch to
 * whichever is on top, and the one underneath goes quietly dead. The three are one chain instead —
 * and this is its outermost link, because a picture is the frontmost of the three and the touch is
 * claimed on the way *down*. See the call site in `EditorPane`.
 *
 * Selection, drag-to-move and four-corner resize are AD7, and the geometry matches [ShapeLayer] and
 * [EquationLayer] to the dp for the reason they match each other: an affordance that behaves
 * differently depending on what is under it is worse than not having one.
 *
 * **The decoded bitmaps are the expensive part, and they are not in the document.** Loading is keyed
 * on the attachment id, runs off the main thread, and is cached here for as long as some picture on
 * the page refers to it — see [rememberPageAssets], which also names the two ways that can fail.
 */
@Composable
internal fun ImageLayer(
    images: List<Outline.Image>,
    /** The page's selection, which may hold other kinds. This layer reads only its picture half. */
    selection: CanvasSelection?,
    attachments: AttachmentStore,
    /**
     * The live lasso transform, so a picture inside a lasso move follows the finger with the ink
     * rather than jumping when the drag ends. Null when there is no lasso in play.
     */
    lassoGesture: LassoGesture? = null,
    /** False while a tool is armed or the lasso owns the page — see [ShapeLayer.interactive]. */
    interactive: Boolean,
    /**
     * What the page can currently show, in the same units this layer draws in.
     *
     * Called *inside* the draw scope, never during composition — the idiom `PageRuling` established
     * and `InkOverlay` reuses: reading the scroll here means scrolling re-runs the draw and nothing
     * above it.
     */
    visibleWindow: () -> Rect,
    onSelect: (CanvasSelection?) -> Unit,
    onMove: (imageId: String, dx: Float, dy: Float) -> Unit,
    onResize: (
        imageId: String,
        anchorX: Float,
        anchorY: Float,
        scaleX: Float,
        scaleY: Float,
    ) -> Unit = { _, _, _, _, _ -> },
    density: Float,
    modifier: Modifier = Modifier,
    /**
     * The next layer **down** — [EquationLayer], and [ShapeLayer] inside that. This is the outermost
     * of the three, so it is asked first and paints last. See [EquationLayer.beneath].
     */
    beneath: @Composable BoxScope.() -> Unit = {},
) {
    val accent = MaterialTheme.colorScheme.primary
    val handleFill = MaterialTheme.colorScheme.surface
    val placeholder = MaterialTheme.colorScheme.surfaceVariant

    // What a broken picture says, in the theme's error colour. The plate underneath stays the
    // loading grey: a whole frame filled with `errorContainer` was tried first and is too loud on
    // a page — a photograph-sized red block shouts far past what a missing file is worth. The words
    // are the signal, and they are red.
    val brokenLabel = MaterialTheme.typography.labelMedium.copy(
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
    )
    val measurer = rememberTextMeasurer()

    // Read by the gesture rather than captured by it: the handler below runs for the lifetime of the
    // layer and is never rebuilt, so everything it needs has to sit behind a stable holder.
    val currentImages = rememberUpdatedState(images)
    val currentSelection = rememberUpdatedState(selection)
    val currentInteractive = rememberUpdatedState(interactive)
    val currentOnSelect = rememberUpdatedState(onSelect)
    val currentOnMove = rememberUpdatedState(onMove)
    val currentOnResize = rememberUpdatedState(onResize)

    /** The drags in flight, drawn but not written until the lift — see [ShapeResize] for why. */
    val resize = remember { mutableStateOf<ImageResize?>(null) }
    val move = remember { mutableStateOf<ImageMove?>(null) }

    val assets = rememberPageAssets(images, attachments, density)

    // What the plate says, put where a screen reader — and an instrumented test — can reach it.
    // Everything this layer shows is painted into one canvas and has no semantics node of its own, so
    // a message that lives only in those pixels does not exist for either of them.
    val failures = images.mapNotNull { (assets[it.attachmentId] as? ImageAsset.Broken)?.reason }

    Box(
        modifier
            .fillMaxSize()
            .testTag(IMAGE_LAYER_TAG)
            .semantics {
                if (failures.isNotEmpty()) contentDescription = failures.joinToString(". ")
            }
            // Keyed on nothing, for the reason ShapeLayer spells out at length: `pointerInput(keys)`
            // cancels its coroutine when a key changes, and a restarted handler waits for a DOWN that
            // a finger already on the glass will never send.
            .pointerInput(Unit) {
                awaitEachGesture {
                    // Tunnelling pass — see `ShapeLayer`, where the whole ordering is set out.
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    if (!currentInteractive.value) return@awaitEachGesture
                    val all = currentImages.value
                    val held = currentSelection.value
                    val startX = down.position.x / density
                    val startY = down.position.y / density

                    // Handles belong to a lone picture. A selection holding more than one object is
                    // the lasso's to move, and the overlay owns that gesture.
                    val selected = all.singleOrNull {
                        held != null && held.isImageOnly && held.holdsImage(it.id)
                    }
                    // A handle wins over the body: they sit on the boundary, so each is also inside
                    // the move target.
                    val handle = selected?.handleNear(startX, startY)

                    val target = when {
                        handle != null -> selected
                        selected?.contains(startX, startY) == true -> selected
                        else -> all.topmostNear(startX, startY)
                    }

                    // Nothing of ours under the finger: leave the gesture for the layers underneath,
                    // whose tap on bare canvas opens a text container.
                    if (target == null) return@awaitEachGesture
                    down.consume()

                    val slop = viewConfiguration.touchSlop
                    var dragging = false
                    var last = down.position

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        // Every sample, not only the ones past the slop: one unconsumed sample with
                        // the finger down is what the scroll containers around the page are waiting
                        // for, and they will pick a half-finished drag back up and pan with it.
                        change.consume()
                        if (!change.pressed) {
                            if (!dragging) currentOnSelect.value(CanvasSelection.ofImage(target))
                            resize.value?.let {
                                currentOnResize.value(
                                    it.imageId, it.anchorX, it.anchorY, it.scaleX, it.scaleY,
                                )
                            }
                            move.value?.let { currentOnMove.value(it.imageId, it.dx, it.dy) }
                            break
                        }
                        if (!dragging && (change.position - down.position).getDistance() > slop) {
                            dragging = true
                            last = change.position
                            // The handles and the tooltip belong to whatever is being dragged, so
                            // grabbing an unselected picture selects and drags it in one motion.
                            if (selected?.id != target.id) {
                                currentOnSelect.value(CanvasSelection.ofImage(target))
                            }
                        }
                        if (dragging) {
                            if (handle != null) {
                                target.scaleFor(
                                    handle.corner,
                                    change.position.x / density,
                                    change.position.y / density,
                                )?.let { (scaleX, scaleY) ->
                                    val (anchorX, anchorY) = target.anchorFor(handle.corner)
                                    // Stopped where the far edges reach the page's origin corner,
                                    // and stopped on the preview so the picture does not grow past
                                    // it and spring back on the lift — [PageBounds].
                                    val limited = PageBounds.clampScale(
                                        target.pageBounds(),
                                        InkPoint(anchorX, anchorY),
                                        scaleX,
                                        scaleY,
                                    )
                                    resize.value =
                                        ImageResize(target.id, anchorX, anchorY, limited.x, limited.y)
                                }
                            } else {
                                // Measured from where the drag began rather than from the previous
                                // sample, so the travel is one number and the slop is not in it.
                                val travel = change.position - last
                                val limited = PageBounds.clampTranslation(
                                    target.pageBounds(),
                                    travel.x / density,
                                    travel.y / density,
                                )
                                move.value = ImageMove(target.id, limited.x, limited.y)
                            }
                        }
                    }
                    // Whatever ended the gesture, including a cancel that took the pointer away
                    // mid-drag — which therefore discards the drag rather than committing it.
                    resize.value = null
                    move.value = null
                }
            },
    ) {
        // First, so the pictures paint over it and are offered the touch before it — see [beneath].
        beneath()

        Canvas(Modifier.fillMaxSize()) {
            val revision = lassoGesture?.renderRevision ?: 0
            val moving = lassoGesture?.takeIf { it.isTransforming && revision >= 0 }
            val heldIds = selection?.imageIds.orEmpty()
            val resizing = resize.value
            val nudging = move.value
            // Read here rather than captured, so scrolling re-runs the draw and not the composition.
            val window = visibleWindow()

            // Both drags are preview-only, so they are applied here rather than read back out of the
            // document — the picture the user is dragging has not been written yet.
            fun previewOf(image: Outline.Image): Outline.Image = when {
                resizing?.imageId == image.id -> image.scaledAbout(
                    resizing.anchorX, resizing.anchorY, resizing.scaleX, resizing.scaleY,
                )
                nudging?.imageId == image.id -> image.translated(nudging.dx, nudging.dy)
                moving != null && image.id in heldIds -> image.withLassoPreview(moving)
                else -> image
            }

            images.forEach { image ->
                val drawn = previewOf(image)
                // Off-screen pictures are skipped before their bitmap is even asked for. A decoded
                // photograph is megabytes of native memory and a texture upload; a page of them that
                // are all nowhere near the window should cost neither.
                if (!drawn.intersects(window, density)) return@forEach
                drawImage(
                    drawn, assets[image.attachmentId], placeholder, brokenLabel, measurer, density,
                )
            }

            // Skipped while the lasso is armed, for the reason `ShapeLayer` gives on the same line:
            // the overlay is drawing the selection and owns every gesture on it.
            images.takeIf { lassoGesture == null }
                ?.singleOrNull { selection?.isImageOnly == true && it.id in heldIds }
                ?.let { drawImageSelection(previewOf(it), accent, handleFill, density) }
        }
    }
}

/**
 * What the page knows about one picture's bytes.
 *
 * Absent from the map is the third state, and the ordinary one: the load has not finished yet. Only
 * what the page has to *say* something about is named here; "not answered yet" says itself.
 */
private sealed interface ImageAsset {

    data class Ready(val bitmap: ImageBitmap) : ImageAsset

    /** No pixels, and the sentence the plate shows instead of them — see [drawBrokenImage]. */
    data class Broken(val reason: String) : ImageAsset
}

/**
 * Everything the page knows about its pictures' bytes, loaded off the main thread and kept while used.
 *
 * Keyed by attachment id rather than by outline id, so the same picture placed twice is decoded once
 * — which is the same property the content-addressed store gives the file itself.
 *
 * Entries are dropped as soon as no picture refers to them. A cache that only grows is a leak with a
 * photograph in it, and this one would be filled by scrolling through a notebook.
 *
 * **A failure is cached exactly like a success**, and deliberately: a picture whose file is gone is
 * not re-checked on every recomposition of the page. What lifts that cache is
 * [AttachmentStore.arrivals] — bytes actually landing — so the one thing that can change the answer
 * is also the only thing that asks the question again.
 *
 * That signal exists because of sync. Before it, a file could only appear under an open page during
 * an import, which rewrote the page anyway; now another device's picture arrives seconds after the
 * frame that points at it, and without a retry the reader would sit looking at a broken plate until
 * they navigated away and back. Successes are kept across an arrival: a decoded bitmap cannot have
 * become wrong, since the file is named by the hash of its own contents.
 */
@Composable
private fun rememberPageAssets(
    images: List<Outline.Image>,
    attachments: AttachmentStore,
    density: Float,
): Map<String, ImageAsset> {
    val assets = remember { mutableStateMapOf<String, ImageAsset>() }
    // The ids alone, so moving or resizing a picture does not re-run the load.
    val ids = images.map { it.attachmentId }.distinct()
    // The size actually needed, quantised so that dragging a corner does not re-decode every frame.
    val target = images.maxOfOrNull { targetPixelsFor(it, density) } ?: 0
    val arrivals by attachments.arrivals.collectAsState()

    LaunchedEffect(ids, target, arrivals) {
        ids.forEach { id ->
            if (assets[id] is ImageAsset.Ready) return@forEach
            // Decoded at the size the page needs rather than the size the file is; the store picks
            // the sample from the header, so a large photograph shown small is never fully decoded.
            val bitmap = attachments.loadBitmap(id, target)
            assets[id] = when {
                bitmap != null -> ImageAsset.Ready(bitmap.asImageBitmap())
                // Two different failures arrive as the same null, and they send the user to two
                // different places — hence the extra stat, only ever on the path that already failed.
                !attachments.hasFile(id) -> ImageAsset.Broken("Error: ${id.asFileName()} not found")
                else -> ImageAsset.Broken("Error: ${id.asFileName()} could not be read")
            }
        }
        val live = ids.toSet()
        assets.keys.filterNot { it in live }.forEach(assets::remove)
    }
    return assets
}

/**
 * The name of the file an outline points at, shortened to fit on a picture-sized plate.
 *
 * Attachments are content-addressed, so the id *is* the filename: `filesDir/attachments/<sha256>`,
 * 64 hex characters and no extension. The first twelve are enough to find the file by hand and short
 * enough to sit on one line of a small frame; the rest is a wall of hex that identifies nothing the
 * prefix has not already identified.
 */
private fun String.asFileName(): String =
    if (length <= FILE_NAME_CHARS) this else take(FILE_NAME_CHARS) + "…"

private const val FILE_NAME_CHARS = 12

/** Chrome, so plain dp: the message stays the same size whatever size the picture was going to be. */
private val BROKEN_PADDING = 8.dp
private const val BROKEN_MAX_LINES = 3

/**
 * How many pixels across a picture is worth decoding.
 *
 * Its page width in device pixels, doubled so that zooming in does not immediately go soft, then
 * rounded up to a power of two. The rounding is what stops a resize drag from asking for a slightly
 * different size on every frame and re-decoding the file sixty times a second.
 */
private fun targetPixelsFor(image: Outline.Image, density: Float): Int {
    val needed = (image.width * density * 2f).roundToInt().coerceAtLeast(1)
    var size = 128
    while (size < needed && size < AttachmentStore.MAX_DIMENSION) size *= 2
    return size.coerceAtMost(AttachmentStore.MAX_DIMENSION)
}

/** Whether any of this picture's frame is inside the window. Both are in device pixels. */
private fun Outline.Image.intersects(window: Rect, density: Float): Boolean {
    val left = x * density
    val top = y * density
    return left + width * density >= window.left && left <= window.right &&
        top + height * density >= window.top && top <= window.bottom
}

/**
 * Draws one picture into its frame.
 *
 * A frame whose bitmap has not arrived yet is filled rather than left blank — the page reflows the
 * moment the picture lands, and a hole that then fills in reads as a bug where a plate does not. A
 * frame whose bitmap is never going to arrive gets [drawBrokenImage] instead.
 */
private fun DrawScope.drawImage(
    image: Outline.Image,
    asset: ImageAsset?,
    placeholder: Color,
    brokenLabel: TextStyle,
    measurer: TextMeasurer,
    density: Float,
) {
    val topLeft = Offset(image.x * density, image.y * density)
    val size = Size(image.width * density, image.height * density)
    when (asset) {
        null -> drawRect(color = placeholder, topLeft = topLeft, size = size)
        is ImageAsset.Broken ->
            drawBrokenImage(asset, topLeft, size, placeholder, brokenLabel, measurer)
        is ImageAsset.Ready -> drawImage(
            image = asset.bitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(asset.bitmap.width, asset.bitmap.height),
            dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        )
    }
}

/**
 * The plate for a picture that cannot be drawn, with the reason written on it.
 *
 * **A blank plate is the bug this replaces.** A picture whose file is missing kept the loading fill —
 * `#333333` on the dark theme — so the page showed a black rectangle where the photograph was, with
 * nothing to say whether it was still decoding, empty, or gone for good. The frame is still drawn at
 * the document's size, because the picture has not been deleted and the page must not reflow around a
 * file that may come back.
 *
 * **The plate keeps that same fill, and only the message is red.** Painting the whole frame in
 * `errorContainer` was the first attempt and it was rejected on sight: a photograph-sized block of
 * saturated red is the loudest thing on the page, for a condition that needs to be noticed once and
 * then read. A missing picture is now told apart from a loading one by the words on it, not by colour.
 *
 * **Painted rather than composed**, for the reason everything else on this layer is: it has to follow
 * the drag and lasso previews and the window culling, all of which live in this draw scope. A `Text`
 * child would have to read that state during composition and would recompose the layer on every frame
 * of a drag. What a canvas cannot give — words a screen reader or a test can find — the layer's
 * `contentDescription` carries instead.
 */
private fun DrawScope.drawBrokenImage(
    asset: ImageAsset.Broken,
    topLeft: Offset,
    size: Size,
    plate: Color,
    label: TextStyle,
    measurer: TextMeasurer,
) {
    drawRect(color = plate, topLeft = topLeft, size = size)

    val padding = BROKEN_PADDING.toPx()
    val room = Size(size.width - padding * 2, size.height - padding * 2)
    if (room.width <= 0f || room.height <= 0f) return
    val layout = measurer.measure(
        text = asset.reason,
        style = label,
        overflow = TextOverflow.Ellipsis,
        maxLines = BROKEN_MAX_LINES,
        constraints = Constraints(maxWidth = room.width.toInt()),
    )
    // A picture can be resized down to `Outline.Image.MIN_SIZE`, where the sentence would spill out
    // of its own frame and over whatever is beside it. A thumbnail-sized frame therefore says nothing
    // on the page and is left to the `contentDescription` — spilling someone else's picture over is
    // worse than a plate that has to be tapped to be understood.
    if (layout.size.height > room.height) return
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            topLeft.x + (size.width - layout.size.width) / 2f,
            topLeft.y + (size.height - layout.size.height) / 2f,
        ),
    )
}

/**
 * A dashed box and four corner handles — [ShapeLayer]'s selection, drawn by the same numbers.
 *
 * Shared constants rather than a shared function because the kinds draw different things inside the
 * box; what must not differ is where the corners are and how far a finger may miss one by.
 */
private fun DrawScope.drawImageSelection(
    image: Outline.Image,
    accent: Color,
    handleFill: Color,
    scale: Float,
) {
    val padding = SelectionChrome.PADDING.toPx()
    val left = image.x * scale - padding
    val top = image.y * scale - padding
    val right = left + image.width * scale + padding * 2
    val bottom = top + image.height * scale + padding * 2

    drawRect(
        color = accent,
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
        style = Stroke(
            width = SelectionChrome.STROKE.toPx(),
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(SelectionChrome.DASH.toPx(), SelectionChrome.DASH.toPx()),
            ),
        ),
    )

    val radius = SelectionChrome.HANDLE_RADIUS.toPx()
    listOf(left to top, right to top, right to bottom, left to bottom).forEach { (x, y) ->
        drawCircle(handleFill, radius, Offset(x, y))
        drawCircle(accent, radius, Offset(x, y), style = Stroke(width = SelectionChrome.STROKE.toPx()))
    }
}

private enum class ImageCorner { TopLeft, TopRight, BottomRight, BottomLeft }

/** What the finger came down on. A picture is a box, so a corner is the only kind there is. */
private data class ImageHandle(val corner: ImageCorner)

/** A corner drag in flight — preview only, committed once on the lift. See [ShapeResize]. */
private data class ImageResize(
    val imageId: String,
    val anchorX: Float,
    val anchorY: Float,
    val scaleX: Float,
    val scaleY: Float,
)

/** A move in flight: the whole travel since the drag began, reported once on the lift. */
private data class ImageMove(val imageId: String, val dx: Float, val dy: Float)

/** The live lasso move or resize, applied for the draw only — nothing is written until the up. */
private fun Outline.Image.withLassoPreview(gesture: LassoGesture): Outline.Image {
    val offset = gesture.previewOffset()
    val scale = gesture.previewScale()
    val anchor = gesture.previewAnchor()
    return when {
        offset.x != 0f || offset.y != 0f -> translated(offset.x, offset.y)
        scale.x != 1f || scale.y != 1f -> scaledAbout(anchor.x, anchor.y, scale.x, scale.y)
        else -> this
    }
}

private fun Outline.Image.cornerPoint(corner: ImageCorner): Pair<Float, Float> = when (corner) {
    ImageCorner.TopLeft -> x to y
    ImageCorner.TopRight -> (x + width) to y
    ImageCorner.BottomRight -> (x + width) to (y + height)
    ImageCorner.BottomLeft -> x to (y + height)
}

/** The corner that stays put: the one opposite the one being dragged. */
private fun Outline.Image.anchorFor(corner: ImageCorner): Pair<Float, Float> = cornerPoint(
    when (corner) {
        ImageCorner.TopLeft -> ImageCorner.BottomRight
        ImageCorner.TopRight -> ImageCorner.BottomLeft
        ImageCorner.BottomRight -> ImageCorner.TopLeft
        ImageCorner.BottomLeft -> ImageCorner.TopRight
    },
)

/** The corner under the point, or null. Page units, so [SelectionChrome.HANDLE_REACH] is plain dp. */
private fun Outline.Image.handleNear(x: Float, y: Float): ImageHandle? =
    ImageCorner.entries
        .map { corner ->
            val (cx, cy) = cornerPoint(corner)
            ImageHandle(corner) to hypot(x - cx, y - cy)
        }
        .minByOrNull { (_, distance) -> distance }
        ?.takeIf { (_, distance) -> distance <= SelectionChrome.HANDLE_REACH.value }
        ?.first

/**
 * How far the dragged corner has taken the frame, measured from the geometry the drag started with.
 *
 * Null when the anchor and the finger are on the same line, which would be a scale of zero on one
 * axis and a frame that can never be grabbed again.
 */
private fun Outline.Image.scaleFor(
    corner: ImageCorner,
    x: Float,
    y: Float,
): Pair<Float, Float>? {
    val (anchorX, anchorY) = anchorFor(corner)
    if (width <= 0f || height <= 0f) return null
    val spanX = when (corner) {
        ImageCorner.TopLeft, ImageCorner.BottomLeft -> anchorX - x
        ImageCorner.TopRight, ImageCorner.BottomRight -> x - anchorX
    }
    val spanY = when (corner) {
        ImageCorner.TopLeft, ImageCorner.TopRight -> anchorY - y
        ImageCorner.BottomLeft, ImageCorner.BottomRight -> y - anchorY
    }
    if (spanX <= 0f || spanY <= 0f) return null
    return (spanX / width) to (spanY / height)
}

/** Last drawn wins a tap, which is the one on top. */
private fun List<Outline.Image>.topmostNear(pointX: Float, pointY: Float): Outline.Image? =
    asReversed().firstOrNull { it.contains(pointX, pointY) }

private fun Outline.Image.contains(pointX: Float, pointY: Float): Boolean =
    pointX >= x && pointX <= x + width && pointY >= y && pointY <= y + height
