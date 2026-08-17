package com.vivenotes.ui.account

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vivenotes.R
import com.vivenotes.data.sync.ConnectFailure
import com.vivenotes.data.sync.PermanentSyncFailure
import com.vivenotes.data.sync.SelfHostConnection
import com.vivenotes.data.sync.SyncRunResult
import com.vivenotes.data.sync.SyncStatus
import com.vivenotes.data.sync.SyncSummary
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.ui.theme.LocalIconAccents

internal object AccountTags {
    const val SCREEN = "account-screen"
    const val BACK = "account-back"
    const val LOGIN = "account-login"
    const val SIGN_UP = "account-sign-up"
    const val SELF_HOST = "account-self-host"
    const val SERVER_URL = "account-server-url"
    const val EMAIL = "account-email"
    const val PASSWORD = "account-password"
    const val CONNECT = "account-connect"

    /** On the spinner *inside* the button, so a test can tell waiting from idle. */
    const val CONNECT_PROGRESS = "account-connect-progress"
    const val CONNECT_STATUS = "account-connect-status"
    const val CONNECTED = "account-connected"
    const val SYNC = "account-sync"
    const val SYNC_PROGRESS = "account-sync-progress"
    const val SYNC_STATUS = "account-sync-status"
    const val DISCONNECT = "account-disconnect"
    const val DISCONNECT_CONFIRM = "account-disconnect-confirm"
}

