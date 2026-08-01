package st.unamedtba.ui.editor

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import st.unamedtba.data.EditorDefaults
import st.unamedtba.model.Block
import st.unamedtba.model.Orientation
import st.unamedtba.model.PageStyle
import st.unamedtba.model.PaperSize
import st.unamedtba.model.RuleLines
import st.unamedtba.ui.theme.UnamedTbaTheme
import kotlin.math.abs

/**
 * What the View tab does to the page.
 *
 * The one that matters is the pair of tap tests. Zoom is a layer transform, and a transform that
 * scales what is drawn but not where touches land is the classic way to break an editor: text would
 * appear under the finger while the caret went somewhere else. That is invisible in the code and
 * obvious only when something actually taps the screen.
 */
class PageViewTest {

    @get:Rule
    val compose = createComposeRule()

    // Held as state and composed once, because a Compose test may only set its content once —
    // tests that compare two settings drive them through here rather than recomposing from scratch.
    private val style = mutableStateOf(PageStyle())
    private val zoom = mutableFloatStateOf(1f)
    private val title = mutableStateOf("A page")
    private var composed = false
    private lateinit var density: Density
    private var created: Pair<Float, Float>? = null

    private fun setPage(
        style: PageStyle = PageStyle(),
        zoom: Float = 1f,
        title: String = "A page",
    ) {
        created = null
        this.style.value = style
        this.zoom.floatValue = zoom
        this.title.value = title
        if (!composed) {
            composed = true
            compose.setContent {
                density = LocalDensity.current
                UnamedTbaTheme {
                    EditorPane(
                        title = this.title.value,
                        createdAt = 0L,
                        defaults = EditorDefaults(),
                        style = this.style.value,
                        zoom = this.zoom.floatValue,
                        onTitleChange = {},
                        outlines = emptyList(),
                        pageRevision = 0,
                        initialBlocksFor = { listOf(Block.empty()) },
                        commands = emptyFlow(),
                        onBlocksChanged = { _, _ -> },
                        onSelectionChanged = {},
                        onMarkArmed = {},
                        onCreateOutline = { x, y -> created = x to y; "new-outline" },
                        onMoveOutline = { _, _, _ -> },
                        onResizeOutline = { _, _ -> },
                        onSetOutlineMinHeight = { _, _ -> },
                        onOutlineBlurred = {},
                        onCanvasMeasured = { _, _ -> },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** Taps the canvas at a point measured from the top-left of the window. */
    private fun tapScreen(xDp: Float, yDp: Float) {
        val offset = with(density) { Offset(xDp.dp.toPx(), yDp.dp.toPx()) }
        compose.onRoot().performTouchInput { click(offset) }
        compose.waitForIdle()
    }

    private fun pageSize() = compose.onNodeWithTag(PageTags.SURFACE).fetchSemanticsNode().size

    // --- zoom ------------------------------------------------------------------------------------

    @Test
    fun aTapLandsWhereItWasAimed() {
        // The title is hidden so the canvas starts at the top of the window and the arithmetic is
        // the transform alone, not the transform plus a header height.
        setPage(style = PageStyle(hideTitle = true))

        tapScreen(200f, 300f)

        val (x, y) = created ?: error("tapping empty canvas created no container")
        assertEquals(192f, x, 1f)
        assertEquals(292f, y, 1f)
    }

    /**
     * At 2x, a page point is half as far from the corner as the pixel that shows it. If the tap
     * were not transformed, this would report the same coordinates as the test above.
     */
    @Test
    fun aTapIsInterpretedInPageCoordinatesNotScreenOnes() {
        setPage(style = PageStyle(hideTitle = true), zoom = 2f)

        tapScreen(200f, 300f)

        val (x, y) = created ?: error("tapping empty canvas created no container")
        assertEquals(92f, x, 1f)
        assertEquals(142f, y, 1f)
    }

    /**
     * Zoom is a projection, not a resize: the sheet covers twice the screen at 2x while its layout
     * keeps the size the paper says. That distinction is the design — it is what lets outline
     * coordinates, hit testing and the stored document all stay in page units at any zoom — so both
     * halves are asserted, and the drawn width has to be measured off the pixels because layout
     * geometry is by definition the half that does not move.
     */
    @Test
    fun zoomScalesWhatIsDrawnWithoutRelayingOutThePage() {
        val sheet = PageStyle(hideTitle = true, paper = PaperSize.IndexCard, ruleLines = RuleLines.None)
        setPage(style = sheet)
        val natural = sheetWidthOnScreen()
        val laidOut = pageSize().width

        setPage(style = sheet, zoom = 2f)

        assertClose("drawn sheet width", expected = natural * 2, actual = sheetWidthOnScreen(), tolerance = 4)
        assertEquals("the page itself must not be re-laid out", laidOut, pageSize().width)
    }

    /** How far the sheet reaches across the window, in pixels, read off the rendered frame. */
    private fun sheetWidthOnScreen(): Int {
        val image = compose.onRoot().captureToImage()
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        val row = image.height / 4
        val paper = pixels[row * image.width]
        var x = 0
        while (x < image.width && pixels[row * image.width + x] == paper) x++
        return x
    }

    // --- paper size ------------------------------------------------------------------------------

    @Test
    fun paperSizeGivesThePageRealBounds() {
        setPage(style = PageStyle(hideTitle = true, paper = PaperSize.A6))

        val size = pageSize()
        val expected = with(density) {
            (PaperSize.A6.widthInches * PageStyle.DP_PER_INCH).dp.roundToPx() to
                (PaperSize.A6.heightInches * PageStyle.DP_PER_INCH).dp.roundToPx()
        }
        assertClose("sheet width", expected = expected.first, actual = size.width)
        assertClose("sheet height", expected = expected.second, actual = size.height)
    }

    @Test
    fun landscapeTurnsTheSheetOnItsSide() {
        setPage(
            style = PageStyle(
                hideTitle = true,
                paper = PaperSize.A6,
                orientation = Orientation.Landscape,
            ),
        )

        val size = pageSize()

        assertTrue("a landscape sheet should be wider than it is tall", size.width > size.height)
    }

    // --- title and ruling ------------------------------------------------------------------------

    @Test
    fun hidePageTitleRemovesTheTitleFromThePage() {
        setPage(style = PageStyle(hideTitle = false))
        compose.onNodeWithText("A page").assertIsDisplayed()

        setPage(style = PageStyle(hideTitle = true))

        compose.onNodeWithText("A page").assertDoesNotExist()
    }

    // Asserting on the pixels rather than on state: the ruling is drawn straight to a Canvas, so
    // there is nothing else to inspect, and "the setting changed but nothing was painted" is
    // exactly the failure worth catching. One render per test, because comparing two renders means
    // capturing a frame right after a state change, which is a race the assertion cannot see.

    @Test
    fun anUnruledPageIsOneFlatColour() {
        setPage(style = PageStyle(hideTitle = true, paper = PaperSize.A6, ruleLines = RuleLines.None))

        assertEquals(1, distinctColoursInsideThePage())
    }

    @Test
    fun aSquaredPageIsActuallyRuled() {
        setPage(style = PageStyle(hideTitle = true, paper = PaperSize.A6, ruleLines = RuleLines.GridMedium))

        assertTrue("nothing was painted on a squared page", distinctColoursInsideThePage() > 1)
    }

    /** Colours within the sheet, kept clear of its own border. */
    private fun distinctColoursInsideThePage(): Int {
        val image = compose.onNodeWithTag(PageTags.SURFACE).captureToImage()
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        val inset = 8
        val seen = HashSet<Int>()
        for (y in inset until image.height - inset) {
            for (x in inset until image.width - inset) {
                seen += pixels[y * image.width + x]
            }
        }
        return seen.size
    }

    private fun assertClose(what: String, expected: Int, actual: Int, tolerance: Int = 2) {
        assertTrue("$what: expected about $expected px, was $actual", abs(expected - actual) <= tolerance)
    }
}
