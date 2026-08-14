package com.vivenotes.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.data.AttachmentStore
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.model.Outline
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * A picture whose file is not there says so — the bug being that it did not.
 *
 * [ImageLayer] fills a frame it has no bitmap for with `surfaceVariant`, so that a picture still being
 * decoded is a plate rather than a hole. A picture whose attachment is *gone* took the same path and
 * stayed there: on the dark theme, a black rectangle on the page, permanently, with nothing to say
 * what had happened or which file was involved.
 *
 * Both halves are asserted, because either alone is satisfiable by the bug: the words have to be in
 * the frame's pixels, **and** they have to say which file and what went wrong. The wording is read
 * off the layer's `contentDescription`, which is where the painted message is repeated for exactly
 * this reason — nothing drawn into a canvas has a semantics node (see `PrimeObjectTest` on the dashed
 * selection box) — and the pixels are counted separately, since a correct description over an
 * unchanged plate is the failure that would otherwise slip through.
 *
 * The healthy case is here too, as the guard on the other side: a picture that loads must not be
 * accused of being missing while it is still on its way.
 */
class MissingImageTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** 64 hex characters, as a real content-addressed attachment id is. */
    private val missingId = "0123456789abcdef".repeat(4)
    private val presentId = "fedcba9876543210".repeat(4)

    /** What the first twelve characters plus an ellipsis come out as. */
    private val missingName = "0123456789ab…"

    private lateinit var databaseFile: File
    private lateinit var db: NotesDatabase
    private lateinit var store: AttachmentStore

    /** Read out of the theme inside the composition, since that is the only place it is correct. */
    private var errorInk: Color = Color.Unspecified

    @Before
    fun setUp() {
        databaseFile = File(context.cacheDir, "missing-image.db")
        listOf("", "-wal", "-shm").forEach { File(databaseFile.path + it).delete() }
        db = Room.databaseBuilder(context, NotesDatabase::class.java, databaseFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        store = AttachmentStore(context, db)
        // The store reads the file by id and never the table, so nothing is inserted here: what is
        // being tested is a page pointing at bytes that are not on disk.
        store.fileFor(missingId).delete()
        store.fileFor(presentId).delete()
    }

    @After
    fun tearDown() {
        store.fileFor(missingId).delete()
        store.fileFor(presentId).delete()
        db.close()
        listOf("", "-wal", "-shm").forEach { File(databaseFile.path + it).delete() }
    }

    /** A picture the store can actually decode: one flat colour, in the format an import writes. */
    private fun writeAttachment(id: String, color: Int) {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        FileOutputStream(store.fileFor(id)).use { out ->
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 88, out)
        }
        bitmap.recycle()
    }

    /**
     * One picture on an otherwise bare layer, at the page's origin.
     *
     * The layer by itself rather than through `EditorPane`: what is under test is what a frame draws
     * when its bytes cannot be had, and hosting the pane would put a page's worth of scroll and title
     * band between the frame's page coordinates and the pixels this reads back.
     */
    private fun setPage(attachmentId: String) {
        compose.setContent {
            ViveNotesTheme {
                errorInk = MaterialTheme.colorScheme.error
                val density = LocalDensity.current.density
                Box(Modifier.size(WIDTH.dp, HEIGHT.dp)) {
                    ImageLayer(
                        images = listOf(
                            Outline.Image(
                                id = "picture",
                                x = 0f,
                                y = 0f,
                                width = WIDTH,
                                attachmentId = attachmentId,
                                height = HEIGHT,
                            ),
                        ),
                        selection = null,
                        attachments = store,
                        interactive = true,
                        // Everything, so nothing is culled before it is drawn.
                        visibleWindow = { Rect(0f, 0f, 10_000f, 10_000f) },
                        onSelect = {},
                        onMove = { _, _, _ -> },
                        density = density,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** The layer's pixels, at a point given in page dp. */
    private fun pixelAt(xDp: Float, yDp: Float): Color {
        val image = compose.onNodeWithTag(IMAGE_LAYER_TAG).captureToImage()
        val scale = image.width / WIDTH
        return image.toPixelMap()[(xDp * scale).toInt(), (yDp * scale).toInt()]
    }

    /** How many of the layer's pixels are wearing [color], within [TOLERANCE] of it. */
    private fun pixelsWearing(color: Color): Int {
        val pixels = compose.onNodeWithTag(IMAGE_LAYER_TAG).captureToImage().toPixelMap()
        var found = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (pixels[x, y].isNear(color)) found++
            }
        }
        return found
    }

    @Test
    fun aPictureWhoseFileIsGoneSaysSoInRed() {
        setPage(missingId)

        val expected = "Error: $missingName not found"
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithContentDescription(expected).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(IMAGE_LAYER_TAG).assertContentDescriptionEquals(expected)

        // Read off the pixels as well as off the semantics, because the description alone is
        // satisfiable by the bug: what the user is looking at is a frame, and the words have to be
        // *in* it. The plate behind them is deliberately still the loading grey, so the error colour
        // is the only thing on this layer that can account for these pixels.
        assertTrue(
            "nothing on the page is wearing the error colour — the message was not drawn",
            pixelsWearing(errorInk) > GLYPH_PIXELS,
        )
    }

    @Test
    fun aPictureThatLoadsSaysNothing() {
        val red = 0xFFE53935.toInt()
        writeAttachment(presentId, red)
        setPage(presentId)

        // Waited for on the pixels, because a picture that loaded leaves no other trace: the frame
        // holds the placeholder until the decode lands. Near rather than equal — WEBP is lossy, and
        // what is being waited for is "the photograph is on the page", not a colour value.
        compose.waitUntil(TIMEOUT_MS) { pixelAt(WIDTH / 2f, HEIGHT / 2f).isNear(Color(red)) }

        compose.onNodeWithTag(IMAGE_LAYER_TAG).assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription),
        )
    }

    private fun Color.isNear(other: Color): Boolean =
        abs(red - other.red) < TOLERANCE &&
            abs(green - other.green) < TOLERANCE &&
            abs(blue - other.blue) < TOLERANCE

    private companion object {
        /** Wide enough for the message to fit, so a failure is the message being absent. */
        const val WIDTH = 240f
        const val HEIGHT = 160f
        const val TIMEOUT_MS = 5_000L

        /** Room for WEBP's rounding, and nothing like enough to reach another plate's colour. */
        const val TOLERANCE = 0.05f

        /**
         * More red pixels than antialiasing could account for on its own, and far fewer than a
         * sentence at 12 sp actually paints — this is a "the words are there" floor, not a metric.
         */
        const val GLYPH_PIXELS = 50
    }
}
