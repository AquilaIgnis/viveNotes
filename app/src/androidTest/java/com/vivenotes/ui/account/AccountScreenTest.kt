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
import com.vivenotes.data.billing.ManagedSubscriptionState
import com.vivenotes.data.sync.ConnectFailure
import com.vivenotes.data.sync.ManagedSubscriptionStatus
import com.vivenotes.data.sync.PaidSubscriptionState
import com.vivenotes.data.sync.PermanentSyncFailure
import com.vivenotes.data.sync.ServerConnection
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
    private var connection by mutableStateOf<ServerConnection>(ServerConnection.Idle)

    private val connectCalls = mutableListOf<Triple<String, String, String>>()
    private val logInCalls = mutableListOf<Pair<String, String>>()
    private val signUpCalls = mutableListOf<Pair<String, String>>()
    private var disconnects = 0
    private var forceDisconnects = 0
    private var disconnectFailure by mutableStateOf<ConnectFailure?>(null)
    private var syncs = 0
    private var syncing by mutableStateOf(false)
    private var syncStatus by mutableStateOf(SyncStatus())

    private var googleAvailable by mutableStateOf(true)
    private var signingInWithGoogle by mutableStateOf(false)
    private var googleFailure by mutableStateOf<ConnectFailure?>(null)
    private var googleSignIns = 0
    private var linkRequired by mutableStateOf(false)
    private var linking by mutableStateOf(false)
    private val linkCalls = mutableListOf<Pair<String, String>>()
    private var linkCancels = 0
    private var accountCreated by mutableStateOf(false)
    private var managedSubscription by mutableStateOf(ManagedSubscriptionState())
    private var subscriptions = 0
    private var managedSubscriptions = 0
    private val couponCodes = mutableListOf<String>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** The default offer, and the only button on the card until the disclosure is opened. */
    @Test
    fun googleSignInIsTheDefaultAction() {
        setScreen()

        compose.onNodeWithTag(AccountTags.GOOGLE).assertIsDisplayed().performClick()

        assertEquals(1, googleSignIns)
    }

    /**
     * One button for both, so the screen must not claim otherwise. The explainer is what tells
     * somebody with no account that pressing it will make them one.
     */
    @Test
    fun googleButtonSaysItBothSignsInAndRegisters() {
        setScreen()

        compose.onNodeWithText(context.getString(R.string.account_google_explainer))
            .assertIsDisplayed()
    }

    @Test
    fun googleButtonShowsProgressAndDisablesItselfWhileSigningIn() {
        setScreen()
        compose.onNodeWithTag(AccountTags.GOOGLE_PROGRESS).assertDoesNotExist()

        signingInWithGoogle = true

        compose.onNodeWithTag(AccountTags.GOOGLE_PROGRESS).assertIsDisplayed()
        compose.onNodeWithTag(AccountTags.GOOGLE).assertIsNotEnabled()
    }

    /**
     * A build with no Web client id shows the button disabled and says why, rather than letting the
     * press fail inside Credential Manager with an error that reads like the user's Google account
     * is at fault.
     */
    @Test
    fun googleSignInIsDisabledAndExplainedWhenTheBuildHasNoClientId() {
        setScreen()
        googleAvailable = false

        compose.onNodeWithTag(AccountTags.GOOGLE).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.account_google_unconfigured))
            .assertIsDisplayed()
    }

    @Test
    fun googleFailureIsExplainedUnderItsOwnButton() {
        setScreen()
        googleFailure = ConnectFailure.NoGoogleAccount

        compose.onNodeWithTag(AccountTags.GOOGLE_STATUS)
            .assertTextContains(context.getString(R.string.account_error_no_google_account))
    }

    /**
     * `409 account_link_required` has one resolution in the contract and this dialog is it. The
     * screen must not offer it before the server has asked for it — showing it unprompted would ask
     * for a password nothing is going to check.
     */
    @Test
    fun linkDialogAppearsOnlyWhenTheServerAsksForIt() {
        setScreen()
        compose.onNodeWithTag(AccountTags.LINK_DIALOG).assertDoesNotExist()

        linkRequired = true

        compose.onNodeWithTag(AccountTags.LINK_DIALOG).assertIsDisplayed()
        compose.onNodeWithTag(AccountTags.LINK_CONFIRM).assertIsNotEnabled()
    }

    @Test
    fun linkDialogSendsTheEmailAndPasswordItWasGiven() {
        setScreen()
        linkRequired = true

        compose.onNodeWithTag(AccountTags.LINK_EMAIL).performTextInput("owner@example.com")
        compose.onNodeWithTag(AccountTags.LINK_PASSWORD).performTextInput("correct horse")
        compose.onNodeWithTag(AccountTags.LINK_CONFIRM).performClick()

        assertEquals(listOf("owner@example.com" to "correct horse"), linkCalls)
    }

    /**
     * Cancelling is a real operation rather than just closing a dialog: `SyncAccounts` is holding a
     * Google ID token for as long as the link is pending, and only this drops it.
     */
    @Test
    fun cancellingTheLinkReportsItRatherThanOnlyClosing() {
        setScreen()
        linkRequired = true

        compose.onNodeWithTag(AccountTags.LINK_CANCEL).performClick()

        assertEquals(1, linkCancels)
        assertTrue(linkCalls.isEmpty())
    }

    /**
     * The one thing the single button cannot show any other way. Only the server knows whether it
     * found the account or made it, and it says so once.
     */
    @Test
    fun creatingAnAccountIsAnnouncedInsteadOfMerelyConnected() {
        setScreen()
        connection = ServerConnection.Connected("https://cloud.vivenotes.net", "Pixel Tablet")
        accountCreated = true

        compose.onNodeWithTag(AccountTags.CONNECT_STATUS)
            .assertTextContains(context.getString(R.string.account_connected_created))
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

        // Scrolled to first: the card now carries Google and the two managed buttons above this
        // disclosure, so Connect can open below the fold on a shorter screen.
        compose.onNodeWithTag(AccountTags.CONNECT).performScrollTo().assertIsDisplayed()
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

        connection = ServerConnection.Connecting

        compose.onNodeWithTag(AccountTags.CONNECT_PROGRESS).performScrollTo().assertIsDisplayed()
        // Pressing again would register a second device on the server, not retry the first.
        compose.onNodeWithTag(AccountTags.CONNECT).assertIsNotEnabled()
    }

    @Test
    fun aFailureIsExplainedInTermsOfWhatToChange() {
        setScreen()
        compose.onNodeWithTag(AccountTags.SELF_HOST).performClick()
        compose.onNodeWithTag(AccountTags.CONNECT_STATUS).assertDoesNotExist()

        connection = ServerConnection.Failed(ConnectFailure.InvalidCredentials)

        compose.onNodeWithTag(AccountTags.CONNECT_STATUS)
            .assertTextContains(context.getString(R.string.account_error_credentials))

        connection = ServerConnection.Failed(ConnectFailure.Unreachable)

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

        connection = ServerConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")

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

        connection = ServerConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")

        compose.onNodeWithText("http://10.0.2.2:5444").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.account_connected_device, "Pixel Tablet"))
            .assertIsDisplayed()
    }

    /** Arriving already connected must show it, not hide it behind anything. */
    @Test
    fun anExistingConnectionIsShownOnArrival() {
        connection = ServerConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")
        setScreen()

        compose.onNodeWithTag(AccountTags.CONNECTED).assertIsDisplayed()
    }

    @Test
    fun disconnectAsksFirstAndCanBeBackedOutOf() {
        setScreen()
        connection = ServerConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")

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
        connection = ServerConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")

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
        connection = ServerConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")

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
        connection = ServerConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")

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
        connection = ServerConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")

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
        connection = ServerConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")

        syncStatus = SyncStatus(
            failure = SyncRunResult.Failed(PermanentSyncFailure.UnsupportedKind),
        )

        compose.onNodeWithTag(AccountTags.SYNC_STATUS).performScrollTo()
        compose.onNodeWithTag(AccountTags.SYNC_OFFLINE).assertDoesNotExist()
    }

    /**
     * Connecting is an invitation, and there is nothing left to invite. Leaving any of the three
     * routes on a screen that is already connected offers a second account to an app that holds one.
     */
    @Test
    fun connectedHidesEveryWayToConnectAgain() {
        setScreen()
        connection = ServerConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")

        compose.onNodeWithTag(AccountTags.CONNECTED).assertIsDisplayed()
        compose.onNodeWithTag(AccountTags.GOOGLE).assertDoesNotExist()
        compose.onNodeWithTag(AccountTags.LOGIN).assertDoesNotExist()
        compose.onNodeWithTag(AccountTags.SIGN_UP).assertDoesNotExist()
        compose.onNodeWithTag(AccountTags.SELF_HOST).assertDoesNotExist()
    }

    @Test
    fun selfHostedConnectionNeverShowsManagedBilling() {
        connection = ServerConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")
        setScreen()

        compose.onNodeWithTag(AccountTags.CONNECTED).assertIsDisplayed()
        compose.onNodeWithTag(AccountTags.SUBSCRIPTION).assertDoesNotExist()
    }

    @Test
    fun managedPlanShowsPlayPriceAndStartsThePurchaseFlow() {
        connection = ServerConnection.Connected("https://cloud.vivenotes.net", "Pixel Tablet")
        managedSubscription = ManagedSubscriptionState(
            visible = true,
            formattedPrice = "\$4.99",
            productAvailable = true,
        )
        setScreen()

        compose.onNodeWithText(
            context.getString(R.string.account_subscription_price, "\$4.99"),
        ).performScrollTo().assertIsDisplayed()
        val planWidth = compose.onNodeWithTag(AccountTags.SUBSCRIPTION)
            .fetchSemanticsNode().boundsInRoot.width
        val purchase = compose.onNodeWithTag(AccountTags.SUBSCRIBE)
        purchase.performScrollTo()
        compose.onNodeWithTag(AccountTags.GOOGLE_PLAY_MARK, useUnmergedTree = true)
            .assertIsDisplayed()
        assertTrue(purchase.fetchSemanticsNode().boundsInRoot.width < planWidth)
        purchase.performClick()

        assertEquals(1, subscriptions)
    }

    @Test
    fun ownedPlayPlanIsManagedInPlayAndCouponDoesNotReplaceIt() {
        connection = ServerConnection.Connected("https://cloud.vivenotes.net", "Pixel Tablet")
        managedSubscription = ManagedSubscriptionState(
            visible = true,
            status = ManagedSubscriptionStatus(
                active = true,
                validUntil = "2026-12-03T12:00:00Z",
                paidState = PaidSubscriptionState.Active,
                paidValidUntil = "2026-10-03T12:00:00Z",
                promotionalValidUntil = "2026-12-03T12:00:00Z",
                autoRenewing = true,
                productId = "vivenotes_storage_monthly",
            ),
            formattedPrice = "\$4.99",
            productAvailable = true,
            playPurchaseOwned = true,
        )
        setScreen()

        compose.onNodeWithTag(AccountTags.SUBSCRIBE).assertDoesNotExist()
        compose.onNodeWithTag(AccountTags.MANAGE_SUBSCRIPTION)
            .performScrollTo()
            .performClick()
        assertEquals(1, managedSubscriptions)
        compose.onNodeWithTag(AccountTags.COUPON)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun couponFieldSubmitsTheTypedCode() {
        connection = ServerConnection.Connected("https://cloud.vivenotes.net", "Pixel Tablet")
        managedSubscription = ManagedSubscriptionState(visible = true)
        setScreen()

        compose.onNodeWithTag(AccountTags.COUPON).performScrollTo().performTextInput("FREE-MONTH")
        val planWidth = compose.onNodeWithTag(AccountTags.SUBSCRIPTION)
            .fetchSemanticsNode().boundsInRoot.width
        val couponWidth = compose.onNodeWithTag(AccountTags.COUPON)
            .fetchSemanticsNode().boundsInRoot.width
        val redeem = compose.onNodeWithTag(AccountTags.REDEEM_COUPON)
        redeem.performScrollTo()
        val redeemWidth = redeem.fetchSemanticsNode().boundsInRoot.width

        assertTrue(couponWidth < planWidth)
        assertTrue(redeemWidth < couponWidth)
        redeem.performClick()

        assertEquals(listOf("FREE-MONTH"), couponCodes)
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

        connection = ServerConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")
        // Asserted, not just assigned: without a sync point here the two writes coalesce into one
        // recomposition that only ever sees Idle, and the effect that drops the password never runs.
        compose.onNodeWithTag(AccountTags.CONNECTED).assertIsDisplayed()

        connection = ServerConnection.Idle

        compose.onNodeWithTag(AccountTags.CONNECT).assertIsNotEnabled()
    }

    /**
     * The managed account takes an email and a password and **no server address**. That absence is
     * the whole separation: a hostname belongs to Self host, which is somebody else's server.
     */
    @Test
    fun loggingInToTheManagedAccountNeverAsksForAServerAddress() {
        setScreen()

        compose.onNodeWithTag(AccountTags.LOGIN).performClick()

        compose.onNodeWithTag(AccountTags.CLOUD_EMAIL).assertIsDisplayed()
        compose.onNodeWithTag(AccountTags.CLOUD_PASSWORD).assertIsDisplayed()
        compose.onNodeWithTag(AccountTags.SERVER_URL).assertDoesNotExist()
    }

    @Test
    fun loggingInReportsWhatWasTypedAndNothingElse() {
        setScreen()
        compose.onNodeWithTag(AccountTags.LOGIN).performClick()
        compose.onNodeWithTag(AccountTags.CLOUD_EMAIL).performTextInput("owner@example.com")
        compose.onNodeWithTag(AccountTags.CLOUD_PASSWORD).performTextInput("correct horse")

        compose.onNodeWithTag(AccountTags.CLOUD_SUBMIT).performScrollTo().performClick()

        assertEquals(listOf("owner@example.com" to "correct horse"), logInCalls)
        // The two are not interchangeable: signing up would create an account first.
        assertTrue(signUpCalls.isEmpty())
    }

    @Test
    fun signingUpUsesItsOwnCallbackAndRelabelsTheButton() {
        setScreen()
        compose.onNodeWithTag(AccountTags.SIGN_UP).performClick()
        compose.onNodeWithTag(AccountTags.CLOUD_SUBMIT)
            .assertTextContains(context.getString(R.string.account_create))
        compose.onNodeWithTag(AccountTags.CLOUD_EMAIL).performTextInput("owner@example.com")
        compose.onNodeWithTag(AccountTags.CLOUD_PASSWORD).performTextInput("correct horse")
        compose.onNodeWithTag(AccountTags.CLOUD_CONFIRM).performScrollTo()
            .performTextInput("correct horse")

        compose.onNodeWithTag(AccountTags.CLOUD_SUBMIT).performScrollTo().performClick()

        assertEquals(listOf("owner@example.com" to "correct horse"), signUpCalls)
        assertTrue(logInCalls.isEmpty())
    }

    /**
     * A mistyped new password cannot be recovered — the server keeps an Argon2id hash and this
     * contract has no reset — so the second field is the one chance to catch it before it becomes an
     * account nobody can get into.
     */
    @Test
    fun signingUpWaitsForTheTwoPasswordsToMatch() {
        setScreen()
        compose.onNodeWithTag(AccountTags.SIGN_UP).performClick()
        compose.onNodeWithTag(AccountTags.CLOUD_EMAIL).performTextInput("owner@example.com")
        compose.onNodeWithTag(AccountTags.CLOUD_PASSWORD).performTextInput("correct horse")

        // Long enough on its own, so only the confirmation is holding the button back.
        compose.onNodeWithTag(AccountTags.CLOUD_SUBMIT).performScrollTo().assertIsNotEnabled()

        // One character short of the password, so it is wrong now and correct after the append —
        // `performTextInput` adds at the end, so the difference has to be at the end.
        compose.onNodeWithTag(AccountTags.CLOUD_CONFIRM).performScrollTo()
            .performTextInput("correct hors")
        compose.onNodeWithTag(AccountTags.CLOUD_SUBMIT).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.account_password_mismatch))
            .assertIsDisplayed()

        compose.onNodeWithTag(AccountTags.CLOUD_CONFIRM).performScrollTo().performTextInput("e")
        compose.onNodeWithTag(AccountTags.CLOUD_SUBMIT).performScrollTo().assertIsEnabled()
    }

    /**
     * An empty second field is unfinished, not wrong. Colouring it the moment the first one is
     * filled scolds somebody who is still typing.
     */
    @Test
    fun theConfirmationStaysQuietUntilSomethingIsTypedInIt() {
        setScreen()
        compose.onNodeWithTag(AccountTags.SIGN_UP).performClick()
        compose.onNodeWithTag(AccountTags.CLOUD_PASSWORD).performTextInput("correct horse")

        compose.onNodeWithText(context.getString(R.string.account_password_mismatch))
            .assertDoesNotExist()
    }

    /** Logging in checks a password that already exists, so a second field would be noise. */
    @Test
    fun loggingInHasNoConfirmationField() {
        setScreen()

        compose.onNodeWithTag(AccountTags.LOGIN).performClick()

        compose.onNodeWithTag(AccountTags.CLOUD_PASSWORD).assertIsDisplayed()
        compose.onNodeWithTag(AccountTags.CLOUD_CONFIRM).assertDoesNotExist()
    }

    /**
     * Switching modes empties it. A confirmation left over from a previous pass would silently
     * satisfy a check the person did not make this time.
     */
    @Test
    fun switchingModesClearsTheConfirmation() {
        setScreen()
        compose.onNodeWithTag(AccountTags.SIGN_UP).performClick()
        compose.onNodeWithTag(AccountTags.CLOUD_EMAIL).performTextInput("owner@example.com")
        compose.onNodeWithTag(AccountTags.CLOUD_PASSWORD).performTextInput("correct horse")
        compose.onNodeWithTag(AccountTags.CLOUD_CONFIRM).performScrollTo()
            .performTextInput("correct horse")
        compose.onNodeWithTag(AccountTags.CLOUD_SUBMIT).performScrollTo().assertIsEnabled()

        // Scrolled to before each press: the assertion above scrolled the card down, and a tap
        // injected at a button that is now above the viewport lands nowhere and fails silently.
        compose.onNodeWithTag(AccountTags.LOGIN).performScrollTo().performClick()
        compose.onNodeWithTag(AccountTags.SIGN_UP).performScrollTo().performClick()

        compose.onNodeWithTag(AccountTags.CLOUD_SUBMIT).performScrollTo().assertIsNotEnabled()
    }

    /** The two share one panel, so pressing the other button must switch it rather than stack it. */
    @Test
    fun logInAndSignUpShareOnePanelAndSwitchBetweenThemselves() {
        setScreen()

        compose.onNodeWithTag(AccountTags.LOGIN).performClick()
        compose.onNodeWithTag(AccountTags.CLOUD_SUBMIT)
            .assertTextContains(context.getString(R.string.account_log_in))

        compose.onNodeWithTag(AccountTags.SIGN_UP).performClick()

        compose.onNodeWithTag(AccountTags.CLOUD_SUBMIT)
            .assertTextContains(context.getString(R.string.account_create))
        compose.onNodeWithTag(AccountTags.CLOUD_EMAIL).assertIsDisplayed()
    }

    /** They are alternatives, and both open at once would show two emails and two submit buttons. */
    @Test
    fun openingSelfHostClosesTheManagedPanel() {
        setScreen()
        compose.onNodeWithTag(AccountTags.LOGIN).performClick()

        compose.onNodeWithTag(AccountTags.SELF_HOST).performScrollTo().performClick()

        compose.onNodeWithTag(AccountTags.SERVER_URL).assertIsDisplayed()
        compose.onNodeWithTag(AccountTags.CLOUD_EMAIL).assertDoesNotExist()
    }

    /**
     * The contract's eight-character minimum, refused here rather than by the server: a shorter one
     * has exactly one possible answer, and spending a round trip on it is spending it to be told so.
     */
    @Test
    fun signingUpWaitsForAPasswordTheServerWouldAccept() {
        setScreen()
        compose.onNodeWithTag(AccountTags.SIGN_UP).performClick()
        compose.onNodeWithTag(AccountTags.CLOUD_EMAIL).performTextInput("owner@example.com")

        // Seven characters: one short, which is the boundary worth testing rather than a
        // comfortably wrong length. Confirmed as it goes, so length is the only thing under test.
        compose.onNodeWithTag(AccountTags.CLOUD_PASSWORD).performTextInput("shorter")
        compose.onNodeWithTag(AccountTags.CLOUD_CONFIRM).performScrollTo()
            .performTextInput("shorter")
        compose.onNodeWithTag(AccountTags.CLOUD_SUBMIT).performScrollTo().assertIsNotEnabled()

        // `performTextInput` appends at the cursor, so these make it exactly eight.
        compose.onNodeWithTag(AccountTags.CLOUD_PASSWORD).performTextInput("s")
        compose.onNodeWithTag(AccountTags.CLOUD_CONFIRM).performScrollTo().performTextInput("s")
        compose.onNodeWithTag(AccountTags.CLOUD_SUBMIT).performScrollTo().assertIsEnabled()
    }

    /**
     * Logging in accepts any password, because it is checking one that already exists. Showing the
     * new-password rule there would state a requirement about the wrong password.
     */
    @Test
    fun loggingInAcceptsAnyPasswordAndDoesNotShowTheMinimum() {
        setScreen()
        compose.onNodeWithTag(AccountTags.LOGIN).performClick()
        compose.onNodeWithTag(AccountTags.CLOUD_EMAIL).performTextInput("owner@example.com")
        compose.onNodeWithTag(AccountTags.CLOUD_PASSWORD).performTextInput("short")

        compose.onNodeWithTag(AccountTags.CLOUD_SUBMIT).performScrollTo().assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.account_password_minimum))
            .assertDoesNotExist()
    }

    /**
     * The one failure that leaves something behind on the server. Repeating Sign up would only say
     * `email_taken`, so the message has to name the way in that now exists.
     */
    @Test
    fun anAccountCreatedWithoutItsDeviceSaysToLogInInstead() {
        setScreen()
        compose.onNodeWithTag(AccountTags.SIGN_UP).performClick()
        connection = ServerConnection.Failed(ConnectFailure.AccountCreatedNotRegistered)

        compose.onNodeWithTag(AccountTags.CLOUD_STATUS)
            .assertTextContains(context.getString(R.string.account_error_created_not_registered))
    }

    @Test
    fun aServerWithRegistrationsClosedIsExplainedRatherThanBlamedOnCredentials() {
        setScreen()
        compose.onNodeWithTag(AccountTags.SIGN_UP).performClick()
        connection = ServerConnection.Failed(ConnectFailure.SignupClosed)

        compose.onNodeWithTag(AccountTags.CLOUD_STATUS)
            .assertTextContains(context.getString(R.string.account_error_signup_closed))
    }

    private fun setScreen() {
        compose.setContent {
            ViveNotesTheme {
                AccountScreen(
                    onBack = {},
                    googleAvailable = googleAvailable,
                    signingInWithGoogle = signingInWithGoogle,
                    onSignInWithGoogle = { googleSignIns++ },
                    googleFailure = googleFailure,
                    linkRequired = linkRequired,
                    linking = linking,
                    onLinkAccount = { email, password -> linkCalls += email to password },
                    onCancelLink = { linkCancels++ },
                    accountCreated = accountCreated,
                    connection = connection,
                    managedSubscription = managedSubscription,
                    onSubscribe = { subscriptions++ },
                    onManageSubscription = { managedSubscriptions++ },
                    onRedeemCoupon = { couponCodes += it },
                    onConnect = { url, email, password ->
                        connectCalls += Triple(url, email, password)
                    },
                    onLogIn = { email, password -> logInCalls += email to password },
                    onSignUp = { email, password -> signUpCalls += email to password },
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
