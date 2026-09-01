package com.vivenotes.data.sync

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.vivenotes.BuildConfig
import com.vivenotes.data.AttachmentStore
import com.vivenotes.data.db.NotesDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Where this installation stands with **a** server, managed or self-hosted.
 *
 * One type for both, because the connected state genuinely is one thing: a base URL and a device
 * token, reached either by Sign in with Google against the managed deployment or by registering an
 * account on a server the user runs. Everything past the sign-in — the cursor, the outbox, the
 * blobs — cannot tell them apart and must not have to.
 *
 * [Connecting] is here rather than being the screen's own boolean because the request outlives the
 * screen on purpose — see [SyncAccounts.connect] — so "a connection is in flight" is a fact about
 * the app, not about whether a composable is on screen.
 */
sealed interface ServerConnection {

    /** Never connected, or the last attempt's result has been dismissed. */
    data object Idle : ServerConnection

    data object Connecting : ServerConnection

    data class Connected(val serverUrl: String, val deviceName: String) : ServerConnection

    data class Failed(val reason: ConnectFailure) : ServerConnection
}

/**
 * How one pass through Sign in with Google ended.
 *
 * Separate from [ServerConnection] because two of its outcomes are not connection states at all:
 * a dismissal is the person changing their mind, and a link requirement is the contract handing the
 * client a second request to make. Both would have to be squeezed into `Failed` otherwise, and the
 * screen would show an error for each.
 */
sealed interface CloudSignInResult {

    /**
     * Signed in and enrolled. [createdAccount] is the server's word for which of the two things the
     * one button just did, so the screen can say "Account created" rather than guessing.
     */
    data class Connected(
        val serverUrl: String,
        val deviceName: String,
        val createdAccount: Boolean,
    ) : CloudSignInResult

    /**
     * `409 account_link_required`: this Google email already belongs to a password account.
     *
     * [SyncAccounts] is now holding the unconsumed challenge and the ID token, waiting for
     * [SyncAccounts.linkGoogleAccount] or [SyncAccounts.cancelGoogleLink]. It is holding a Google ID
     * token in memory for as long as that lasts, which is why cancelling is a real operation and not
     * just closing a dialog.
     */
    data object LinkRequired : CloudSignInResult

    /** The Google sheet was dismissed. Nothing was sent; the screen says nothing. */
    data object Dismissed : CloudSignInResult

    data class Failed(val reason: ConnectFailure) : CloudSignInResult
}

sealed interface DisconnectResult {
    data object Disconnected : DisconnectResult
    data class Failed(val reason: ConnectFailure) : DisconnectResult
}

/**
 * How synchronisation is going, for any screen that wants to say so.
 *
 * Every run reports here — the foreground clock, the WorkManager catch-up and the Sync now button
 * alike — because the ones a person never asked for are exactly the ones that need somewhere to be
 * seen. Before this existed, a device whose sync had been failing for forty minutes looked identical
 * to one with nothing to do, and the only way to find out was to open Account and press a button.
 *
 * Deliberately in memory and not persisted. The alternative is a row written every interval — five
 * seconds apart in debug builds — to answer a question that the first run after launch answers
 * anyway, and it does so within a second of the app coming to the foreground.
 */
