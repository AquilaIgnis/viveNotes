package com.vivenotes.ui.editor

import android.graphics.Matrix
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.data.AttachmentStore
import com.vivenotes.data.EraserSettings
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.ink.CanvasSelection
import com.vivenotes.model.Outline
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * What a screen reader finds where a picture is: the text read out of it, or that it is a picture.
 *
 * The layer paints every picture into one canvas, so each needs a node of its own laid over its
 * frame — at the frame, or touch exploration lands on the wrong thing — and that node must not take
 * the touch the layer's own gestures need.
 */
class ImageDescriptionTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** No bytes are written for it: a description does not depend on the picture loading. */
    private val attachmentId = "a1b2c3d4e5f60718".repeat(4)

    private lateinit var databaseFile: File
    private lateinit var db: NotesDatabase
    private lateinit var store: AttachmentStore

    private var selected: CanvasSelection? = null

    @Before
    fun setUp() {
        databaseFile = File(context.cacheDir, "image-description.db")
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

    private fun setPage(pictureText: Map<String, String>, inkOverlay: Boolean = false) {
        compose.setContent {
            ViveNotesTheme {
                val density = LocalDensity.current.density
                Box(Modifier.size(PAGE.dp, PAGE.dp)) {
                    ImageLayer(
                        images = listOf(
                            Outline.Image(
                                id = "picture",
                                x = X,
                                y = Y,
                                width = WIDTH,
                                attachmentId = attachmentId,
                                height = HEIGHT,
                            ),
                        ),
                        selection = null,
                        attachments = store,
                        interactive = true,
                        visibleWindow = { Rect(0f, 0f, 10_000f, 10_000f) },
                        onSelect = { selected = it },
                        onMove = { _, _, _ -> },
                        density = density,
                        pictureText = pictureText,
                    )
                    // Over everything, as the editor lays it: the ink overlay is the page's topmost
                    // layer, so whatever it hides from a screen reader is the whole page.
                    if (inkOverlay) {
                        InkOverlay(
                            strokes = emptyList(),
                            lassoGesture = remember { LassoGesture() },
                            brush = null,
                            erasing = false,
                            lassoing = false,
                            shaping = null,
                            eraser = EraserSettings(),
                            allowFinger = false,
                            pageToView = { Matrix() },
                            onStrokeFinished = {},
                            onPartialErase = {},
                            onObjectErase = {},
                            onMoveSelection = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * Every content description in the window, read the way a screen reader reads them: through
     * the accessibility tree, which Compose prunes of whatever an important node lies over. The
     * semantics tree the other tests query is not pruned, so it cannot show this.
     */
    private fun screenReaderDescriptions(): List<String> {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            ?: return emptyList()
        val found = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo) {
            node.contentDescription?.let { found += it.toString() }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::walk)
        }
        walk(root)
        return found
    }

    @Test
    fun aPictureIsDescribedByTheTextReadOutOfItAndSitsOnItsFrame() {
        setPage(mapOf(attachmentId to "Quarterly totals"))

        compose.onNodeWithContentDescription("Picture: Quarterly totals")
            .assertLeftPositionInRootIsEqualTo(X.dp)
            .assertTopPositionInRootIsEqualTo(Y.dp)
            .assertWidthIsEqualTo(WIDTH.dp)
            .assertHeightIsEqualTo(HEIGHT.dp)
    }

    /** Nothing read yet, or nothing legible: still announced, or a screen reader skips it entirely. */
    @Test
    fun aPictureWithNoTextIsStillAPicture() {
        setPage(emptyMap())

        compose.onNodeWithContentDescription("Picture").assertExists()
    }

    /**
     * The ink overlay lies over the whole page. Counted as a node of its own, it hid everything
     * under it from a screen reader — this picture, and the page's title and text with it.
     */
    @Test
    fun aPictureUnderTheInkOverlayStillReachesAScreenReader() {
        setPage(mapOf(attachmentId to "Quarterly totals"), inkOverlay = true)

        compose.waitUntil(TIMEOUT_MS) { "Picture: Quarterly totals" in screenReaderDescriptions() }
    }

    /** The node lies over the picture, and the tap that selects the picture has to go through it. */
    @Test
    fun theDescriptionDoesNotTakeTheTouch() {
        setPage(mapOf(attachmentId to "Quarterly totals"))

        compose.onNodeWithTag(IMAGE_LAYER_TAG).performTouchInput {
            click(percentOffset((X + WIDTH / 2) / PAGE, (Y + HEIGHT / 2) / PAGE))
        }
        compose.waitForIdle()

        assertEquals(setOf("picture"), selected?.imageIds)
    }

    private companion object {
        const val PAGE = 400f
        const val X = 40f
        const val Y = 60f
        const val WIDTH = 200f
        const val HEIGHT = 120f
        const val TIMEOUT_MS = 5_000L
    }
}