/**
 * Account entry point for hosted and self-hosted sync.
 *
 * Hosted `Log in` and `Sign up` deliberately remain callbacks: there is no hosted service, so this
 * screen does not invent an API contract for one. **Self host is real** — it registers this device
 * against `POST /v1/devices` (`viveCServer/docs/openapi.yaml`).
 *
 * Presentational, including the connect flow: [connection] comes in and [onConnect] goes out, so the
 * request lives in a scope that outlives this screen. That matters more here than it looks. The
 * device token comes back exactly once and cannot be reissued, so a request tied to this
 * composition would turn "closing the screen while it spins" into a device row on the server that
 * nothing holds the credential for. It also makes the wait testable by driving [connection], which
 * is the pattern the rest of the suite uses.
 *
 * The opt-in is for the connect button's [LoadingIndicator] — see `EquationButton` for why the
 * loading indicators are the one part of M3 Expressive still gated in 1.5.0-alpha25.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    onLogIn: () -> Unit = {},
    onSignUp: () -> Unit = {},
    connection: SelfHostConnection = SelfHostConnection.Idle,
    onConnect: (serverUrl: String, email: String, password: String) -> Unit = { _, _, _ -> },
    /** Whether *this screen's* Sync now is in flight — the button's own spinner, not the clock's. */
    syncing: Boolean = false,
    syncStatus: SyncStatus = SyncStatus(),
    onSync: () -> Unit = {},
    disconnecting: Boolean = false,
    disconnectFailure: ConnectFailure? = null,
    onDisconnect: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    var selfHostExpanded by rememberSaveable { mutableStateOf(false) }
    var serverUrl by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    // Passwords should not be written into saved-instance state or retained after leaving the flow.
    var password by remember { mutableStateOf("") }
    var confirmDisconnect by remember { mutableStateOf(false) }
    val spatialMotion = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    val effectsMotion = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    // The spinner replaces the button's label, so without this the button loses its accessible name
    // for as long as the request runs. Read here because a semantics lambda is not composable.
    val connectingLabel = stringResource(R.string.account_connecting)

    val connected = connection as? SelfHostConnection.Connected

    // Once the token exists the password has no further use, so it stops being held. Deliberately
    // not done on failure: a wrong password is usually a typo in one character, and clearing the
    // field would make correcting it mean typing the whole thing again.
    //
    // Opening the disclosure is in the same effect because it has the same trigger: arriving on this
    // screen already connected should show that, not a collapsed row that hides it. Keyed on
    // whether there is a connection rather than on [connection] itself, so re-expanding does not
    // fight a user who has just folded it away.
    LaunchedEffect(connected != null) {
        if (connected != null) {
            password = ""
            selfHostExpanded = true
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag(AccountTags.SCREEN),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.account)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag(AccountTags.BACK),
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(contentPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.AccountCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(40.dp),
                        )
                    }

                    // Connected, this screen has one job: say how sync is going and offer the two
                    // things that act on it. The hosted buttons and the disclosure that hides the
                    // form are an invitation to connect, and there is nothing left to invite.
                    if (connected != null) {
                        Spacer(Modifier.height(20.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ConnectedPanel(
                                connected = connected,
                                syncStatus = syncStatus,
                                syncing = syncing,
                                onSync = onSync,
                                disconnecting = disconnecting,
                                disconnectFailure = disconnectFailure,
                                onDisconnect = { confirmDisconnect = true },
                            )
                        }
                        return@Column
                    }

                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = stringResource(R.string.account_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.account_supporting_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(28.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = onLogIn,
                            modifier = Modifier
                                .weight(1f)
                                .testTag(AccountTags.LOGIN),
                        ) {
                            Text(stringResource(R.string.account_log_in))
                        }
                        OutlinedButton(
                            onClick = onSignUp,
                            modifier = Modifier
                                .weight(1f)
                                .testTag(AccountTags.SIGN_UP),
                        ) {
                            Text(stringResource(R.string.account_sign_up))
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    TextButton(
                        onClick = { selfHostExpanded = !selfHostExpanded },
                        modifier = Modifier.testTag(AccountTags.SELF_HOST),
                    ) {
                        Text(stringResource(R.string.account_self_host))
                    }

                    AnimatedVisibility(
                        visible = selfHostExpanded,
                        enter = expandVertically(animationSpec = spatialMotion) +
                            fadeIn(animationSpec = effectsMotion),
                        exit = shrinkVertically(animationSpec = spatialMotion) +
                            fadeOut(animationSpec = effectsMotion),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.account_self_host_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = serverUrl,
                                onValueChange = { serverUrl = it },
                                label = { Text(stringResource(R.string.account_server_url)) },
                                placeholder = { Text(stringResource(R.string.account_server_url_example)) },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Next,
                                ),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag(AccountTags.SERVER_URL),
                            )
                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it },
                                label = { Text(stringResource(R.string.account_email)) },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Email,
                                    imeAction = ImeAction.Next,
                                ),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag(AccountTags.EMAIL),
                            )
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text(stringResource(R.string.account_password)) },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done,
                                ),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag(AccountTags.PASSWORD),
                            )

                            val connecting = connection is SelfHostConnection.Connecting
                            Button(
                                onClick = { onConnect(serverUrl, email, password) },
                                // Blank fields are refused here rather than by the server: all three
                                // are required by the contract, so a round trip could only come back
                                // saying so.
                                enabled = !connecting &&
                                    serverUrl.isNotBlank() &&
                                    email.isNotBlank() &&
                                    password.isNotEmpty(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag(AccountTags.CONNECT),
                            ) {
                                if (connecting) {
                                    // Uncontained and sized to the label it replaces — an indicator
                                    // with its own container inside a button is a container in a
                                    // container. Same call `EquationButton` makes.
                                    LoadingIndicator(
                                        Modifier
                                            .size(18.dp)
                                            .testTag(AccountTags.CONNECT_PROGRESS)
                                            .semantics { contentDescription = connectingLabel },
                                    )
                                } else {
                                    Text(stringResource(R.string.account_connect))
                                }
                            }

                            ConnectionStatus(connection)
                        }
                    }
                }
            }
        }
    }

    // Confirmed rather than immediate: the token cannot be reissued, so a stray tap costs a
    // credential and leaves a device on the server that this app can no longer revoke.
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.account_disconnect_title)) },
            text = { Text(stringResource(R.string.account_disconnect_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDisconnect = false
                        onDisconnect()
                    },
                    modifier = Modifier.testTag(AccountTags.DISCONNECT_CONFIRM),
                ) {
                    Text(
                        text = stringResource(R.string.account_disconnect),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) {
                    Text(stringResource(R.string.account_disconnect_cancel))
                }
            },
        )
    }
}