data class SyncStatus(
    /** True for the length of a run, whoever started it. */
    val running: Boolean = false,
    /** When a run last reconciled everything, or null if none has since this process started. */
    val lastSucceededAtMillis: Long? = null,
    /** What that run moved. Zero on both counts is the ordinary case and says "nothing to do". */
    val lastSummary: SyncSummary? = null,
    /** The last run's verdict when it was not a success. Cleared by the next success. */
    val failure: SyncRunResult? = null,
) {

    /**
     * Whether the last run could not reach the server at all — the Cloud Off state.
     *
     * Narrower than "sync is failing", deliberately. The badge this drives says *the server is not
     * there*, and the only failures that mean that are the two transport ones: nothing answered
     * ([ConnectFailure.Unreachable] — offline, wrong port, server down), and something answered that
     * was not viveCServer ([ConnectFailure.NotAViveServer] — a proxy error page, a captive portal).
     * Both leave this tablet holding writes it cannot deliver, which is the fact worth showing on
     * every screen rather than only inside Account.
     *
     * Everything else is excluded because a cloud with a line through it would misdescribe it. A
     * 5xx, a revoked token or a change this build cannot store all mean the server *is* reachable
     * and something else is wrong; each has its own sentence on the account screen, and each needs
     * a different thing done about it. `InvalidAddress` and `NotStored` cannot arise from a run at
     * all — they belong to connecting — but they are named here so this stays exhaustive rather
     * than falling through an `else`.
     */
    val serverUnreachable: Boolean
        get() = when ((failure as? SyncRunResult.Retryable)?.reason) {
            ConnectFailure.Unreachable, ConnectFailure.NotAViveServer -> true

            ConnectFailure.InvalidAddress,
            ConnectFailure.InvalidCredentials,
            ConnectFailure.InvalidRequest,
            ConnectFailure.PayloadTooLarge,
            ConnectFailure.ServerError,
            ConnectFailure.NotStored,
            ConnectFailure.Revoked,
            ConnectFailure.SignupClosed,
            ConnectFailure.EmailTaken,
            ConnectFailure.AccountCreatedNotRegistered,
            // The Google failures cannot arise from a sync run either — nothing in the protocol
            // touches those endpoints — but they are named rather than caught by an `else` so this
            // stays exhaustive and a new failure has to be considered here.
            ConnectFailure.InvalidChallenge,
            ConnectFailure.InvalidGoogleToken,
            ConnectFailure.GoogleUnavailable,
            ConnectFailure.IdentityConflict,
            ConnectFailure.IdempotencyConflict,
            ConnectFailure.AccountUnavailable,
            ConnectFailure.NoGoogleAccount,
            ConnectFailure.GoogleNotConfigured,
            -> false

            // Not a retryable failure: a success, a permanent failure, a revocation, or no run yet.
            null -> false
        }
}

/**
 * Connecting this installation to a self-hosted viveCServer, start to finish.
 *
 * Ties [SyncServerClient] to [SyncAccountStore] so that no caller can do half of it: the token is
 * written to disk *before* success is reported, because it is returned exactly once and a caller
 * that reports success first and stores second has a window in which the only copy of a
 * non-reissuable credential exists on the stack.
 */
