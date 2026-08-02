package com.vivenotes.ui.editor

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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.vivenotes.data.EditorDefaults
import com.vivenotes.model.Block
import com.vivenotes.model.Orientation
import com.vivenotes.model.PageStyle
import com.vivenotes.model.PaperSize
import com.vivenotes.model.RuleLines
import com.vivenotes.richtext.FormatCommand
import com.vivenotes.richtext.SelectionState
import com.vivenotes.ui.OutlineBox
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlin.math.abs

/**
 * What the View tab does to the page.
 *
 * The one that matters is the pair of tap tests. Zoom is a layer transform, and a transform that
 * scales what is drawn but not where touches land is the classic way to break an editor: text would
 * appear under the finger while the caret went somewhere else. That is invisible in the code and
 * obvious only when something actually taps the screen.
 *
 * **Needs a large-screen device**, which is what the app targets (`docs/plan.md` §1) and what CI
 * runs on. [zoomScalesWhatIsDrawnWithoutRelayingOutThePage] measures the sheet against the window,
 * so on a phone-sized window the sheet is already clipped at 1x and the comparison is meaningless.
 */
class PageViewTest {

    @get:Rule
    val compose = createComposeRule()

    // Held as state and composed once, because a Compose test may only set its content once —
    // tests that compare two settings drive them through here rather than recomposing from scratch.
    private val style = mutableStateOf(PageStyle())
    private val zoom = mutableFloatStateOf(1f)
    private val title = mutableStateOf("A page")
    private val outlines = mutableStateOf(emptyList<OutlineBox>())
    private var composed = false
    private lateinit var density: Density
    private var created: Pair<Float, Float>? = null
    private val commands = MutableSharedFlow<FormatCommand>(extraBufferCapacity = 4)
    private val selection = mutableStateOf(SelectionState())