/**
 * What the disclosure shows once this installation holds a device token.
 *
 * Green, from [com.vivenotes.ui.theme.IconAccents] rather than from the colour scheme, because the
 * scheme has no "good" colour — `primary` is azure and means "this is the app", `tertiary` is an
 * accent. The accents are already tuned per theme, which matters: a green that reads on the dark
 * shell is invisible on a white card. The tinted plate behind it is that same green at low alpha, so
 * one hue carries the whole state and the panel needs no border to separate from the card.
 *
 * The check mark is knocked out in [androidx.compose.material3.ColorScheme.surface], which lands
 * light on the light theme's dark green and dark on the dark theme's light green — the inversion is
 * automatic rather than two hand-picked colours that could drift apart.
 */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun ConnectedPanel(
    connected: SelfHostConnection.Connected,
    syncStatus: SyncStatus,
    syncing: Boolean,
    onSync: () -> Unit,
    disconnecting: Boolean,
    disconnectFailure: ConnectFailure?,
    onDisconnect: () -> Unit,
) {
    val accents = LocalIconAccents.current
    val connectedSyncingDescription = stringResource(R.string.account_syncing)
    val disconnectingDescription = stringResource(R.string.account_disconnecting)

    Surface(
        shape = MaterialTheme.shapes.large,
        color = accents.green.copy(alpha = CONNECTED_PLATE_ALPHA),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AccountTags.CONNECTED),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(accents.green, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MaterialSymbols.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.account_connected_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = accents.green,
                    modifier = Modifier.testTag(AccountTags.CONNECT_STATUS),
                )
                Text(
                    text = connected.serverUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    // The device name is what the person will look for in the server's list when
                    // they revoke it there, so it is worth showing rather than implying.
                    text = stringResource(R.string.account_connected_device, connected.deviceName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    Button(
        onClick = onSync,
        enabled = !syncing && !disconnecting,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AccountTags.SYNC),
    ) {
        if (syncing) {
            LoadingIndicator(
                Modifier
                    .size(18.dp)
                    .testTag(AccountTags.SYNC_PROGRESS)
                    .semantics {
                        contentDescription = connectedSyncingDescription
                    },
            )
        } else {
            Text(stringResource(R.string.account_sync_now))
        }
    }

    SyncStatusLine(syncStatus)

    OutlinedButton(
        onClick = onDisconnect,
        enabled = !syncing && !disconnecting,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AccountTags.DISCONNECT),
    ) {
        if (disconnecting) {
            LoadingIndicator(
                Modifier
                    .size(18.dp)
                    .semantics { contentDescription = disconnectingDescription },
            )
        } else {
            Text(stringResource(R.string.account_disconnect))
        }
    }

    if (disconnectFailure != null) {
        Text(
            text = stringResource(R.string.account_disconnect_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * The one line that says whether sync is actually working.
 *
 * It reports the clock's runs, not just the button's, which is the whole point: a tablet syncs every
 * interval whether or not anyone is watching, and the failure worth catching is the one nobody asked
 * for. A failure wins over the timestamp because it is the news; the timestamp survives underneath it
 * in [SyncStatus] and is what a later reading of this line falls back to once the trouble clears.
 *
 * Nothing announces a run in progress. At the debug interval that would be a line flickering between
 * two states every five seconds, and the button beside it already spins when a person asks for one.
 */
@Composable
private fun SyncStatusLine(status: SyncStatus) {
    val failure = status.failure
    val (message, isError) = when {
        failure != null -> syncFailureText(failure) to true
        status.lastSucceededAtMillis != null ->
            syncedAtText(status.lastSucceededAtMillis, status.lastSummary) to false
        else -> stringResource(R.string.account_sync_never) to false
    }
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else LocalIconAccents.current.green,
        modifier = Modifier.testTag(AccountTags.SYNC_STATUS),
    )
}

/**
 * "Synced just now", and what that run moved when it moved anything.
 *
 * The counts are appended only when they are not both zero. Every interval reporting "Pulled 0 ·
 * Pushed 0" is a line that trains its reader to stop looking at it, and "nothing to do" is already
 * what a bare timestamp means.
 *
 * Relative rather than a clock time because the question is "is this tablet current", and *five
 * minutes ago* answers it without the reader doing arithmetic. It goes stale only while sync is
 * failing, and that case shows the failure instead.
 */
@Composable
private fun syncedAtText(atMillis: Long, summary: SyncSummary?): String {
    val now = System.currentTimeMillis()
    val elapsed = now - atMillis
    val whenText = if (elapsed < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.account_sync_just_now)
    } else {
        stringResource(
            R.string.account_sync_relative,
            DateUtils.getRelativeTimeSpanString(atMillis, now, DateUtils.MINUTE_IN_MILLIS),
        )
    }
    if (summary == null || (summary.pulled == 0 && summary.pushed == 0)) return whenText
    return whenText + stringResource(R.string.account_sync_counts, summary.pulled, summary.pushed)
}

@Composable
private fun syncFailureText(failure: SyncRunResult): String = when (failure) {
    // Transport trouble is normal on a tablet and resolves itself, so it says so rather than
    // reading as something the person has to go and fix.
    is SyncRunResult.Retryable -> stringResource(
        R.string.account_sync_will_retry,
        stringResource(failureMessage(failure.reason)),
    )
    is SyncRunResult.Failed -> stringResource(syncFailureMessage(failure.reason))
    SyncRunResult.Revoked -> stringResource(R.string.account_error_revoked)
    // Not reachable: a success is not a failure. Exhaustive so the compiler keeps it that way.
    is SyncRunResult.Succeeded -> stringResource(R.string.account_sync_up_to_date)
}

@StringRes
private fun syncFailureMessage(reason: PermanentSyncFailure): Int = when (reason) {
    PermanentSyncFailure.InvalidServerResponse -> R.string.account_sync_error_response
    PermanentSyncFailure.LocalData -> R.string.account_sync_error_local
    PermanentSyncFailure.ChangeTooLarge -> R.string.account_sync_error_large
    PermanentSyncFailure.MalformedChange -> R.string.account_sync_error_malformed
    PermanentSyncFailure.MissingParent -> R.string.account_sync_error_parent
}

/** Enough tint to read as a state, not enough to compete with the card it sits on. */
private const val CONNECTED_PLATE_ALPHA = 0.14f

/**
 * The one line under the button that says how the last attempt ended.
 *
 * Nothing is shown while idle or in flight: the button is already saying it is working, and a
 * message that appears before there is news is noise. Success is not here either — it replaces the
 * form entirely, in [ConnectedPanel]. Failures are the common case a self-hoster meets first, so
 * they get the error colour and a sentence naming what to change.
 */
@Composable
private fun ConnectionStatus(connection: SelfHostConnection) {
    when (connection) {
        is SelfHostConnection.Failed -> Text(
            text = stringResource(failureMessage(connection.reason)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(AccountTags.CONNECT_STATUS),
        )

        is SelfHostConnection.Connected,
        SelfHostConnection.Idle,
        SelfHostConnection.Connecting,
        -> Unit
    }
}

/**
 * Every [ConnectFailure] gets its own sentence.
 *
 * Exhaustive on purpose — no `else` branch — so a failure added to the enum cannot reach the screen
 * as a blank line. The compiler is the reminder to write the message.
 */
@StringRes
private fun failureMessage(reason: ConnectFailure): Int = when (reason) {
    ConnectFailure.InvalidAddress -> R.string.account_error_address
    ConnectFailure.Unreachable -> R.string.account_error_unreachable
    ConnectFailure.InvalidCredentials -> R.string.account_error_credentials
    ConnectFailure.InvalidRequest -> R.string.account_error_request
    ConnectFailure.PayloadTooLarge -> R.string.account_error_too_large
    ConnectFailure.ServerError -> R.string.account_error_server
    ConnectFailure.NotAViveServer -> R.string.account_error_not_vive
    ConnectFailure.NotStored -> R.string.account_error_not_stored
    ConnectFailure.Revoked -> R.string.account_error_revoked
}
