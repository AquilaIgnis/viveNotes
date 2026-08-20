package com.vivenotes.ui.closed

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vivenotes.data.db.ClosedNotebook
import com.vivenotes.data.db.NotebookEntity
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shelf, driven by its inputs.
 *
 * Compose tests may only set content once, so every input is held in `mutableStateOf` and driven —
 * the pattern the rest of the suite uses (`PageViewTest`).
 */
@RunWith(AndroidJUnit4::class)
class ClosedNotebooksScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var notebooks by mutableStateOf(emptyList<ClosedNotebook>())
    private var connected by mutableStateOf(false)
    private var busy by mutableStateOf<String?>(null)
    private var message by mutableStateOf<String?>(null)

    private val opened = mutableListOf<String>()
    private val moved = mutableListOf<String>()
    private val broughtBack = mutableListOf<String>()

    private fun setScreen() {
        compose.setContent {
            ViveNotesTheme {
                ClosedNotebooksScreen(
                    notebooks = notebooks,
                    onBack = {},
                    onOpen = { opened += it },
                    onMoveToCloud = { moved += it },
                    onBringBack = { broughtBack += it },
                    accountConnected = connected,
                    busyNotebookId = busy,
                    message = message,
                )
            }
        }
    }

    @Test
    fun anEmptyShelfSaysWhatClosingDoesRatherThanShowingNothing() {
        setScreen()

        compose.onNodeWithTag(ClosedNotebooksTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithTag(ClosedNotebooksTags.ON_DEVICE_HEADING).assertDoesNotExist()
        compose.onNodeWithTag(ClosedNotebooksTags.IN_CLOUD_HEADING).assertDoesNotExist()
    }

    @Test
    fun aNotebookOnTheDeviceOffersOpenAndMoveToCloud() {
        notebooks = listOf(closed("here", cloudOnly = false))
        connected = true
        setScreen()

        compose.onNodeWithTag(ClosedNotebooksTags.ON_DEVICE_HEADING).assertIsDisplayed()
        compose.onNodeWithTag(ClosedNotebooksTags.IN_CLOUD_HEADING).assertDoesNotExist()

        compose.onNodeWithTag(ClosedNotebooksTags.open("here")).performClick()
        compose.onNodeWithTag(ClosedNotebooksTags.moveToCloud("here")).performClick()

        assertEquals(listOf("here"), opened)
        assertEquals(listOf("here"), moved)
    }

    /**
     * A cloud-only notebook is in the other section and offers the other button.
     *
     * Not a matter of taste: it is the section heading that explains why the notebook cannot be
     * searched, and the row it applies to has to be under it.
     */
    @Test
    fun aCloudOnlyNotebookIsListedSeparatelyAndOnlyOffersBringBack() {
        notebooks = listOf(closed("elsewhere", cloudOnly = true))
        connected = true
        setScreen()

        compose.onNodeWithTag(ClosedNotebooksTags.IN_CLOUD_HEADING).assertIsDisplayed()
        compose.onNodeWithTag(ClosedNotebooksTags.ON_DEVICE_HEADING).assertDoesNotExist()
        compose.onNodeWithTag(ClosedNotebooksTags.open("elsewhere")).assertDoesNotExist()
        compose.onNodeWithTag(ClosedNotebooksTags.moveToCloud("elsewhere")).assertDoesNotExist()

        compose.onNodeWithTag(ClosedNotebooksTags.bringBack("elsewhere")).performClick()

        assertEquals(listOf("elsewhere"), broughtBack)
    }

    /**
     * Without a server there is nowhere to move a notebook to and nowhere to fetch one from, and a
     * button that looked live and did nothing would be the worse answer.
     */
    @Test
    fun withoutAnAccountTheCloudButtonsAreOfferedButNotLive() {
        notebooks = listOf(closed("here", cloudOnly = false), closed("elsewhere", cloudOnly = true))
        connected = false
        setScreen()

        compose.onNodeWithTag(ClosedNotebooksTags.moveToCloud("here")).assertIsNotEnabled()
        compose.onNodeWithTag(ClosedNotebooksTags.bringBack("elsewhere")).assertIsNotEnabled()
        compose.onNodeWithTag(ClosedNotebooksTags.moveToCloud("here")).performTouchInput { click() }
        assertEquals(emptyList<String>(), moved)

        // Opening one that is already here needs no server at all.
        compose.onNodeWithTag(ClosedNotebooksTags.open("here")).performClick()
        assertEquals(listOf("here"), opened)
    }

    /**
     * One spinner, on the row it belongs to, and the rest of the list held still.
     *
     * A move and a restore both take the sync mutex and would queue behind each other; two live
     * buttons with one of them silently waiting is a worse account of what is happening than one
     * spinner and a still list.
     */
    @Test
    fun aRunningMoveSpinsOnItsOwnRowAndFreezesTheOthers() {
        notebooks = listOf(closed("here", cloudOnly = false), closed("other", cloudOnly = false))
        connected = true
        busy = "here"
        setScreen()

        compose.onNodeWithTag(ClosedNotebooksTags.busy("here")).assertIsDisplayed()
        compose.onNodeWithTag(ClosedNotebooksTags.moveToCloud("here")).assertDoesNotExist()
        compose.onNodeWithTag(ClosedNotebooksTags.moveToCloud("other")).assertIsNotEnabled()
        compose.onNodeWithTag(ClosedNotebooksTags.open("other")).assertIsNotEnabled()

        busy = null
        compose.waitForIdle()
        compose.onNodeWithTag(ClosedNotebooksTags.busy("here")).assertDoesNotExist()
        compose.onNodeWithTag(ClosedNotebooksTags.moveToCloud("here")).performClick()
        assertEquals(listOf("here"), moved)
    }

    /** A refusal has to be visible, and a success has nothing to say the list has not said. */
    @Test
    fun aRefusalIsShownAndClearedWithTheNextAttempt() {
        notebooks = listOf(closed("here", cloudOnly = false))
        connected = true
        setScreen()
        compose.onNodeWithTag(ClosedNotebooksTags.MESSAGE).assertDoesNotExist()

        message = "This device still has 3 changes to upload."
        compose.waitForIdle()
        compose.onNodeWithTag(ClosedNotebooksTags.MESSAGE).assertIsDisplayed()

        message = null
        compose.waitForIdle()
        compose.onNodeWithTag(ClosedNotebooksTags.MESSAGE).assertDoesNotExist()
        assertNull(message)
    }

    private fun closed(id: String, cloudOnly: Boolean) = ClosedNotebook(
        notebook = NotebookEntity(
            id = id,
            name = "Notebook $id",
            colorArgb = 0xFF336699.toInt(),
            sortIndex = 0,
            createdAt = 1L,
            updatedAt = 2L,
            closedAt = 3L,
            cloudOnlyAt = if (cloudOnly) 4L else null,
        ),
        sectionCount = 2,
        pageCount = 7,
    )
}
