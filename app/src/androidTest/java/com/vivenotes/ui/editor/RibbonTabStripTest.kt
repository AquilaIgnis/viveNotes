package com.vivenotes.ui.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import com.vivenotes.data.DrawTool
import com.vivenotes.data.EditorDefaults
import com.vivenotes.data.EraserSettings
import com.vivenotes.data.HighlighterSettings
import com.vivenotes.data.PEN_COLORS
import com.vivenotes.data.PenPreset
import com.vivenotes.data.ShapeSettings
import com.vivenotes.data.ViewSettings
import com.vivenotes.model.PageStyle
import com.vivenotes.richtext.SelectionState
import com.vivenotes.ui.theme.ViveNotesTheme

/**
 * The tab strip's own controls, as opposed to the tabs.
 *
 * These are app-wide actions rather than tabs: whether a finger may draw and opening the account
 * destination. The fixed strip keeps both available while the document tabs scroll underneath.
 */
class RibbonTabStripTest {

    @get:Rule
    val compose = createComposeRule()

    private var fingerDrawing: Boolean? = null
    private var accountOpened = false

    @Test
    fun theFingerButtonTogglesWhoMayDraw() {
        setRibbon(allowFinger = false)

        compose.onNodeWithTag(RibbonTags.FINGER).performClick()

        assertEquals(true, fingerDrawing)
    }

    @Test
    fun theFingerButtonTurnsBackOff() {
        setRibbon(allowFinger = true)

        compose.onNodeWithTag(RibbonTags.FINGER).performClick()

        assertEquals(false, fingerDrawing)
    }

    /** The whole point of the move: it is not the Draw tab's, so it does not leave with the tab. */
    @Test
    fun theFingerButtonIsThereWithTheHomeTabOpen() {
        setRibbon(activeTab = RibbonTab.Document)

        compose.onNodeWithTag(RibbonTags.FINGER).assertIsDisplayed()
    }

    @Test
    fun theFingerButtonIsStillThereWithTheDrawTabOpen() {
        setRibbon(activeTab = RibbonTab.Draw)

        compose.onNodeWithTag(RibbonTags.FINGER).assertIsDisplayed()
    }

    @Test
    fun theAccountButtonOpensTheAccountDestination() {
        setRibbon()

        compose.onNodeWithTag(RibbonTags.ACCOUNT).performClick()

        assertEquals(true, accountOpened)
    }

    /**
     * The dot is the ribbon's whole report of sync state, so its absence has to be asserted too —
     * a badge that is always there says nothing.
     */
    @Test
    fun theAccountButtonIsUnmarkedWhileNoServerIsConnected() {
        setRibbon()

        compose.onNodeWithTag(RibbonTags.ACCOUNT_CONNECTED).assertDoesNotExist()
    }

    @Test
    fun theAccountButtonIsDottedOnceAServerIsConnected() {
        setRibbon(accountConnected = true)

        compose.onNodeWithTag(RibbonTags.ACCOUNT_CONNECTED).assertIsDisplayed()
        // The dot is decoration; what a screen reader gets is the button's own description.
        compose.onNodeWithContentDescription("Account, connected to a server").assertIsDisplayed()
    }

    /**
     * The Cloud Off state, which is the one worth having chrome for: a tablet whose sync has been
     * failing since it left the house looks, without this, exactly like one with nothing to send.
     */
    @Test
    fun theAccountButtonShowsCloudOffWhileTheServerCannotBeReached() {
        setRibbon(accountConnected = true, serverUnreachable = true)

        compose.onNodeWithTag(RibbonTags.ACCOUNT_OFFLINE).assertIsDisplayed()
        compose.onNodeWithContentDescription("Account, server unreachable").assertIsDisplayed()
    }

    /**
     * One state, not two. The dot's claim is "there is a server"; while it cannot be reached that
     * claim is the thing being corrected, so a button wearing both would assert a contradiction.
     */
    @Test
    fun cloudOffReplacesTheConnectedDotRatherThanJoiningIt() {
        setRibbon(accountConnected = true, serverUnreachable = true)

        compose.onNodeWithTag(RibbonTags.ACCOUNT_CONNECTED).assertDoesNotExist()
    }

    @Test
    fun theAccountButtonGoesBackToItsDotOnceTheServerAnswers() {
        setRibbon(accountConnected = true, serverUnreachable = false)

        compose.onNodeWithTag(RibbonTags.ACCOUNT_OFFLINE).assertDoesNotExist()
        compose.onNodeWithTag(RibbonTags.ACCOUNT_CONNECTED).assertIsDisplayed()
    }

    private fun setRibbon(
        activeTab: RibbonTab = RibbonTab.Document,
        allowFinger: Boolean = false,
        accountConnected: Boolean = false,
        serverUnreachable: Boolean = false,
    ) {
        compose.setContent {
            ViveNotesTheme {
                Ribbon(
                    selection = SelectionState(),
                    activeTab = activeTab,
                    onTabChange = {},
                    onCommand = {},
                    defaults = EditorDefaults(),
                    onSetDefault = {},
                    pageStyle = PageStyle(),
                    viewSettings = ViewSettings(),
                    view = noopViewActions(),
                    pens = List(PenPreset.COUNT) { PenPreset.starting(it) },
                    palette = PEN_COLORS,
                    eraser = EraserSettings(),
                    highlighter = HighlighterSettings(),
                    shape = ShapeSettings(),
                    tool = DrawTool.None,
                    allowFinger = allowFinger,
                    draw = DrawActions(
                        selectTool = {},
                        updatePen = { _, _ -> },
                        updateEraser = {},
                        setDrawWithFinger = { fingerDrawing = it },
                    ),
                    pageOpen = true,
                    onOpenAccount = { accountOpened = true },
                    accountConnected = accountConnected,
                    serverUnreachable = serverUnreachable,
                )
            }
        }
    }

    private fun noopViewActions() = ViewActions(
        setRuleLines = {},
        setPageColor = {},
        setHideTitle = {},
        setZoom = {},
        zoomIn = {},
        zoomOut = {},
        zoomToPageWidth = {},
        setTabsLayout = {},
        setCanvasDark = {},
        setLinkPreviews = {},
        openPane = {},
    )
}
