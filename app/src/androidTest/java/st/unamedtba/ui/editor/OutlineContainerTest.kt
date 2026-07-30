package st.unamedtba.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import st.unamedtba.model.Block
import st.unamedtba.richtext.EditorStyle
import st.unamedtba.ui.OutlineBox
import st.unamedtba.ui.theme.UnamedTbaTheme

/**
 * The container's drag targets are geometry-sensitive: a handle overlaid on the editor never
 * receives touches, because the editor is a real Android View that consumes them. That is not
 * visible in the code, only in where the handle ends up, so it needs a test that actually drags.
 */
class OutlineContainerTest {

    @get:Rule
    val compose = createComposeRule()

    private val style = EditorStyle(
        indentStepPx = 40,
        listGapPx = 40,
        bulletRadiusPx = 6,
        accentColor = 0xFF4CAF50.toInt(),
        codeBackgroundColor = 0x22FFFFFF,
        quoteColor = 0xFF4CAF50.toInt(),
    )

    private fun setContainer(
        box: OutlineBox = OutlineBox("o1", 0f, 0f, 300f),
        blocks: List<Block> = listOf(Block.of("hello there")),
        focused: Boolean = true,
        onMove: (Float, Float) -> Unit = { _, _ -> },
        onResize: (Float) -> Unit = {},
        onSetMinHeight: (Float) -> Unit = {},
    ) {
        compose.setContent {
            UnamedTbaTheme {
                OutlineContainer(
                    box = box,
                    initialBlocks = blocks,
                    editorStyle = style,
                    focused = focused,
                    requestFocus = false,
                    onFocusHandled = {},
                    onFocused = {},
                    onBlurred = {},
                    onBlocksChanged = {},
                    onSelectionChanged = {},
                    onMove = onMove,
                    onResize = onResize,
                    onSetMinHeight = onSetMinHeight,
                    onMeasured = {},
                )
            }
        }
    }

    @Test
    fun draggingTheBottomEdgeRaisesTheMinimumHeight() {
        var reported = -1f
        setContainer(onSetMinHeight = { reported = it })

        compose.onNodeWithTag(OutlineTags.RESIZE_HEIGHT).performTouchInput { swipeDown() }

        assertTrue("bottom handle never reported a height, got $reported", reported > 0f)
    }

    /**
     * The previous version of this only checked that the drag callback fired, and passed while
     * the box on screen never changed: the height constraint was being applied to the wrapper
     * instead of the editor. Assert the rendered text area, not the callback.
     */
    @Test
    fun aMinimumHeightGrowsTheTextAreaItself() {
        var box by mutableStateOf(OutlineBox("o1", 0f, 0f, 300f, minHeight = 0f))
        compose.setContent {
            UnamedTbaTheme {
                OutlineContainer(
                    box = box,
                    initialBlocks = listOf(Block.of("hello there")),
                    editorStyle = style,
                    focused = true,
                    requestFocus = false,
                    onFocusHandled = {},
                    onFocused = {},
                    onBlurred = {},
                    onBlocksChanged = {},
                    onSelectionChanged = {},
                    onMove = { _, _ -> },
                    onResize = {},
                    onSetMinHeight = {},
                    onMeasured = {},
                )
            }
        }

        val natural = compose.onNodeWithTag(OutlineTags.EDITOR).fetchSemanticsNode().size.height

        box = box.copy(minHeight = 400f)
        compose.waitForIdle()
        val expanded = compose.onNodeWithTag(OutlineTags.EDITOR).fetchSemanticsNode().size.height

        assertTrue("text area did not grow: $natural -> $expanded", expanded > natural * 2)
    }

    @Test
    fun draggingTheRightEdgeWidensTheContainer() {
        var reported = -1f
        setContainer(onResize = { reported = it })

        compose.onNodeWithTag(OutlineTags.RESIZE_WIDTH).performTouchInput { swipeRight() }

        assertTrue("right handle reported $reported, expected wider than 300", reported > 300f)
    }

    @Test
    fun draggingTheGripMovesTheContainer() {
        var moved = false
        setContainer(box = OutlineBox("o1", 50f, 50f, 300f), onMove = { _, _ -> moved = true })

        compose.onNodeWithTag(OutlineTags.MOVE).performTouchInput { swipeRight() }

        assertTrue("grip bar did not move the container", moved)
    }

    @Test
    fun aContainerWithContentShowsItsHandlesWhenFocused() {
        setContainer()

        compose.onNodeWithTag(OutlineTags.MOVE).assertIsDisplayed()
        compose.onNodeWithTag(OutlineTags.RESIZE_WIDTH).assertIsDisplayed()
        compose.onNodeWithTag(OutlineTags.RESIZE_HEIGHT).assertIsDisplayed()
    }

    /** Tapping around the page must not litter it with visible empty rectangles. */
    @Test
    fun anEmptyContainerShowsNoChromeEvenWhenFocused() {
        setContainer(blocks = listOf(Block.empty()), focused = true)

        compose.onNodeWithTag(OutlineTags.MOVE).assertDoesNotExist()
        compose.onNodeWithTag(OutlineTags.RESIZE_WIDTH).assertDoesNotExist()
        compose.onNodeWithTag(OutlineTags.RESIZE_HEIGHT).assertDoesNotExist()
    }

    @Test
    fun anUnfocusedContainerShowsNoChrome() {
        setContainer(focused = false)

        compose.onNodeWithTag(OutlineTags.MOVE).assertDoesNotExist()
        compose.onNodeWithTag(OutlineTags.RESIZE_WIDTH).assertDoesNotExist()
    }
}