class SyncAccounts(
    context: Context,
    private val client: SyncServerClient = SyncServerClient(),
    database: NotesDatabase? = null,
    /**
     * Where attachment bytes live, so a run can carry pictures as well as rows.
     *
     * Defaulted rather than required because the screens' own tests build this class without a
     * database at all; with one, the app passes the same [AttachmentStore] the editor draws from,
     * so a picture downloaded here announces itself to the canvas already showing its frame.
     */
    attachments: AttachmentBytes? = null,
    private val deviceName: String = defaultDeviceName(context),
    private val platform: String = defaultPlatform(),
    /**
     * The Android half of Sign in with Google. Injectable because Credential Manager needs a real
     * Activity and a real Google account to answer, which no test on this side has.
     */
    private val google: GoogleIdentityProvider = GoogleIdentityProvider(),
) {

    private val appContext = context.applicationContext
    private val store = SyncAccountStore(context)
    private val remoteInkSignal = RemoteInkSignal()
    private val hierarchy = database?.let { db ->
        HierarchySync(
            db = db,
            client = client,
            remoteInk = remoteInkSignal,
            blobs = AttachmentBlobSync(db, client, attachments ?: AttachmentStore(appContext, db)),
        )
    }

    /**
     * Pages the server has written ink into, by generation — `memory/inkSyncPlan.md` IS5.
     *
     * Held here because this is the one object both clocks run through: the foreground scheduler and
     * WorkManager's catch-up both call [synchronize] on this instance, in this process, so an open
     * canvas hears about a pulled stroke whichever of them pulled it.
     */
    val remoteInk: StateFlow<Map<String, Long>> = remoteInkSignal.pages

    /** Null until connected. Survives launches; cleared by [disconnect] or [forgetConnection]. */
    val account: Flow<SyncAccount?> = store.account

    /**
     * The managed deployment this build talks to — `http://10.0.2.2:5444` in debug,
     * `https://cloud.vivenotes.net` in release, either overridable from `local.properties`.
     *
     * Put through [normaliseServerAddress] like a typed address, so a trailing slash or a capital in
     * the host cannot produce two spellings of one server between a debug override and the default.
     * The unnormalised value is kept as the fallback rather than throwing: a build configured with a
     * malformed URL should fail its first request with `InvalidAddress`, which names the problem,
     * rather than crash on launch somewhere unrelated.
     */
    val cloudServerUrl: String =
        normaliseServerAddress(BuildConfig.CLOUD_BASE_URL) ?: BuildConfig.CLOUD_BASE_URL

    /** False when this build has no Google Web client id; the screen says so rather than failing. */
    val googleSignInAvailable: Boolean get() = google.configured

    /**
     * The half-finished Google sign-in that `409 account_link_required` leaves behind.
     *
     * In memory and nowhere else. It holds a Google ID token, which is a bearer credential for the
     * few minutes it lives, so it is never persisted and is dropped the moment the link succeeds,
     * fails, or is cancelled. `@Volatile` because the dialog's answer arrives on a different
     * coroutine from the sign-in that set it.
     */
    @Volatile
    private var pendingLink: PendingGoogleLink? = null

    private val _status = MutableStateFlow(SyncStatus())

    /** How synchronisation is going. See [SyncStatus] for why this is not persisted. */
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /**
     * Registers this device and stores the token it gets back.
     *
     * Repeating this against the same server registers a *second* device rather than refreshing the
     * first: `POST /v1/devices` mints a new row every time, by design, so the previous token stays
     * valid and its device stays in the server's list until somebody revokes it. That is the
     * server's contract, not something to paper over here — but it is the reason this must not be
     * called speculatively or retried automatically.
     *
     * The caller is expected to run this in a scope that outlives the account screen. Cancelling
     * mid-flight cannot un-register the device, so a cancelled call is how an orphan row is created.
     */
    suspend fun connect(
        typedAddress: String,
        email: String,
        password: String,
    ): ServerConnection {
        val serverUrl = normaliseServerAddress(typedAddress)
            ?: return ServerConnection.Failed(ConnectFailure.InvalidAddress)

        return when (
            val registration = client.registerDevice(
                serverBaseUrl = serverUrl,
                email = email,
                password = password,
                deviceName = deviceName,
                platform = platform,
            )
        ) {
            is DeviceRegistration.Registered -> when (
                val unstored = adopt(
                    serverUrl = serverUrl,
                    accountId = registration.accountId,
                    deviceId = registration.deviceId,
                    token = registration.token,
                )
            ) {
                null -> ServerConnection.Connected(serverUrl, deviceName)
                else -> ServerConnection.Failed(unstored)
            }

            is DeviceRegistration.Rejected -> ServerConnection.Failed(registration.reason)
        }
    }

    /**
     * Creates an account on a server and connects this installation to it, in that order.
     *
     * Two requests, because the contract is two requests: `POST /v1/accounts` mints an account and
     * **no credential at all**, and `POST /v1/devices` is the only endpoint that returns a token.
     * They are joined here rather than in the screen so that nobody can do half of it — a created
     * account with no device is the one outcome of this flow that leaves something behind on the
     * server.
     *
     * That outcome is reported as [ConnectFailure.AccountCreatedNotRegistered] rather than as
     * whatever the second request said, because the instruction it needs is neither request's
     * message: the account exists, and the way in is now Sign in. Pressing Create account again
     * would only answer `email_taken`.
     *
     * [ConnectFailure.NotStored] is the exception and keeps its own meaning — the device *did*
     * register, and the thing to fix is local storage rather than anything on the server.
     */
    suspend fun register(
        typedAddress: String,
        email: String,
        password: String,
    ): ServerConnection {
        val serverUrl = normaliseServerAddress(typedAddress)
            ?: return ServerConnection.Failed(ConnectFailure.InvalidAddress)

        when (val created = client.createAccount(serverUrl, email, password)) {
            is AccountCreation.Created -> Unit
            is AccountCreation.Rejected -> return ServerConnection.Failed(created.reason)
        }

        return when (
            val registration = client.registerDevice(
                serverBaseUrl = serverUrl,
                email = email,
                password = password,
                deviceName = deviceName,
                platform = platform,
            )
        ) {
            is DeviceRegistration.Registered -> when (
                val unstored = adopt(
                    serverUrl = serverUrl,
                    accountId = registration.accountId,
                    deviceId = registration.deviceId,
                    token = registration.token,
                )
            ) {
                null -> ServerConnection.Connected(serverUrl, deviceName)
                else -> ServerConnection.Failed(unstored)
            }

            is DeviceRegistration.Rejected ->
                ServerConnection.Failed(ConnectFailure.AccountCreatedNotRegistered)
        }
    }

    /**
     * Logs in to the **managed** deployment with an email and a password.
     *
     * [connect] with the address supplied rather than typed. That is the entire difference, and it
     * is the point: a managed account is not something a person should have to know a hostname for,
     * and the app already knows the one hostname there is.
     *
     * Carries [connect]'s warning unchanged — `POST /v1/devices` mints a new device row every time,
     * so this must not be called speculatively or retried automatically.
     */
    suspend fun logInToCloud(email: String, password: String): ServerConnection =
        connect(cloudServerUrl, email, password)

    /** [register] against the managed deployment: create the account, then connect this device. */
    suspend fun signUpForCloud(email: String, password: String): ServerConnection =
        register(cloudServerUrl, email, password)

    /**
     * Signs in to the managed deployment with Google, creating the account if there is not one yet.
     *
     * One method for logging in and registering, because `POST /v1/auth/google` is one endpoint for
     * both: the server finds the account by `(issuer, sub)` or creates it. Nothing here has to ask
     * which the person meant, and nothing here can get it wrong.
     *
     * The order is fixed by the contract and is the whole security story of this flow. The challenge
     * is fetched **first**, its nonce goes into the sheet, and Google seals it into the signed token;
     * a token that does not carry the challenge this server issued seconds ago is refused. So the
     * challenge cannot be cached, reordered, or reused.
     *
     * [activityContext] must be an Activity — Credential Manager has UI to show. Like [connect],
     * this is expected to run in a scope that outlives the account screen: the device token comes
     * back once and a cancelled call is how an unusable device row is created.
     */
    suspend fun signInWithGoogle(activityContext: Context): CloudSignInResult {
        val serverUrl = cloudServerUrl
        pendingLink = null

        val challenge = when (val requested = client.createGoogleChallenge(serverUrl)) {
            is ServerResult.Success -> requested.value
            // This endpoint takes no credential, so a 401 from it is not a revocation — it is a
            // server that is not viveCServer, or a proxy in front of one.
            ServerResult.Unauthorized -> return CloudSignInResult.Failed(ConnectFailure.NotAViveServer)
            is ServerResult.Failed -> return CloudSignInResult.Failed(requested.reason)
        }

        val idToken = when (val credential = google.requestIdToken(activityContext, challenge.nonce)) {
            is GoogleIdToken.Received -> credential.idToken
            GoogleIdToken.Dismissed -> return CloudSignInResult.Dismissed
            is GoogleIdToken.Rejected -> return CloudSignInResult.Failed(credential.reason)
        }

        val device = googleDeviceDetails()
        val authentication = client.signInWithGoogle(
            serverBaseUrl = serverUrl,
            challengeId = challenge.challengeId,
            idToken = idToken,
            // New per logical attempt. It is not reused across retries here because there is no
            // retry here: a lost response leaves the person pressing the button again, which is a
            // new attempt with a new challenge and therefore a new key.
            idempotencyKey = UUID.randomUUID().toString(),
            device = device,
        )

        return when (authentication) {
            is GoogleAuthentication.Authenticated -> adoptGoogle(serverUrl, authentication)

            is GoogleAuthentication.LinkRequired -> {
                // The challenge is deliberately still unspent, so the link request can prove the
                // same Google session rather than opening a second sheet.
                pendingLink = PendingGoogleLink(
                    serverUrl = serverUrl,
                    challengeId = challenge.challengeId,
                    idToken = idToken,
                    device = device,
                )
                CloudSignInResult.LinkRequired
            }

            is GoogleAuthentication.Rejected -> CloudSignInResult.Failed(authentication.reason)
        }
    }

    /**
     * Finishes a sign-in that came back `account_link_required`, by proving the password account.
     *
     * The email is asked for rather than read out of the ID token. The token's claims are unverified
     * until the server checks Google's signature over them, and the server is going to compare this
     * email against the verified one anyway — so typing it costs one field and keeps the client from
     * ever treating an unverified claim as a fact. A mismatch comes back as `InvalidCredentials`,
     * which is what the message on that failure has to account for.
     *
     * Returns [ConnectFailure.InvalidChallenge] with no request sent when there is nothing pending:
     * the challenge behind a link expires in five minutes, and a dialog left open outlives it.
     */
    suspend fun linkGoogleAccount(email: String, password: String): CloudSignInResult {
        val pending = pendingLink ?: return CloudSignInResult.Failed(ConnectFailure.InvalidChallenge)

        val authentication = client.linkGoogleIdentity(
            serverBaseUrl = pending.serverUrl,
            challengeId = pending.challengeId,
            idToken = pending.idToken,
            // A *new* key: the request content differs from the sign-in that was refused, and
            // reusing that key would be `409 idempotency_conflict` rather than a retry.
            idempotencyKey = UUID.randomUUID().toString(),
            device = pending.device,
            email = email,
            password = password,
        )

        return when (authentication) {
            is GoogleAuthentication.Authenticated -> {
                pendingLink = null
                adoptGoogle(pending.serverUrl, authentication)
            }

            // The link endpoint answering "link required" would mean the server changed its mind
            // about the same identity. Nothing sensible follows, so it is reported as a failure
            // rather than looping the dialog back onto itself.
            is GoogleAuthentication.LinkRequired -> {
                pendingLink = null
                CloudSignInResult.Failed(ConnectFailure.IdentityConflict)
            }

            // The pending link is kept: a wrong password is usually one character, and dropping the
            // ID token here would make correcting it mean a whole new sign-in.
            is GoogleAuthentication.Rejected -> CloudSignInResult.Failed(authentication.reason)
        }
    }

    /**
     * Drops a pending link, and the Google ID token it is holding.
     *
     * Called when the dialog is dismissed. Closing the dialog without this would leave a bearer
     * credential in memory for as long as the process lives.
     */
    fun cancelGoogleLink() {
        pendingLink = null
    }

    /** The shared tail of both Google routes: store the token, activate, and start syncing. */
    private suspend fun adoptGoogle(
        serverUrl: String,
        authentication: GoogleAuthentication.Authenticated,
    ): CloudSignInResult = when (
        val unstored = adopt(
            serverUrl = serverUrl,
            accountId = authentication.accountId,
            deviceId = authentication.deviceId,
            token = authentication.token,
        )
    ) {
        null -> CloudSignInResult.Connected(
            serverUrl = serverUrl,
            deviceName = deviceName,
            createdAccount = authentication.createdAccount,
        )

        else -> CloudSignInResult.Failed(unstored)
    }

    /**
     * Writes a freshly issued device token to disk and brings sync up on it.
     *
     * Returns null on success, or the [ConnectFailure] that must be reported instead. Shared by
     * every route that ends in a token — password registration, Google sign-in, Google linking —
     * because the order matters identically in all three and getting it wrong is expensive: the
     * token is written **before** success is reported, since it is returned exactly once and the
     * server keeps only its SHA-256. A caller that reported success first would have a window in
     * which the only copy of a non-reissuable credential lives on the stack.
     */
    private suspend fun adopt(
        serverUrl: String,
        accountId: String,
        deviceId: String,
        token: String,
    ): ConnectFailure? {
        try {
            store.setAccount(
                SyncAccount(
                    serverUrl = serverUrl,
                    accountId = accountId,
                    deviceId = deviceId,
                    token = token,
                    deviceName = deviceName,
                ),
            )
        } catch (unwritable: IOException) {
            // The device exists on the server and its token is now unrecoverable. Reporting success
            // would leave the app claiming a connection it cannot authenticate.
            return ConnectFailure.NotStored
        }

        // Activation is local and idempotent. If it fails after the token is safely stored, the
        // scheduled worker retries it; the registration itself still succeeded.
        try {
            hierarchy?.activate(accountId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The token is already durable. The scheduled worker retries local activation.
        }
        HierarchySyncWorker.requestNow(appContext)
        return null
    }

    /**
     * What the server's device list will show for this installation on the Google routes.
     *
     * Richer than the two fields `POST /v1/devices` takes, because the list is read by somebody
     * deciding which row to revoke and `Build.MODEL` alone regularly names two of them. The
     * installation id is the part that does real work: it is stable across signing out and back in,
     * so a second sign-in rotates this device row instead of adding another one.
     */
    private suspend fun googleDeviceDetails(): GoogleDeviceDetails = GoogleDeviceDetails(
        installationId = store.installationId(),
        name = deviceName,
        platform = platform,
        appVersion = BuildConfig.VERSION_NAME,
        appBuild = BuildConfig.VERSION_CODE.toString(),
        osVersion = Build.VERSION.RELEASE,
        model = Build.MODEL,
        architecture = Build.SUPPORTED_ABIS?.firstOrNull(),
        locale = Locale.getDefault().toLanguageTag(),
    )

    /**
     * Re-checks the stored registration against the server, and forgets it if it has been revoked.
     *
     * Revocation is one-sided: the operator removes a device from the dashboard, and this
     * installation finds out only by being told `401 unauthenticated` — "unknown or revoked token".
     * Holding that token afterwards is worse than holding nothing, because every later request fails
     * identically and the app goes on claiming to be connected. So a revoked token is deleted, which
     * puts the account screen back on its sign-in form; registering again mints a new device, and
     * the revoked one stays in the server's history.
     *
     * **Only an explicit 401 clears the store.** Offline, DNS failure, a 5xx from a proxy: all leave
     * the registration exactly where it was. The token cannot be reissued, so anything less than the
     * server itself saying no is not enough to throw it away — a tablet out of wifi range must come
     * back still connected.
     *
     * Returns null when there is nothing stored to check.
     */
    suspend fun refresh(): ServerConnection? {
        val account = store.account.first() ?: return null

        return when (client.checkToken(account.serverUrl, account.token)) {
            TokenCheck.Valid -> ServerConnection.Connected(account.serverUrl, account.deviceName)

            TokenCheck.Revoked -> {
                forget(account)
                ServerConnection.Failed(ConnectFailure.Revoked)
            }

            // Unreachable, or a server-side error. Nothing was learned, so nothing changes: the
            // stored registration still stands and the screen goes on showing it as connected.
            is TokenCheck.Unknown ->
                ServerConnection.Connected(account.serverUrl, account.deviceName)
        }
    }

    /**
     * Revokes this installation on the server before forgetting its non-reissuable token.
     *
     * Network and 5xx failures leave the credential standing so the user can try again. A 401 means
     * it was already revoked and is therefore the other successful local-disconnect path.
     *
     * Asking the server first is the polite order, not a precondition for leaving it:
     * [forgetConnection] is the way out when the answer never comes.
     */
    suspend fun disconnect(): DisconnectResult {
        val account = store.account.first() ?: return DisconnectResult.Disconnected
        return when (val result = client.revokeDevice(account.serverUrl, account.token, account.deviceId)) {
            is ServerResult.Success,
            ServerResult.Unauthorized,
            -> {
                forget(account)
                DisconnectResult.Disconnected
            }
            is ServerResult.Failed -> DisconnectResult.Failed(result.reason)
        }
    }

    /**
     * Forgets the registration without the server's agreement, for when it cannot give one.
     *
     * A revoke that cannot be delivered used to be the end of the road. The stored registration is
     * what the account screen shows *instead of* the connect form, so a server that stayed down —
     * decommissioned, moved, or reachable only from a network this tablet has left — held this
     * installation to itself with no way to reach another one. Being unable to tell that server
     * anything is a reason to leave it, not a reason to be kept by it.
     *
     * The cost is real and the screen states it before offering this: the device row stays in the
     * server's list and its token stays valid there until somebody removes it from that list or the
     * admin dashboard. Nothing holds that token afterwards — this deletes the only copy — but it is
     * an entry the operator has to prune by hand, which is why this is the second offer and never
     * the first.
     */
    suspend fun forgetConnection() {
        val account = store.account.first() ?: return
        forget(account)
    }

    /** The local half of a disconnect, identical whichever route arrives at it. */
    private suspend fun forget(account: SyncAccount) {
        store.clear()
        hierarchy?.deactivate(account.accountId)
        // Nothing about the old server's syncing is true of the next one.
        _status.value = SyncStatus()
    }

    /**
     * Whether this installation may still create its first-run starter notebook.
     *
     * A device joining an existing account must not. It seeds before its first pull, activation
     * enqueues the seed into the outbox, and the account permanently grows a second "My Notebook"
     * that nobody created — which is exactly what happened here on 2026-08-16, three times over, and
     * is `viveCServer/memory/syncPlan.md` §10 item 4.
     *
     * True with no registration at all, because an offline install must not open on an empty void.
     * True again once this device is level with the server: an empty tree then really is an empty
     * account rather than one that has not been pulled yet, and seeding it is the same first-run
     * courtesy the first device got. The "account is empty" half of that rule needs no code here —
     * [com.vivenotes.data.NotesRepository.seedIfEmpty] already refuses to run over an existing tree,
     * and after a pull the local tree *is* the account.
     */
    suspend fun maySeedStarter(): Boolean {
        val account = store.account.first() ?: return true
        return hierarchy?.hasCaughtUp(account.accountId) ?: true
    }

    /**
     * Runs the hierarchy protocol once, or returns null when no account is configured.
     *
     * Every path through here reports to [status], including the ones nobody watched start.
     */
    suspend fun synchronize(): SyncRunResult? {
        val account = store.account.first() ?: return null

        _status.update { it.copy(running = true) }
        val result = try {
            hierarchy?.run(account) ?: SyncRunResult.Failed(PermanentSyncFailure.LocalData)
        } finally {
            // In a `finally` because the foreground clock cancels its run when the app goes to the
            // background, and a status left saying "running" would outlive the run that set it.
            _status.update { it.copy(running = false) }
        }

        _status.update { current ->
            when (result) {
                is SyncRunResult.Succeeded -> current.copy(
                    lastSucceededAtMillis = System.currentTimeMillis(),
                    lastSummary = result.summary,
                    failure = null,
                )
                // The last success stands: "this failed" and "it last worked ten minutes ago" are
                // both worth knowing, and the second is what says how stale the tablet is.
                else -> current.copy(failure = result)
            }
        }

        if (result == SyncRunResult.Revoked) {
            store.clear()
            hierarchy?.deactivate(account.accountId)
        }
        return result
    }

    /**
     * Moves a closed notebook's contents to the server and removes them from this device.
     *
     * The sync run in front is not a convenience: `HierarchySync.evictToCloud` refuses to delete
     * anything while the outbox holds work, and an empty outbox is the server's own statement that
     * it has every byte this device could offer. The run behind is what tells the other devices,
     * since the shelf columns travel on the notebook row like any other edit.
     *
     * Both are ordinary runs and take the sync mutex, so they are deliberately *outside* the
     * eviction rather than inside it — calling `synchronize` from within would deadlock on a lock
     * this class does not own.
     */
    suspend fun moveNotebookToCloud(notebookId: String): CloudArchiveResult {
        val hierarchy = this.hierarchy ?: return CloudArchiveResult.NoAccount
        store.account.first() ?: return CloudArchiveResult.NoAccount
        when (val run = synchronize()) {
            is SyncRunResult.Succeeded -> Unit
            null -> return CloudArchiveResult.NoAccount
            else -> return CloudArchiveResult.Failed(run)
        }
        val moved = hierarchy.evictToCloud(notebookId)
        if (moved == CloudArchiveResult.Moved) synchronize()
        return moved
    }

    /** Downloads a cloud-only notebook again and puts it back on the rail. */
    suspend fun bringNotebookBack(notebookId: String): CloudArchiveResult {
        val hierarchy = this.hierarchy ?: return CloudArchiveResult.NoAccount
        val account = store.account.first() ?: return CloudArchiveResult.NoAccount
        val restored = hierarchy.restoreFromCloud(account, notebookId)
        // So the cleared shelf columns reach the account: another device holding this notebook in
        // the cloud has to stop evicting it before it can show it again.
        if (restored == CloudArchiveResult.BroughtBack) synchronize()
        return restored
    }
}

/**
 * A Google sign-in paused on `account_link_required`, waiting for the account's password.
 *
 * Every field is required to finish it and none of them may be re-derived: the challenge must be the
 * same unconsumed one, and the ID token must be the same token, or the link proves a different
 * Google session from the one that was refused.
 */
private data class PendingGoogleLink(
    val serverUrl: String,
    val challengeId: String,
    val idToken: String,
    val device: GoogleDeviceDetails,
)

/**
 * What the server's device list will call this tablet.
 *
 * [Build.MODEL] rather than a name the user types: the field exists so somebody scanning their
 * device list can tell which row to revoke, and a marketing name does that on the first try. The
 * empty fallbacks are for emulator images and stripped ROMs that leave these properties blank, where
 * a blank `name` would be refused by the contract.
 *
 * **The model alone does not identify an installation**, and not only in theory. Android Studio's
 * "Medium Tablet" and "Pixel Tablet" profiles are the same `emu64xa` hardware and both report
 * `Build.MODEL == "Pixel Tablet"`, so two emulators register two rows under one name and the device
 * list cannot say which is which — which is the moment somebody revokes the wrong one. Two real
 * tablets of the same model do the same thing.
 *
 * So the name carries a four-character suffix that differs per installation: the leading two bytes
 * of the SHA-256 of `Settings.Secure.ANDROID_ID`, which Android scopes to this app's signing key on
 * this device and user, and which therefore survives launches, updates and reinstalls while
 * differing on a second device. It is hashed and truncated because the server needs a label and not
 * an identifier — four characters of a digest name a row and cannot be correlated back to a tablet.
 */
private fun defaultDeviceName(context: Context): String {
    val model = Build.MODEL?.takeIf { it.isNotBlank() }
        ?: Build.DEVICE?.takeIf { it.isNotBlank() }
        ?: "Android device"
    val suffix = installationSuffix(context) ?: return model
    return "$model ($suffix)"
}

/**
 * Null when the platform has no id to offer. Rare, but the fallback matters: a name with no suffix
 * is better than one with a fabricated suffix, because a suffix that changes between launches would
 * turn one tablet into a growing list of devices nobody can prune.
 */
@SuppressLint("HardwareIds")
private fun installationSuffix(context: Context): String? {
    val androidId = Settings.Secure
        .getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return MessageDigest.getInstance("SHA-256")
        .digest(androidId.toByteArray())
        .take(2)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}

/** Display-only on the server, so the release string is more useful than the API level. */
private fun defaultPlatform(): String = "Android ${Build.VERSION.RELEASE.orEmpty()}".trim()
