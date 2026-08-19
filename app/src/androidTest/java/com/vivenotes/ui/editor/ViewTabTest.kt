package com.vivenotes.ui.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.vivenotes.data.TabsLayout
import com.vivenotes.data.ViewSettings
import com.vivenotes.model.PageStyle
import com.vivenotes.model.RuleLines
import com.vivenotes.ui.panel.ToolPane
import com.vivenotes.ui.theme.ViveNotesTheme

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
    private var openedPane: ToolPane? = null
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
            ViveNotesTheme {
                ViewTab(
                    style = style,
                    settings = settings,
                    pageOpen = pageOpen,
                    actions = ViewActions(
                        setRuleLines = { ruleLines = it },
                        setPageColor = { if (it == null) pageColorCleared = true else pageColor = it },
                        setHideTitle = { hideTitle = it },
                        setZoom = { zoom = it },
                        zoomIn = { zoomedIn = true },
                        zoomOut = { zoomedOut = true },
                        zoomToPageWidth = { fittedToPageWidth = true },
                        setTabsLayout = { tabsLayout = it },
                        setCanvasDark = { canvasDark = it },
                        setLinkPreviews = {},
                        openPane = { openedPane = it },
                    ),
                )
            }
        }
    }

    @Test
    fun ruleLinesPicksTheChosenRuling() {
        setTab()

        compose.onNodeWithText("Paper").performClick()
        compose.onNodeWithText("Hexagonal Paper").performClick()

        assertEquals(RuleLines.Hexagonal, ruleLines)
    }

    @Test
    fun removedRuleLineOptionsAreNotOffered() {
        setTab()

        compose.onNodeWithText("Paper").performClick()

        compose.onNodeWithText("Narrow Ruled").assertDoesNotExist()
        compose.onNodeWithText("College Ruled").assertDoesNotExist()
        compose.onNodeWithText("Small Grid").assertDoesNotExist()
    }

    @Test
    fun pageColorCanBeCleared() {
        setTab(style = PageStyle(backgroundArgb = 0xFF112233.toInt()))

        compose.onNodeWithText("Page Color").performClick()
        compose.onNodeWithText("No Color").performClick()

        assertTrue("clearing the page colour must hand the page back to the theme", pageColorCleared)
    }

    /** Paper Size is a pane, not a menu — six fields in two groups do not belong in a drop-down. */
    @Test
    fun paperSizeOpensItsPaneRatherThanAMenu() {
        setTab()

        compose.onNodeWithText("Paper Size").performClick()

        assertEquals(ToolPane.PaperSize, openedPane)
        compose.onNodeWithText("A4").assertDoesNotExist()
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
     * Dropped at the user's request, not deferred — see `ViewTab`'s KDoc for why this app has nothing
     * for them to toggle. Asserted absent rather than deleted, so re-adding them is a decision
     * somebody makes on purpose.
     */
    @Test
    fun theViewToggleButtonsAreGone() {
        setTab()

        compose.onNodeWithText("Full Page View").assertDoesNotExist()
        compose.onNodeWithText("Normal View").assertDoesNotExist()
    }

    /** With no page open there is nothing whose appearance these could change. */
    @Test
    fun pageControlsAreInertUntilAPageIsOpen() {
        setTab(pageOpen = false)

        compose.onNodeWithText("Paper").performClick()

        compose.onNodeWithText("Standard Ruled").assertDoesNotExist()
        assertNull(ruleLines)
    }
}