    private fun setPage(
        style: PageStyle = PageStyle(),
        zoom: Float = 1f,
        title: String = "A page",
        outlines: List<OutlineBox> = emptyList(),
    ) {
        created = null
        this.style.value = style
        this.zoom.floatValue = zoom
        this.title.value = title
        this.outlines.value = outlines
        if (!composed) {
            composed = true
            compose.setContent {
                density = LocalDensity.current
                ViveNotesTheme {
                    EditorPane(
                        title = this.title.value,
                        createdAt = 0L,
                        defaults = EditorDefaults(),
                        style = this.style.value,
                        zoom = this.zoom.floatValue,
                        onTitleChange = {},
                        outlines = this.outlines.value,
                        pageRevision = 0,
                        initialBlocksFor = { listOf(Block.empty()) },
                        commands = commands,
                        onBlocksChanged = { _, _ -> },
                        onSelectionChanged = { selection.value = it },
                        onMarkArmed = {},
                        onCreateOutline = { x, y -> created = x to y; "new-outline" },
                        onMoveOutline = { _, _, _ -> },
                        onResizeOutline = { _, _ -> },
                        onSetOutlineMinHeight = { _, _ -> },
                        onOutlineBlurred = {},
                        onCanvasMeasured = { _, _ -> },
                        showPrintMargins = false,
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

    @Test
    fun pickingADrawToolDismissesTheActiveTextCursor() {
        setPage(
            style = PageStyle(hideTitle = true),
            outlines = listOf(OutlineBox("text", 80f, 100f, 360f)),
        )
        compose.onNodeWithTag(OutlineTags.EDITOR).performClick()
        compose.waitUntil(timeoutMillis = 2_000) { selection.value.editorFocused }

        var emitted = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            emitted = commands.tryEmit(FormatCommand.DeactivateTextInput)
        }
        assertTrue(emitted)

        compose.waitUntil(timeoutMillis = 2_000) { !selection.value.editorFocused }
    }

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
        val sheet = PageStyle(hideTitle = true, paper = PaperSize.Billfold, ruleLines = RuleLines.None)
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

    // --- the page's origin -------------------------------------------------------------------------

    /**
     * One coordinate space: an outline at `(0, 0)` is at the sheet's own top-left corner.
     *
     * It used to be measured from a box sitting *below* the title header, so the same y meant one
     * thing to an outline and another to the ruling and the margin guides drawn on the sheet — the
     * guides could not line up with the content they exist to describe.
     *
     * Asserted with the title shown, because that is the case where the two origins came apart.
     */
    @Test
    fun anOutlineAtTheOriginSitsAtThePagesTopLeft() {
        setPage(
            style = PageStyle(paper = PaperSize.A4),
            outlines = listOf(OutlineBox(id = "o", x = 0f, y = 0f, width = 300f)),
        )

        val sheet = compose.onNodeWithTag(PageTags.SURFACE).fetchSemanticsNode().positionInRoot
        val container = compose.onNodeWithTag(OutlineTags.CONTAINER).fetchSemanticsNode().positionInRoot

        assertClose("outline x against the sheet", expected = 0, actual = (container.x - sheet.x).toInt())
        assertClose("outline y against the sheet", expected = 0, actual = (container.y - sheet.y).toInt())
    }

    // --- the sheet binds, or marks -----------------------------------------------------------------

    /**
     * A chosen size hard-sizes the page only while the page can hold what is on it. That is the
     * whole rule: a sheet is a bound when the content fits and a guide when it does not, so choosing
     * A4 can never put work out of reach.
     */
    @Test
    fun aSheetTheContentFitsInsideBindsThePage() {
        setPage(
            style = PageStyle(hideTitle = true, paper = PaperSize.A6),
            outlines = listOf(OutlineBox(id = "o", x = 0f, y = 40f, width = 300f)),
        )

        val sheet = pageSize()
        val expectedHeight = with(density) { (PaperSize.A6.heightInches * PageStyle.DP_PER_INCH).dp.roundToPx() }
        assertClose("the writable area is the sheet", expected = expectedHeight, actual = sheet.height)
        compose.onNodeWithTag(PageTags.SHEET_GUIDE).assertDoesNotExist()
    }

    @Test
    fun contentPastTheSheetMarksItInsteadOfClippingIt() {
        val past = PaperSize.A6.heightInches * PageStyle.DP_PER_INCH + 200f
        setPage(
            style = PageStyle(hideTitle = true, paper = PaperSize.A6),
            outlines = listOf(OutlineBox(id = "o", x = 0f, y = past, width = 300f)),
        )

        val sheetHeight = with(density) { (PaperSize.A6.heightInches * PageStyle.DP_PER_INCH).dp.roundToPx() }
        assertTrue(
            "the page must still reach the content that spilled past the sheet",
            pageSize().height > sheetHeight,
        )
        compose.onNodeWithTag(PageTags.SHEET_GUIDE).assertExists()
    }

    /** A page bound by a sheet has edges: there is nowhere outside it to put anything. */
    @Test
    fun aTapBesideABoundSheetCreatesNothing() {
        setPage(style = PageStyle(hideTitle = true, paper = PaperSize.A6, ruleLines = RuleLines.None))
        val beyond = PaperSize.A6.widthInches * PageStyle.DP_PER_INCH + 80f

        tapScreen(beyond, 200f)
        assertNull("a tap off the sheet started a container", created)

        tapScreen(120f, 200f)
        assertNotNull("a tap on the sheet should still start one", created)
    }

    /** The title owns the band at the top of the page, now that content shares its coordinates. */
    @Test
    fun aTapOnTheTitleBandCreatesNothing() {
        setPage(style = PageStyle(paper = PaperSize.A4))

        tapScreen(300f, PageStyle.TITLE_BAND_DP / 2f)

        assertNull("a tap on the title started a container", created)
    }

    // --- an unbounded page keeps going ---------------------------------------------------------

    /**
     * `Auto` is an endless canvas, not "content plus a margin": scrolling to the bottom has to find
     * more page rather than the end of it. Grown a screenful at a time, so this asserts the step.
     */
    @Test
    fun anAutoPageExtendsAsItIsScrolled() {
        setPage(style = PageStyle(hideTitle = true))
        val before = canvasHeight()

        repeat(3) {
            compose.onRoot().performTouchInput { swipeUp() }
            compose.waitForIdle()
        }

        assertTrue(
            "scrolling to the bottom of an endless page should find more of it: was $before, still $before",
            canvasHeight() > before,
        )
    }

    private fun canvasHeight() = compose.onNodeWithTag(PageTags.CANVAS).fetchSemanticsNode().size.height

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
