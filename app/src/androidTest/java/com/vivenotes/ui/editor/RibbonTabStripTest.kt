package com.vivenotes.ui.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import com.vivenotes.BuildConfig
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
 * These are app-wide actions rather than tabs: putting the armed tool down, whether a finger may
 * draw, and opening the account destination. The fixed strip keeps them available while the document
 * tabs scroll underneath.
 */
class RibbonTabStripTest {

    @get:Rule
    val compose = createComposeRule()

    private var selectedTool: DrawTool? = null
    private var fingerDrawing: Boolean? = null
    private var accountOpened = false

    /**
     * The empty hand, moved off the Draw tab. Putting a tool down is not a drawing setting: a pen
     * stays armed while you type, so the tap that disarms it must not cost a trip to Draw and back.
     */
    @Test
    fun thePointerPutsTheArmedToolDown() {
        setRibbon(tool = DrawTool.Pen(1))

        compose.onNodeWithTag(RibbonTags.POINTER).performClick()

        assertEquals(DrawTool.None, selectedTool)
    }

    /** The whole point of the move: it is not the Draw tab's, so it does not leave with the tab. */
    @Test
    fun thePointerIsThereWithTheDocumentTabOpen() {
        setRibbon(activeTab = RibbonTab.Document)

        compose.onNodeWithTag(RibbonTags.POINTER).assertIsDisplayed()
    }

    @Test
    fun thePointerIsStillThereWithTheDrawTabOpen() {
        setRibbon(activeTab = RibbonTab.Draw)

        compose.onNodeWithTag(RibbonTags.POINTER).assertIsDisplayed()
    }

    /** "Left of the finger button" is the whole placement, and a row is easy to reorder. */
    @Test
    fun thePointerSitsLeftOfTheFingerButton() {
        assumeTrue("the finger button ships in debug only", BuildConfig.DEBUG)
        setRibbon()

        val pointer = compose.onNodeWithTag(RibbonTags.POINTER).fetchSemanticsNode().boundsInRoot
        val finger = compose.onNodeWithTag(RibbonTags.FINGER).fetchSemanticsNode().boundsInRoot

        assertTrue("$pointer is not left of $finger", pointer.right <= finger.left)
    }

    /**
     * Holds in a release build too, where the finger button is gone and the pointer would otherwise
     * be free to drift past the one control that is still to its right.
     */
    @Test
    fun thePointerSitsLeftOfTheAccountButton() {
        setRibbon()

        val pointer = compose.onNodeWithTag(RibbonTags.POINTER).fetchSemanticsNode().boundsInRoot
        val account = compose.onNodeWithTag(RibbonTags.ACCOUNT).fetchSemanticsNode().boundsInRoot

        assertTrue("$pointer is not left of $account", pointer.right <= account.left)
    }

    /**
     * The finger button is a debug convenience — an emulator has no stylus — and `BuildConfig.DEBUG`
     * is a compile-time constant, so a release APK does not contain it at all. *Let a finger draw*
     * in Settings > Hardware is what a shipped build offers, writing the same flag.
     */
    @Test
    fun theFingerButtonShipsInDebugBuildsOnly() {
        setRibbon()

        if (BuildConfig.DEBUG) {
            compose.onNodeWithTag(RibbonTags.FINGER).assertIsDisplayed()
        } else {
            compose.onNodeWithTag(RibbonTags.FINGER).assertDoesNotExist()
        }
    }

    @Test
    fun theFingerButtonTogglesWhoMayDraw() {
        assumeTrue("the finger button ships in debug only", BuildConfig.DEBUG)
        setRibbon(allowFinger = false)

        compose.onNodeWithTag(RibbonTags.FINGER).performClick()

        assertEquals(true, fingerDrawing)
    }

    @Test
    fun theFingerButtonTurnsBackOff() {
        assumeTrue("the finger button ships in debug only", BuildConfig.DEBUG)
        setRibbon(allowFinger = true)

        compose.onNodeWithTag(RibbonTags.FINGER).performClick()

        assertEquals(false, fingerDrawing)
    }

    /** It is not the Draw tab's either, so it does not leave with the tab. */
    @Test
    fun theFingerButtonIsStillThereWithTheDrawTabOpen() {
        assumeTrue("the finger button ships in debug only", BuildConfig.DEBUG)
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
        tool: DrawTool = DrawTool.None,
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
                    tool = tool,
                    allowFinger = allowFinger,
                    draw = DrawActions(
                        selectTool = { selectedTool = it },
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
