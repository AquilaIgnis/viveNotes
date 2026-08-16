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
import com.vivenotes.data.sync.SelfHostConnection
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

    /** Arriving already connected must show it, not hide it behind a collapsed row. */
    @Test
    fun anExistingConnectionOpensTheDisclosureByItself() {
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
                    onDisconnect = { disconnects++ },
                )
            }
        }
    }
}
