package com.vivenotes.ui.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.ai.AiModelInstallState
import com.vivenotes.ai.AiModelStore
import com.vivenotes.ai.OnnxInkRecognitionEngine
import com.vivenotes.data.AttachmentStore
import com.vivenotes.data.EditorDefaults
import com.vivenotes.data.ImageTextIndexer
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.PictureTextHighlighter
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageStyle
import com.vivenotes.model.ocr.ImageLineLayout
import com.vivenotes.model.ocr.PictureHighlight
import com.vivenotes.model.ocr.PictureHighlights
import com.vivenotes.model.search.ContentKind
import com.vivenotes.model.search.MatchSpan
import com.vivenotes.ui.ContentReveal
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The boxes a search puts on a picture, as they reach the screen.
 *
 * Read off the rendered pixels: the layer paints into one canvas, so what is on the words is only
 * visible there.
 */
class ImageSearchHighlightTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var databaseFile: File
    private lateinit var db: NotesDatabase
    private lateinit var store: AttachmentStore

    @Before
    fun setUp() {
        databaseFile = File(context.cacheDir, "image-search-highlight.db")
        listOf("", "-wal", "-shm").forEach { File(databaseFile.path + it).delete() }
        db = Room.databaseBuilder(context, NotesDatabase::class.java, databaseFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        store = AttachmentStore(context, db)
    }

    @After
    fun tearDown() {
        db.close()
        listOf("", "-wal", "-shm").forEach { File(databaseFile.path + it).delete() }
    }

    private fun importPicture(bitmap: Bitmap): String = runBlocking {
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        store.import(bytes)?.id ?: error("import failed")
    }

    private fun blankPicture(): String =
        importPicture(Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.WHITE) })

    private fun setLayer(
        attachmentId: String,
        highlights: () -> Map<String, PictureHighlight>,
        width: Float = WIDTH,
        height: Float = HEIGHT,
        visibleWindow: Rect = Rect(0f, 0f, 10_000f, 10_000f),
    ) {
        compose.setContent {
            ViveNotesTheme {
                val density = LocalDensity.current.density
                Box(Modifier.size(PAGE.dp, PAGE.dp)) {
                    ImageLayer(
                        images = listOf(
                            Outline.Image(
                                id = "picture",
                                x = 0f,
                                y = 0f,
                                width = width,
                                attachmentId = attachmentId,
                                height = height,
                            ),
                        ),
                        selection = null,
                        attachments = store,
                        interactive = true,
                        visibleWindow = { visibleWindow },
                        onSelect = {},
                        onMove = { _, _, _ -> },
                        density = density,
                        pictureHighlights = highlights,
                    )
                }
            }
        }
    }

    private fun layerPixels(): Bitmap =
        compose.onNodeWithTag(IMAGE_LAYER_TAG).captureToImage().asAndroidBitmap()

    /** The colour at fractions of the picture's frame, which sits at the layer's origin. */
    private fun Bitmap.at(fractionX: Float, fractionY: Float, width: Float = WIDTH, height: Float = HEIGHT): Int {
        val density = context.resources.displayMetrics.density
        return getPixel(
            (fractionX * width * density).toInt().coerceIn(0, this.width - 1),
            (fractionY * height * density).toInt().coerceIn(0, this.height - 1),
        )
    }

    /** Waits for the picture to decode, which is asynchronous. A white picture shows as white. */
    private fun awaitWhitePixel(fractionX: Float, fractionY: Float) {
        compose.waitUntil(timeoutMillis = 5_000) { layerPixels().at(fractionX, fractionY) == AndroidColor.WHITE }
    }

    private val boxAtTheLeft = PictureHighlights.forSpans(
        line = "abcd",
        layout = ImageLineLayout(
            corners = floatArrayOf(0.1f, 0.2f, 0.9f, 0.2f, 0.9f, 0.4f, 0.1f, 0.4f),
            edges = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
        ),
        spans = listOf(MatchSpan(0, 2)),
    )!!

    /** The box covers x 0.1 to 0.5 and y 0.2 to 0.4 of the picture. */
    @Test
    fun aMatchIsPaintedOnItsWordsAndNowhereElse() {
        val id = blankPicture()
        setLayer(id, { mapOf(id to boxAtTheLeft) })
        awaitWhitePixel(0.8f, 0.8f)
        compose.waitUntil(timeoutMillis = 5_000) { layerPixels().at(0.3f, 0.3f) != AndroidColor.WHITE }

        val pixels = layerPixels()

        assertNotEquals("inside the box", AndroidColor.WHITE, pixels.at(0.3f, 0.3f))
        assertEquals("right of the box, on the same line", AndroidColor.WHITE, pixels.at(0.7f, 0.3f))
        assertEquals("below the box", AndroidColor.WHITE, pixels.at(0.3f, 0.7f))
    }

    @Test
    fun aBoxStaysOnItsWordsWhenTheFrameIsStretched() {
        val id = blankPicture()
        // Twice as wide as tall on the original, and the wrong way round here.
        setLayer(id, { mapOf(id to boxAtTheLeft) }, width = 200f, height = 400f)
        compose.waitUntil(timeoutMillis = 5_000) {
            layerPixels().at(0.3f, 0.3f, 200f, 400f) != AndroidColor.WHITE &&
                layerPixels().at(0.7f, 0.3f, 200f, 400f) == AndroidColor.WHITE
        }

        val pixels = layerPixels()

        assertNotEquals(AndroidColor.WHITE, pixels.at(0.3f, 0.3f, 200f, 400f))
        assertEquals(AndroidColor.WHITE, pixels.at(0.7f, 0.3f, 200f, 400f))
    }

    /** A new set of boxes reaches the screen through the draw alone. */
    @Test
    fun aNewQueryRepaintsAndAClearedOneRemovesTheBoxes() {
        val id = blankPicture()
        val highlights = mutableStateOf(emptyMap<String, PictureHighlight>())
        setLayer(id, { highlights.value })
        awaitWhitePixel(0.3f, 0.3f)

        compose.runOnIdle { highlights.value = mapOf(id to boxAtTheLeft) }
        compose.waitUntil(timeoutMillis = 5_000) { layerPixels().at(0.3f, 0.3f) != AndroidColor.WHITE }

        compose.runOnIdle { highlights.value = emptyMap() }
        compose.waitUntil(timeoutMillis = 5_000) { layerPixels().at(0.3f, 0.3f) == AndroidColor.WHITE }
    }

    /** The boxes are only painted. Without this a screen reader cannot tell the search landed here. */
    @Test
    fun aPictureWithMatchesSaysSoToAScreenReader() {
        val id = blankPicture()
        setLayer(id, { mapOf(id to boxAtTheLeft) })

        val expected = context.resources.getQuantityString(R_PLURAL, 1, 1)
        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected),
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun aPictureWithNoMatchesSaysNothingAboutThem() {
        val id = blankPicture()
        setLayer(id, { emptyMap() })

        compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription),
            useUnmergedTree = true,
        ).assertDoesNotExist()
    }

    /**
     * Opening a picture hit scrolls the page to the line and puts no selection on the picture.
     *
     * The line is far below the picture's top, as on a long screenshot, so a scroll to the picture's
     * corner would leave it off screen.
     */
    @Test
    fun openingAHitGoesToItsLineAndSelectsNothing() {
        val id = blankPicture()
        var handled = false
        var viewportTop = 0f
        val deepLine = PictureHighlights.forSpans(
            line = "abcd",
            layout = ImageLineLayout(
                corners = floatArrayOf(0.1f, 0.75f, 0.9f, 0.75f, 0.9f, 0.78f, 0.1f, 0.78f),
                edges = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
            ),
            spans = listOf(MatchSpan(0, 2)),
        )!!

        setEditor(
            attachmentId = id,
            reveal = imageReveal(deepLine),
            onHandled = { handled = true },
            onViewport = { _, top, _ -> viewportTop = top },
        )

        compose.waitUntil(timeoutMillis = 10_000) { handled }
        compose.waitForIdle()

        val expectedTop = 0.75f * TALL_HEIGHT
        assertTrue(
            "the page should have scrolled near y=$expectedTop, but its viewport starts at $viewportTop",
            viewportTop > expectedTop - 120f && viewportTop <= expectedTop,
        )
        compose.onNodeWithTag(OBJECT_TOOLTIP_TAG).assertDoesNotExist()
    }

    /** A picture not yet read for positions gets what it always got: selected. */
    @Test
    fun openingAHitWithNoPositionsSelectsThePicture() {
        val id = blankPicture()
        var handled = false

        setEditor(attachmentId = id, reveal = imageReveal(null), onHandled = { handled = true })

        compose.waitUntil(timeoutMillis = 10_000) { handled }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasTestTag(OBJECT_TOOLTIP_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun imageReveal(highlight: PictureHighlight?) = ContentReveal(
        pageId = "page",
        kind = ContentKind.Image,
        boxId = "picture",
        tableId = null,
        start = 0,
        end = 2,
        imageHighlight = highlight,
    )

    private fun setEditor(
        attachmentId: String,
        reveal: ContentReveal,
        onHandled: () -> Unit,
        onViewport: (Float, Float, Float) -> Unit = { _, _, _ -> },
    ) {
        compose.setContent {
            ViveNotesTheme {
                EditorPane(
                    title = "Page",
                    createdAt = 0L,
                    defaults = EditorDefaults(),
                    style = PageStyle(hideTitle = true),
                    zoom = 1f,
                    onTitleChange = {},
                    outlines = emptyList(),
                    pageRevision = 0,
                    pageId = "page",
                    initialBlocksFor = { listOf(Block.empty()) },
                    commands = emptyFlow(),
                    onBlocksChanged = { _, _ -> },
                    onSelectionChanged = { _ -> },
                    onMarkArmed = { _ -> },
                    onCreateOutline = { _, _ -> "unused" },
                    textArmed = false,
                    onMoveOutline = { _, _, _ -> },
                    onResizeOutline = { _, _ -> },
                    onSetOutlineMinHeight = { _, _ -> },
                    onOutlineBlurred = {},
                    onCanvasMeasured = { _, _ -> },
                    showPrintMargins = false,
                    reveal = reveal,
                    onRevealHandled = onHandled,
                    images = listOf(
                        Outline.Image(
                            id = "picture",
                            x = 0f,
                            y = 0f,
                            width = WIDTH,
                            attachmentId = attachmentId,
                            height = TALL_HEIGHT,
                        ),
                    ),
                    attachments = store,
                    onViewport = onViewport,
                )
            }
        }
    }

    /**
     * The whole chain with the real models: a picture is read, its row is stored, a query turns it
     * into a box, and the box lands on the pixels of the word.
     */
    @Test
    fun aWordReadByTheRealModelsIsMarkedWhereItIsDrawn() = runBlocking {
        val models = AiModelStore(context)
        withTimeout(10_000) {
            models.state.first { it.handwritingText !is AiModelInstallState.Verifying }
        }
        val bitmap = Bitmap.createBitmap(800, 300, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.BLACK
            textSize = 44f
        }
        Canvas(bitmap).apply {
            drawColor(AndroidColor.WHITE)
            drawText("Quarterly invoice summary", 40f, 90f, paint)
            drawText("The total is due on Friday", 40f, 200f, paint)
        }
        val id = importPicture(bitmap)
        val engine = OnnxInkRecognitionEngine(models)
        val repository = NotesRepository(db)
        try {
            val indexer = ImageTextIndexer(repository, store, engine)
            indexer.request(setOf(id))
            withTimeout(60_000) { indexer.awaitPass() }

            val highlights = PictureTextHighlighter(repository)
                .forQuery(setOf(id), "invoice", indexerVersion = 1L)
            assertEquals("one box, on the one line the word is on", 1, highlights.getValue(id).count)

            val wordLeft = (40f + paint.measureText("Quarterly ")) / bitmap.width
            val wordRight = (40f + paint.measureText("Quarterly invoice")) / bitmap.width
            val inside = (wordLeft + wordRight) / 2f
            val onTheNextWord = wordRight + 0.04f
            val quad = highlights.getValue(id).quads
            assertTrue(
                "box runs ${quad[0]} to ${quad[2]}, the word $wordLeft to $wordRight",
                quad[0] < inside && quad[2] > inside && quad[2] < onTheNextWord,
            )

            setLayer(id, { highlights }, width = WIDTH, height = WIDTH * 300f / 800f)
            compose.waitUntil(timeoutMillis = 5_000) {
                layerPixels().at(inside, 90f / 300f - 0.05f, WIDTH, WIDTH * 300f / 800f) != AndroidColor.WHITE
            }
            bitmap.recycle()
        } finally {
            engine.close()
        }
    }

    private companion object {
        const val PAGE = 500f
        const val WIDTH = 400f
        const val HEIGHT = 200f
        const val TALL_HEIGHT = 4_000f
        val R_PLURAL = com.vivenotes.R.plurals.image_search_matches
    }
}
