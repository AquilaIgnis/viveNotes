package com.vivenotes.ui.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.data.ViewSettings
import com.vivenotes.ui.panel.ToolPane
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsTabTest {
    @get:Rule
    val compose = createComposeRule()

    private var opened = false
    private var pane: ToolPane? = null

    /**
     * Held as Compose state and driven, rather than read back off a captured lambda: the button
     * renders its own on/off from this, so a test that only recorded the callback would pass
     * while the switch sat visibly stuck.
     */
    private var linkPreviews by mutableStateOf(true)

    @Test
    fun integratedOpensTheModelPane() {
        setTab()

        compose.onNodeWithTag(SettingsTags.INTEGRATED).performClick()

        assertTrue(opened)
    }

    @Test
    fun hardwareOpensTheHardwarePane() {
        setTab()

        compose.onNodeWithTag(SettingsTags.HARDWARE).performClick()

        assertEquals(ToolPane.Hardware, pane)
    }

    /**
     * About is the one command here that answers with a window rather than a pane, and the three
     * things in that window are the whole reason it exists: which build this is, where the source
     * is, and where to support it. Asserted by content and not only by the dialog being up, because
     * a dialog that opens empty is exactly as useless as one that does not open.
     */
    @Test
    fun aboutOpensAWindowWithTheVersionAndBothAddresses() {
        setTab()

        compose.onNodeWithTag(SettingsTags.ABOUT).performClick()

        compose.onNodeWithTag(AboutTags.DIALOG).assertIsDisplayed()
        // The installed package's own name, read the same way the dialog reads it, so bumping the
        // version in the build file does not fail this.
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        compose.onNodeWithTag(AboutTags.VERSION)
            .assertTextEquals("Version ${info.versionName}")
        compose.onNodeWithText("github.com/AquilaIgnis/viveNotes").assertIsDisplayed()
        compose.onNodeWithText("buymeacoffee.com/acidburn").assertIsDisplayed()
    }

    @Test
    fun closingTheAboutWindowLeavesTheTabBehind() {
        setTab()

        compose.onNodeWithTag(SettingsTags.ABOUT).performClick()
        compose.onNodeWithTag(AboutTags.CLOSE).performClick()

        compose.onNodeWithTag(AboutTags.DIALOG).assertDoesNotExist()
        // The tab it was opened from is still there and still works — the failure being a dialog
        // that takes its host with it when it goes.
        compose.onNodeWithTag(SettingsTags.HARDWARE).performClick()
        assertEquals(ToolPane.Hardware, pane)
    }

    /**
     * Link Previews reports its own state, and toggling it turns the fetch off rather than doing
     * nothing visible.
     *
     * The `active` background is what a user reads the setting off, so the assertion is on the
     * rendered state after the round trip through the caller — not on the callback alone, which
     * would still pass if the button were wired to a value it never re-read.
     */
    @Test
    fun linkPreviewsTogglesAndReportsItsState() {
        setTab()

        compose.onNodeWithTag(SettingsTags.LINK_PREVIEWS).assertIsDisplayed()
        assertTrue(linkPreviews)

        compose.onNodeWithTag(SettingsTags.LINK_PREVIEWS).performClick()
        compose.waitForIdle()
        assertEquals(false, linkPreviews)

        compose.onNodeWithTag(SettingsTags.LINK_PREVIEWS).performClick()
        compose.waitForIdle()
        assertEquals(true, linkPreviews)
    }

    private fun setTab() {
        compose.setContent {
            ViveNotesTheme {
                SettingsTab(
                    ai = AiActions(openIntegrated = { opened = true }),
                    openPane = { pane = it },
                    viewSettings = ViewSettings(linkPreviews = linkPreviews),
                    onSetLinkPreviews = { linkPreviews = it },
                )
            }
        }
    }
}
