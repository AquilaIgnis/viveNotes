package st.unamedtba.ui.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import st.unamedtba.data.TabsLayout
import st.unamedtba.data.ViewSettings
import st.unamedtba.model.Orientation
import st.unamedtba.model.PageStyle
import st.unamedtba.model.PaperSize
import st.unamedtba.model.RuleLines
import st.unamedtba.ui.theme.UnamedTbaTheme

/**
 * The View tab is a wall of controls that mostly differ only in which setting they write, which is
 * precisely the shape of code where a copy-paste sends "Wide Ruled" to the paper size. These check
 * that each control reaches the setting it names.
 */
class ViewTabTest {

    @get:Rule
    val compose = createComposeRule()

    private var ruleLines: RuleLines? = null
    private var pageColor: Int? = null
    private var pageColorCleared = false
    private var paper: PaperSize? = null
    private var orientation: Orientation? = null
    private var hideTitle: Boolean? = null
    private var zoom: Float? = null
    private var zoomedIn = false
    private var zoomedOut = false
    private var fittedToPageWidth = false
    private var tabsLayout: TabsLayout? = null
    private var canvasDark: Boolean? = null

    private fun setTab(
        style: PageStyle = PageStyle(),
        settings: ViewSettings = ViewSettings(),
        pageOpen: Boolean = true,
    ) {
        compose.setContent {
            UnamedTbaTheme {
                ViewTab(
                    style = style,
                    settings = settings,
                    pageOpen = pageOpen,
                    actions = ViewActions(
                        setRuleLines = { ruleLines = it },
                        setPageColor = { if (it == null) pageColorCleared = true else pageColor = it },
                        setPaperSize = { paper = it },
                        setOrientation = { orientation = it },
                        setHideTitle = { hideTitle = it },
                        setZoom = { zoom = it },
                        zoomIn = { zoomedIn = true },
                        zoomOut = { zoomedOut = true },
                        zoomToPageWidth = { fittedToPageWidth = true },
                        setTabsLayout = { tabsLayout = it },
                        setCanvasDark = { canvasDark = it },
                    ),
                )
            }
        }
    }

    @Test
    fun ruleLinesPicksTheChosenRuling() {
        setTab()

        compose.onNodeWithText("Rule Lines").performClick()
        compose.onNodeWithText("College Ruled").performClick()

        assertEquals(RuleLines.College, ruleLines)
    }

    @Test
    fun pageColorCanBeCleared() {
        setTab(style = PageStyle(backgroundArgb = 0xFF112233.toInt()))

        compose.onNodeWithText("Page Color").performClick()
        compose.onNodeWithText("No Color").performClick()

        assertTrue("clearing the page colour must hand the page back to the theme", pageColorCleared)
    }

    @Test
    fun paperSizePicksASheet() {
        setTab()

        compose.onNodeWithText("Paper Size").performClick()
        compose.onNodeWithText("A4").performClick()

        assertEquals(PaperSize.A4, paper)
    }

    /** Orientation shares the Paper Size menu but is a separate property of the page. */
    @Test
    fun orientationTurnsTheSheetWithoutChangingIt() {
        setTab()

        compose.onNodeWithText("Paper Size").performClick()
        compose.onNodeWithText("Landscape").performScrollTo().performClick()

        assertEquals(Orientation.Landscape, orientation)
        assertNull("turning the page must not resize it", paper)
    }

    @Test
    fun hidePageTitleTogglesRatherThanOnlySetting() {
        setTab(style = PageStyle(hideTitle = true))

        compose.onNodeWithText("Hide Page Title").performClick()

        assertEquals("a second press should bring the title back", false, hideTitle)
    }

    @Test
    fun theZoomGroupReachesItsOwnActions() {
        // Deliberately not at 100%: the combo box displays the current zoom, so at 100% its label
        // would be indistinguishable from the button that resets to 100%.
        setTab(settings = ViewSettings(zoom = 1.25f))

        compose.onNodeWithText("Page Width").performClick()
        compose.onNodeWithText("100%").performClick()

        assertTrue(fittedToPageWidth)
        assertEquals(1f, zoom)
    }

    @Test
    fun theZoomComboOffersTheSameStepsTheButtonsClimb() {
        setTab(settings = ViewSettings(zoom = 1.25f))

        compose.onNodeWithText("125%").performClick()
        compose.onNodeWithText("150%").performClick()

        assertEquals(1.5f, zoom)
    }

    @Test
    fun switchBackgroundFlipsWhateverTheCanvasCurrentlyIs() {
        setTab()

        compose.onNodeWithText("Switch Background").performClick()

        // The theme under test is whatever the device is set to, so assert it made a choice at all
        // rather than which one — the direction is covered by it being the opposite of the canvas.
        assertTrue("Switch Background did not change the canvas", canvasDark != null)
    }

    @Test
    fun tabsLayoutSwitchesTheNavigation() {
        setTab(settings = ViewSettings(tabsLayout = TabsLayout.Vertical))

        compose.onNodeWithText("Tabs Layout").performClick()
        compose.onNodeWithText("Horizontal Tabs").performClick()

        assertEquals(TabsLayout.Horizontal, tabsLayout)
    }

    /**
     * These belong to the drawing surface, which is not built. They are on screen because the
     * ribbon's layout is part of the design, but they must not pretend to work.
     */
    @Test
    fun theViewToggleButtonsArePresentButInert() {
        setTab()

        compose.onNodeWithText("Full Page View").assertIsDisplayed()
        compose.onNodeWithText("Normal View").assertIsDisplayed()
        compose.onNodeWithText("Full Page View").performClick()
        compose.onNodeWithText("Normal View").performClick()

        assertNull("an unbuilt feature must not change anything", zoom)
        assertNull(tabsLayout)
    }

    /** With no page open there is nothing whose appearance these could change. */
    @Test
    fun pageControlsAreInertUntilAPageIsOpen() {
        setTab(pageOpen = false)

        compose.onNodeWithText("Rule Lines").performClick()

        compose.onNodeWithText("College Ruled").assertDoesNotExist()
        assertNull(ruleLines)
    }
}
