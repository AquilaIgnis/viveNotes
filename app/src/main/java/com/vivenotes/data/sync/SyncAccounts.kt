package com.vivenotes.data.sync

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.vivenotes.data.db.NotesDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
                DisconnectResult.Disconnected
            }
            is ServerResult.Failed -> DisconnectResult.Failed(result.reason)
        }
    }

    /** Runs the hierarchy protocol once, or returns null when no account is configured. */
    suspend fun synchronize(): SyncRunResult? {
        val account = store.account.first() ?: return null
        val result = hierarchy?.run(account)
            ?: return SyncRunResult.Failed(PermanentSyncFailure.LocalData)
        if (result == SyncRunResult.Revoked) {
            store.clear()
            hierarchy.deactivate(account.accountId)
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
