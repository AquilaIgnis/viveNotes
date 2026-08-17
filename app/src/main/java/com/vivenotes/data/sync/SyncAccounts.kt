package com.vivenotes.data.sync

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
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

/**
 * Where this installation stands with a self-hosted server.
 *
 * [Connecting] is here rather than being the screen's own boolean because the request outlives the
 * screen on purpose — see [SyncAccounts.connect] — so "a connection is in flight" is a fact about
 * the app, not about whether a composable is on screen.
 */
sealed interface SelfHostConnection {

    /** Never connected, or the last attempt's result has been dismissed. */
    data object Idle : SelfHostConnection

    data object Connecting : SelfHostConnection

    data class Connected(val serverUrl: String, val deviceName: String) : SelfHostConnection

    data class Failed(val reason: ConnectFailure) : SelfHostConnection
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
)

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
    private val deviceName: String = defaultDeviceName(context),
    private val platform: String = defaultPlatform(),
) {

    private val appContext = context.applicationContext
    private val store = SyncAccountStore(context)
    private val hierarchy = database?.let { HierarchySync(it, client) }

    /** Null until connected. Survives launches; cleared by [disconnect]. */
    val account: Flow<SyncAccount?> = store.account

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
    ): SelfHostConnection {
        val serverUrl = normaliseServerAddress(typedAddress)
            ?: return SelfHostConnection.Failed(ConnectFailure.InvalidAddress)

        return when (
            val registration = client.registerDevice(
                serverBaseUrl = serverUrl,
                email = email,
                password = password,
                deviceName = deviceName,
                platform = platform,
            )
        ) {
            is DeviceRegistration.Registered -> {
                try {
                    store.setAccount(
                        SyncAccount(
                            serverUrl = serverUrl,
                            accountId = registration.accountId,
                            deviceId = registration.deviceId,
                            token = registration.token,
                            deviceName = deviceName,
                        ),
                    )
                } catch (unwritable: IOException) {
                    // The device exists on the server and its token is now unrecoverable. Reporting
                    // success would leave the app claiming a connection it cannot authenticate.
                    return SelfHostConnection.Failed(ConnectFailure.NotStored)
                }
                // Activation is local and idempotent. If storage fails after the token is safely
                // stored, the scheduled worker retries it; the registration itself still succeeded.
                try {
                    hierarchy?.activate(registration.accountId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The token is already durable. The scheduled worker retries local activation.
                }
                HierarchySyncWorker.requestNow(appContext)
                SelfHostConnection.Connected(serverUrl, deviceName)
            }

            is DeviceRegistration.Rejected -> SelfHostConnection.Failed(registration.reason)
        }
    }

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
    suspend fun refresh(): SelfHostConnection? {
        val account = store.account.first() ?: return null

        return when (client.checkToken(account.serverUrl, account.token)) {
            TokenCheck.Valid -> SelfHostConnection.Connected(account.serverUrl, account.deviceName)

            TokenCheck.Revoked -> {
                store.clear()
                hierarchy?.deactivate(account.accountId)
                _status.value = SyncStatus()
                SelfHostConnection.Failed(ConnectFailure.Revoked)
            }

            // Unreachable, or a server-side error. Nothing was learned, so nothing changes: the
            // stored registration still stands and the screen goes on showing it as connected.
            is TokenCheck.Unknown ->
                SelfHostConnection.Connected(account.serverUrl, account.deviceName)
        }
    }

    /**
     * Revokes this installation on the server before forgetting its non-reissuable token.
     *
     * Network and 5xx failures leave the credential standing so the user can try again. A 401 means
     * it was already revoked and is therefore the other successful local-disconnect path.
     */
    suspend fun disconnect(): DisconnectResult {
        val account = store.account.first() ?: return DisconnectResult.Disconnected
        return when (val result = client.revokeDevice(account.serverUrl, account.token, account.deviceId)) {
            is ServerResult.Success,
            ServerResult.Unauthorized,
            -> {
                store.clear()
                hierarchy?.deactivate(account.accountId)
                // Nothing about the old server's syncing is true of the next one.
                _status.value = SyncStatus()
                DisconnectResult.Disconnected
            }
            is ServerResult.Failed -> DisconnectResult.Failed(result.reason)
        }
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
}

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
