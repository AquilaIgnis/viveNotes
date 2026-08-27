package com.vivenotes.ui.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.R
import com.vivenotes.data.sync.ConnectFailure
import com.vivenotes.data.sync.PermanentSyncFailure
import com.vivenotes.data.sync.SelfHostConnection
import com.vivenotes.data.sync.SyncRunResult
import com.vivenotes.data.sync.SyncStatus
import com.vivenotes.data.sync.SyncSummary
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AccountScreenTest {
    @get:Rule
    val compose = createComposeRule()

    /**
     * Driven rather than replaced: the connection state is an input to the screen, so the suite
     * moves it the way the app will and sets content once. See `PageViewTest` for the pattern.
     */
    private var connection by mutableStateOf<SelfHostConnection>(SelfHostConnection.Idle)

    private val connectCalls = mutableListOf<Triple<String, String, String>>()
    private var disconnects = 0
    private var forceDisconnects = 0
    private var disconnectFailure by mutableStateOf<ConnectFailure?>(null)
    private var syncs = 0
    private var syncing by mutableStateOf(false)
    private var syncStatus by mutableStateOf(SyncStatus())

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun hostedAccountActionsAreAvailable() {
        var login = false
        var signUp = false
        compose.setContent {
            ViveNotesTheme {
                AccountScreen(
                    onBack = {},
                    onLogIn = { login = true },
                    onSignUp = { signUp = true },
                )
            }
        }

        compose.onNodeWithTag(AccountTags.LOGIN).performClick()
        compose.onNodeWithTag(AccountTags.SIGN_UP).performClick()

        assertTrue(login)
        assertTrue(signUp)
    }

    @Test
    fun selfHostRevealsUrlEmailAndPasswordFields() {
        setScreen()
        compose.onNodeWithTag(AccountTags.SERVER_URL).assertDoesNotExist()

        compose.onNodeWithTag(AccountTags.SELF_HOST).performClick()

        compose.onNodeWithTag(AccountTags.SERVER_URL).assertIsDisplayed()
        compose.onNodeWithTag(AccountTags.EMAIL).assertIsDisplayed()
        compose.onNodeWithTag(AccountTags.PASSWORD).assertIsDisplayed()
    }

    @Test
    fun selfHostAcceptsConnectionDetails() {
        setScreen()
        compose.onNodeWithTag(AccountTags.SELF_HOST).performClick()

        compose.onNodeWithTag(AccountTags.SERVER_URL)
            .performTextInput("https://notes.example.com")
        compose.onNodeWithTag(AccountTags.EMAIL)
            .performTextInput("writer@example.com")
        compose.onNodeWithTag(AccountTags.PASSWORD)
            .performTextInput("correct horse battery staple")

        compose.onNodeWithTag(AccountTags.SERVER_URL)
            .assertTextContains("https://notes.example.com")
        compose.onNodeWithTag(AccountTags.EMAIL)
            .assertTextContains("writer@example.com")
    }

    /** Connect belongs to the disclosure, not the card: there is nothing to connect to above it. */
    @Test
    fun connectAppearsOnlyWithTheSelfHostFields() {
        setScreen()
        compose.onNodeWithTag(AccountTags.CONNECT).assertDoesNotExist()

        compose.onNodeWithTag(AccountTags.SELF_HOST).performClick()

        compose.onNodeWithTag(AccountTags.CONNECT).assertIsDisplayed()
    }

    @Test
    fun connectWaitsForAllThreeFieldsAndThenSendsWhatWasTyped() {
        setScreen()
        compose.onNodeWithTag(AccountTags.SELF_HOST).performClick()
        compose.onNodeWithTag(AccountTags.CONNECT).assertIsNotEnabled()

        compose.onNodeWithTag(AccountTags.SERVER_URL).performTextInput("http://10.0.2.2:5444")
        compose.onNodeWithTag(AccountTags.EMAIL).performTextInput("owner@example.com")
        // Still short of all three, so the round trip that could only fail is not offered.
        compose.onNodeWithTag(AccountTags.CONNECT).assertIsNotEnabled()

        compose.onNodeWithTag(AccountTags.PASSWORD).performTextInput("correct horse")
        compose.onNodeWithTag(AccountTags.CONNECT).assertIsEnabled()
        // Scrolled to first: typing raises the IME, and the card's scroll container can leave the
        // button below the visible area, where an injected tap silently lands nowhere.
        compose.onNodeWithTag(AccountTags.CONNECT).performScrollTo().performClick()

        assertEquals(
            listOf(Triple("http://10.0.2.2:5444", "owner@example.com", "correct horse")),
            connectCalls,
        )
    }

    @Test
    fun theButtonShowsAProgressIndicatorInsteadOfItsLabelWhileTheServerAnswers() {
        setScreen()
        compose.onNodeWithTag(AccountTags.SELF_HOST).performClick()
        compose.onNodeWithTag(AccountTags.CONNECT_PROGRESS).assertDoesNotExist()

        connection = SelfHostConnection.Connecting

        compose.onNodeWithTag(AccountTags.CONNECT_PROGRESS).assertIsDisplayed()
        // Pressing again would register a second device on the server, not retry the first.
        compose.onNodeWithTag(AccountTags.CONNECT).assertIsNotEnabled()
    }

    @Test
    fun aFailureIsExplainedInTermsOfWhatToChange() {
        setScreen()
        compose.onNodeWithTag(AccountTags.SELF_HOST).performClick()
        compose.onNodeWithTag(AccountTags.CONNECT_STATUS).assertDoesNotExist()

        connection = SelfHostConnection.Failed(ConnectFailure.InvalidCredentials)

        compose.onNodeWithTag(AccountTags.CONNECT_STATUS)
            .assertTextContains(context.getString(R.string.account_error_credentials))

        connection = SelfHostConnection.Failed(ConnectFailure.Unreachable)

        compose.onNodeWithTag(AccountTags.CONNECT_STATUS)
            .assertTextContains(context.getString(R.string.account_error_unreachable))
    }

    /**
     * Connected is a different form, not a message appended to the old one: the fields describe a
     * connection to make, and once there is one they have nothing left to say.
     */
    @Test
    fun connectedReplacesTheFormWithItsStatusAndADisconnect() {
        setScreen()
        compose.onNodeWithTag(AccountTags.SELF_HOST).performClick()

        connection = SelfHostConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")

        compose.onNodeWithTag(AccountTags.CONNECTED).assertIsDisplayed()
        compose.onNodeWithTag(AccountTags.CONNECT_STATUS)
            .assertTextContains(context.getString(R.string.account_connected_title))
        compose.onNodeWithTag(AccountTags.DISCONNECT).assertIsDisplayed()
        compose.onNodeWithTag(AccountTags.SYNC).assertIsDisplayed()

        compose.onNodeWithTag(AccountTags.SERVER_URL).assertDoesNotExist()
        compose.onNodeWithTag(AccountTags.EMAIL).assertDoesNotExist()
        compose.onNodeWithTag(AccountTags.PASSWORD).assertDoesNotExist()
        compose.onNodeWithTag(AccountTags.CONNECT).assertDoesNotExist()
    }

    /** Both facts are on screen because both are needed to find the device on the server. */
    @Test
    fun connectedNamesTheServerAndTheDevice() {
        setScreen()

        connection = SelfHostConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")

        compose.onNodeWithText("http://10.0.2.2:5444").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.account_connected_device, "Pixel Tablet"))
            .assertIsDisplayed()
    }

    /** Arriving already connected must show it, not hide it behind anything. */
    @Test
    fun anExistingConnectionIsShownOnArrival() {
        connection = SelfHostConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")
        setScreen()

        compose.onNodeWithTag(AccountTags.CONNECTED).assertIsDisplayed()
    }

    @Test
    fun disconnectAsksFirstAndCanBeBackedOutOf() {
        setScreen()
        connection = SelfHostConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")

        compose.onNodeWithTag(AccountTags.DISCONNECT).performScrollTo().performClick()

        // Nothing has happened yet: the token cannot be reissued, so the tap only opens the question.
        assertEquals(0, disconnects)
        compose.onNodeWithText(context.getString(R.string.account_disconnect_cancel)).performClick()
        assertEquals(0, disconnects)

        compose.onNodeWithTag(AccountTags.DISCONNECT).performScrollTo().performClick()
        compose.onNodeWithTag(AccountTags.DISCONNECT_CONFIRM).performClick()

        assertEquals(1, disconnects)
    }

    /**
     * The way out of a server that never answers again.
     *
     * Without it the stored registration is a one-way door: it is what this screen shows instead of
     * the connect form, so a device whose server is offline could neither revoke itself nor move to
     * another server. The offer is deliberately not on screen before the ordinary revoke has been
     * tried and failed — it leaves a live device row behind on the server.
     */
    @Test
    fun aFailedRevokeOffersTheLocalOnlyWayOut() {
        setScreen()
        connection = SelfHostConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")

        compose.onNodeWithTag(AccountTags.DISCONNECT_ANYWAY).assertDoesNotExist()

        disconnectFailure = ConnectFailure.Unreachable

        // What stays behind is stated beside the button rather than behind a second dialog: the
        // person has already confirmed a disconnect, and this is the only part of it that differs.
        compose.onNodeWithText(context.getString(R.string.account_disconnect_anyway_note))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag(AccountTags.DISCONNECT_ANYWAY).performScrollTo().performClick()

        assertEquals(1, forceDisconnects)
        // The failed route is not retried on the way out; nothing goes to the server at all.
        assertEquals(0, disconnects)
    }

    @Test
    fun syncNowShowsProgressAndThenASummary() {
        setScreen()
        connection = SelfHostConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")

        compose.onNodeWithTag(AccountTags.SYNC).performScrollTo().performClick()
        assertEquals(1, syncs)

        syncing = true
        compose.onNodeWithTag(AccountTags.SYNC_PROGRESS).assertIsDisplayed()
        compose.onNodeWithTag(AccountTags.SYNC).assertIsNotEnabled()
        compose.onNodeWithTag(AccountTags.DISCONNECT).assertIsNotEnabled()

        syncing = false
        syncStatus = SyncStatus(
            lastSucceededAtMillis = System.currentTimeMillis(),
            lastSummary = SyncSummary(pulled = 2, pushed = 1, conflictsResolved = 0),
        )
        compose.onNodeWithTag(AccountTags.SYNC_STATUS).assertTextContains(
            context.getString(R.string.account_sync_just_now) +
                context.getString(R.string.account_sync_counts, 2, 1),
        )
    }

    /**
     * The status line answers for the clock as well as for the button, which is the entire reason it
     * reads from a shared status rather than from the last press. A sync that has been failing since
     * before this screen was opened has to be visible on opening it.
     */
    @Test
    fun aFailedBackgroundRunIsOnScreenWithoutAnybodyPressingSync() {
        setScreen()
        connection = SelfHostConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")

        syncStatus = SyncStatus(failure = SyncRunResult.Retryable(ConnectFailure.Unreachable))

        compose.onNodeWithTag(AccountTags.SYNC_STATUS).performScrollTo().assertTextContains(
            context.getString(
                R.string.account_sync_will_retry,
                context.getString(R.string.account_error_unreachable),
            ),
        )
        assertEquals(0, syncs)
    }

    /**
     * The same glyph the ribbon badges the account button with, beside the sentence explaining it —
     * so somebody who followed the badge here recognises what they followed.
     */
    @Test
    fun anUnreachableServerIsMarkedWithCloudOff() {
        setScreen()
        connection = SelfHostConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")

        syncStatus = SyncStatus(failure = SyncRunResult.Retryable(ConnectFailure.Unreachable))

        compose.onNodeWithTag(AccountTags.SYNC_OFFLINE).performScrollTo().assertIsDisplayed()
    }

    /**
     * And only for that state. A cloud with a line through it beside "the server rejected this
     * change" sends its reader to check the wifi, which is fine and is not the problem.
     */
    @Test
    fun aFailureThatIsNotTheConnectionCarriesNoCloudOff() {
        setScreen()
        connection = SelfHostConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")

        syncStatus = SyncStatus(
            failure = SyncRunResult.Failed(PermanentSyncFailure.UnsupportedKind),
        )

        compose.onNodeWithTag(AccountTags.SYNC_STATUS).performScrollTo()
        compose.onNodeWithTag(AccountTags.SYNC_OFFLINE).assertDoesNotExist()
    }

    /**
     * Connecting is an invitation, and there is nothing left to invite. Leaving Log in and Sign up on
     * a screen that is already connected offers a second account to an app that holds one.
     */
    @Test
    fun connectedHidesEveryWayToConnectAgain() {
        setScreen()
        connection = SelfHostConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")

        compose.onNodeWithTag(AccountTags.CONNECTED).assertIsDisplayed()
        compose.onNodeWithTag(AccountTags.LOGIN).assertDoesNotExist()
        compose.onNodeWithTag(AccountTags.SIGN_UP).assertDoesNotExist()
        compose.onNodeWithTag(AccountTags.SELF_HOST).assertDoesNotExist()
    }

    /**
     * Asserted through the button rather than by reading the password field, whose semantics carry
     * the masked text: back on the empty form, Connect has an empty required field again. The point
     * is that the token replaced the password in memory rather than joining it.
     */
    @Test
    fun connectingDropsThePassword() {
        setScreen()
        compose.onNodeWithTag(AccountTags.SELF_HOST).performClick()
        compose.onNodeWithTag(AccountTags.SERVER_URL).performTextInput("http://10.0.2.2:5444")
        compose.onNodeWithTag(AccountTags.EMAIL).performTextInput("owner@example.com")
        compose.onNodeWithTag(AccountTags.PASSWORD).performTextInput("correct horse")
        compose.onNodeWithTag(AccountTags.CONNECT).assertIsEnabled()

        connection = SelfHostConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")
        // Asserted, not just assigned: without a sync point here the two writes coalesce into one
        // recomposition that only ever sees Idle, and the effect that drops the password never runs.
        compose.onNodeWithTag(AccountTags.CONNECTED).assertIsDisplayed()

        connection = SelfHostConnection.Idle

        compose.onNodeWithTag(AccountTags.CONNECT).assertIsNotEnabled()
    }

    private fun setScreen() {
        compose.setContent {
            ViveNotesTheme {
                AccountScreen(
                    onBack = {},
                    connection = connection,
                    onConnect = { url, email, password ->
                        connectCalls += Triple(url, email, password)
                    },
                    syncing = syncing,
                    syncStatus = syncStatus,
                    onSync = { syncs++ },
                    disconnectFailure = disconnectFailure,
                    onDisconnect = { disconnects++ },
                    onForceDisconnect = { forceDisconnects++ },
                )
            }
        }
    }
}
