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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vivenotes.R
import com.vivenotes.data.sync.ConnectFailure
import com.vivenotes.data.sync.MIN_ACCOUNT_PASSWORD
import com.vivenotes.data.sync.PermanentSyncFailure
import com.vivenotes.data.sync.ServerConnection
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
    const val GOOGLE = "account-google"

    /** On the spinner inside the Google button, so a test can tell waiting from idle. */
    const val GOOGLE_PROGRESS = "account-google-progress"
    const val GOOGLE_STATUS = "account-google-status"
    const val SELF_HOST = "account-self-host"

    /** The password-account link dialog, raised only by `409 account_link_required`. */
    const val LINK_DIALOG = "account-link-dialog"
    const val LINK_EMAIL = "account-link-email"
    const val LINK_PASSWORD = "account-link-password"
    const val LINK_CONFIRM = "account-link-confirm"
    const val LINK_CANCEL = "account-link-cancel"
    const val SERVER_URL = "account-server-url"
    const val EMAIL = "account-email"
    const val PASSWORD = "account-password"
    const val CONNECT = "account-connect"

    /**
     * The managed account's own fields, distinct from the self-host form's.
     *
     * Two panels that are never open together could share tags, but only until one of them is
     * mid-exit-animation while the other opens — at which point a test would find two nodes and
     * fail somewhere unrelated to what it was checking.
     */
    const val CLOUD_EMAIL = "account-cloud-email"
    const val CLOUD_PASSWORD = "account-cloud-password"
    const val CLOUD_CONFIRM = "account-cloud-confirm"
    const val CLOUD_SUBMIT = "account-cloud-submit"
    const val CLOUD_PROGRESS = "account-cloud-progress"
    const val CLOUD_STATUS = "account-cloud-status"

    /** On the spinner *inside* the button, so a test can tell waiting from idle. */
    const val CONNECT_PROGRESS = "account-connect-progress"
    const val CONNECT_STATUS = "account-connect-status"
    const val CONNECTED = "account-connected"
    const val SYNC = "account-sync"
    const val SYNC_PROGRESS = "account-sync-progress"
    const val SYNC_STATUS = "account-sync-status"

    /** The Cloud Off glyph beside that line, present only while the server cannot be reached. */
    const val SYNC_OFFLINE = "account-sync-offline"
    const val DISCONNECT = "account-disconnect"
    const val DISCONNECT_CONFIRM = "account-disconnect-confirm"

    /** The local-only way out, shown only once a revoke has failed. */
    const val DISCONNECT_ANYWAY = "account-disconnect-anyway"
}

/**
 * Account entry point for managed and self-hosted sync.
 *
 * Three routes to one place, in descending order of how many people want them.
 *
 * **Sign in with Google sits on top and is the default**: one button, because `POST /v1/auth/google`
 * is one endpoint that logs in or registers as the account turns out to need, so the screen never
 * asks somebody to declare which they are. Below it, **Log in and Sign up** are the same managed
 * account reached with an email and a password — and neither asks for a server address, because
 * there is one managed deployment and [com.vivenotes.BuildConfig.CLOUD_BASE_URL] already names it.
 *
 * **Self host is the third and is a different server**, run by whoever is using it, so it is the one
 * route that has an address to type. Keeping that field out of Log in and Sign up is the whole point
 * of the separation: a managed account is not something a person should have to know a hostname for.
 *
 * All three end in the same place — a base URL and a device token — so [connection] describes all of
 * them and the connected panel is written once (`viveCServer/docs/openapi.yaml`).
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
    /** False when the build carries no Google Web client id: the button is shown disabled, not hidden. */
    googleAvailable: Boolean = true,
    signingInWithGoogle: Boolean = false,
    onSignInWithGoogle: () -> Unit = {},
    /**
     * The last Google attempt's failure, or null. Separate from [connection] because the two routes
     * fail independently and a message under the wrong button is a message about the wrong thing.
     */
    googleFailure: ConnectFailure? = null,
    /** Raised by `409 account_link_required`, which has exactly one resolution — see [onLinkAccount]. */
    linkRequired: Boolean = false,
    linking: Boolean = false,
    onLinkAccount: (email: String, password: String) -> Unit = { _, _ -> },
    onCancelLink: () -> Unit = {},
    /** True for the session in which Google sign-in created the account, rather than found it. */
    accountCreated: Boolean = false,
    connection: ServerConnection = ServerConnection.Idle,
    /**
     * Sign in to the **managed** account with an email and password — `POST /v1/devices` against the
     * deployment this build was compiled for. No server address, because there is nothing to choose.
     */
    onLogIn: (email: String, password: String) -> Unit = { _, _ -> },
    /**
     * Create the managed account, then connect — `POST /v1/accounts` followed by `POST /v1/devices`.
     */
    onSignUp: (email: String, password: String) -> Unit = { _, _ -> },
    /** The self-hosted route, which is a different server and keeps its address field. */
    onConnect: (serverUrl: String, email: String, password: String) -> Unit = { _, _, _ -> },
    /** Whether *this screen's* Sync now is in flight — the button's own spinner, not the clock's. */
    syncing: Boolean = false,
    syncStatus: SyncStatus = SyncStatus(),
    onSync: () -> Unit = {},
    disconnecting: Boolean = false,
    disconnectFailure: ConnectFailure? = null,
    onDisconnect: () -> Unit = {},
    /**
     * Forget the server without revoking this device on it, for a server that cannot be told.
     * Offered only after [onDisconnect] has failed.
     */
    onForceDisconnect: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    var selfHostExpanded by rememberSaveable { mutableStateOf(false) }
    var serverUrl by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    // Passwords should not be written into saved-instance state or retained after leaving the flow.
    var password by remember { mutableStateOf("") }
    var confirmDisconnect by remember { mutableStateOf(false) }

    // The managed account's panel: whether it is open, and which of the two buttons opened it.
    // Saved, because a configuration change in the middle of filling it must not silently turn
    // Sign up back into Log in.
    var cloudExpanded by rememberSaveable { mutableStateOf(false) }
    var cloudSignUp by rememberSaveable { mutableStateOf(false) }
    var cloudEmail by rememberSaveable { mutableStateOf("") }
    // Composition-only, like the self-host form's password and for the same reason.
    var cloudPassword by remember { mutableStateOf("") }
    var cloudConfirmPassword by remember { mutableStateOf("") }
    val spatialMotion = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    val effectsMotion = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    // The spinner replaces the button's label, so without this the button loses its accessible name
    // for as long as the request runs. Read here because a semantics lambda is not composable.
    val connectingLabel = stringResource(R.string.account_connecting)
    val creatingLabel = stringResource(R.string.account_creating)

    // Pressing the button that is already showing folds the panel away again; pressing the other
    // one switches modes without closing. Self host closes, because the two are alternatives.
    fun openCloudPanel(signUp: Boolean) {
        if (cloudExpanded && cloudSignUp == signUp) {
            cloudExpanded = false
        } else {
            cloudExpanded = true
            cloudSignUp = signUp
            selfHostExpanded = false
            // The confirmation belongs to signing up. Left behind from a previous pass it could
            // silently satisfy a check the person did not make this time.
            cloudConfirmPassword = ""
        }
    }

    val connected = connection as? ServerConnection.Connected

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
            cloudPassword = ""
            cloudConfirmPassword = ""
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
                                accountCreated = accountCreated,
                                syncStatus = syncStatus,
                                syncing = syncing,
                                onSync = onSync,
                                disconnecting = disconnecting,
                                disconnectFailure = disconnectFailure,
                                onDisconnect = { confirmDisconnect = true },
                                onForceDisconnect = onForceDisconnect,
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
                    GoogleSignInButton(
                        enabled = googleAvailable && !signingInWithGoogle,
                        signingIn = signingInWithGoogle,
                        onClick = onSignInWithGoogle,
                    )

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(
                            if (googleAvailable) {
                                R.string.account_google_explainer
                            } else {
                                // A build with no Web client id says so here rather than letting the
                                // button fail with a provider error that reads like the person's
                                // Google account is at fault.
                                R.string.account_google_unconfigured
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    // Suppressed while the link dialog is up, because that dialog shows the same
                    // failure with wording of its own. Two copies of one message is one of them
                    // being read out twice by a screen reader.
                    if (googleFailure != null && !linkRequired) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(failureMessage(googleFailure)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag(AccountTags.GOOGLE_STATUS),
                        )
                    }

                    // The email-and-password way into the **managed** account, unchanged in shape
                    // from before Google was added. Neither of these asks for a server address:
                    // there is one managed deployment and the build already knows it. Typing a host
                    // belongs to Self host below, which is a different server and a different
                    // project.
                    Spacer(Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = { openCloudPanel(signUp = false) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag(AccountTags.LOGIN),
                        ) {
                            Text(stringResource(R.string.account_log_in))
                        }
                        OutlinedButton(
                            onClick = { openCloudPanel(signUp = true) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag(AccountTags.SIGN_UP),
                        ) {
                            Text(stringResource(R.string.account_sign_up))
                        }
                    }

                    AnimatedVisibility(
                        visible = cloudExpanded,
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
                            OutlinedTextField(
                                value = cloudEmail,
                                onValueChange = { cloudEmail = it },
                                label = { Text(stringResource(R.string.account_email)) },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Email,
                                    imeAction = ImeAction.Next,
                                ),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag(AccountTags.CLOUD_EMAIL),
                            )
                            OutlinedTextField(
                                value = cloudPassword,
                                onValueChange = { cloudPassword = it },
                                label = { Text(stringResource(R.string.account_password)) },
                                // Only while signing up: the minimum is a rule about a *new*
                                // password, and showing it beside an existing one would read as a
                                // claim about the password the person already has.
                                supportingText = if (cloudSignUp) {
                                    { Text(stringResource(R.string.account_password_minimum)) }
                                } else {
                                    null
                                },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done,
                                ),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag(AccountTags.CLOUD_PASSWORD),
                            )

                            // Signing up only. A mistyped password here cannot be recovered — the
                            // server stores an Argon2id hash and there is no reset flow in this
                            // contract — so the second field is the one chance to catch the typo
                            // before it becomes an account nobody can get into. Logging in needs no
                            // such thing: a wrong password there is simply refused.
                            val mismatch = cloudSignUp &&
                                cloudConfirmPassword.isNotEmpty() &&
                                cloudConfirmPassword != cloudPassword
                            if (cloudSignUp) {
                                OutlinedTextField(
                                    value = cloudConfirmPassword,
                                    onValueChange = { cloudConfirmPassword = it },
                                    label = {
                                        Text(stringResource(R.string.account_confirm_password))
                                    },
                                    isError = mismatch,
                                    // Only once something has been typed: an empty second field is
                                    // unfinished, not wrong, and colouring it red the moment the
                                    // first one is filled scolds somebody who is still going.
                                    supportingText = if (mismatch) {
                                        { Text(stringResource(R.string.account_password_mismatch)) }
                                    } else {
                                        null
                                    },
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                        imeAction = ImeAction.Done,
                                    ),
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag(AccountTags.CLOUD_CONFIRM),
                                )
                            }

                            val busy = connection is ServerConnection.Connecting
                            Button(
                                onClick = {
                                    if (cloudSignUp) {
                                        onSignUp(cloudEmail, cloudPassword)
                                    } else {
                                        onLogIn(cloudEmail, cloudPassword)
                                    }
                                },
                                // Signing up adds the contract's eight-character minimum, refused
                                // here rather than by the server: a shorter password has exactly one
                                // possible answer, and a round trip to hear it is a wasted one.
                                enabled = !busy &&
                                    cloudEmail.isNotBlank() &&
                                    if (cloudSignUp) {
                                        cloudPassword.length >= MIN_ACCOUNT_PASSWORD &&
                                            cloudConfirmPassword == cloudPassword
                                    } else {
                                        cloudPassword.isNotEmpty()
                                    },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag(AccountTags.CLOUD_SUBMIT),
                            ) {
                                if (busy) {
                                    LoadingIndicator(
                                        Modifier
                                            .size(18.dp)
                                            .testTag(AccountTags.CLOUD_PROGRESS)
                                            .semantics {
                                                contentDescription = if (cloudSignUp) {
                                                    creatingLabel
                                                } else {
                                                    connectingLabel
                                                }
                                            },
                                    )
                                } else {
                                    Text(
                                        stringResource(
                                            if (cloudSignUp) {
                                                R.string.account_create
                                            } else {
                                                R.string.account_log_in
                                            },
                                        ),
                                    )
                                }
                            }

                            ConnectionStatus(connection, AccountTags.CLOUD_STATUS)
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    TextButton(
                        onClick = {
                            selfHostExpanded = !selfHostExpanded
                            // The two panels are alternatives, so opening one closes the other. Both
                            // open at once would show two email fields and two submit buttons.
                            if (selfHostExpanded) cloudExpanded = false
                        },
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

                            val connecting = connection is ServerConnection.Connecting
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

                            ConnectionStatus(connection, AccountTags.CONNECT_STATUS)
                        }
                    }
                }
            }
        }
    }

    // `409 account_link_required` has exactly one resolution in the contract, and this is it: the
    // Google email already belongs to a password account, and creating a second account would split
    // one person's notes across two. So the dialog is not a choice — it is the next request.
    //
    // The email is typed rather than filled in from the ID token. Those claims are unverified until
    // the server has checked Google's signature, and it re-derives the address from the verified
    // token to compare against this one anyway; asking costs one field and keeps an unverified
    // string from ever being shown back as a fact.
    if (linkRequired) {
        var linkEmail by remember { mutableStateOf("") }
        // Composition-only, like the connect form's password, and for the same reason.
        var linkPassword by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = onCancelLink,
            title = { Text(stringResource(R.string.account_link_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.account_link_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = linkEmail,
                        onValueChange = { linkEmail = it },
                        label = { Text(stringResource(R.string.account_link_email)) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                        singleLine = true,
                        enabled = !linking,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(AccountTags.LINK_EMAIL),
                    )
                    OutlinedTextField(
                        value = linkPassword,
                        onValueChange = { linkPassword = it },
                        label = { Text(stringResource(R.string.account_link_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        singleLine = true,
                        enabled = !linking,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(AccountTags.LINK_PASSWORD),
                    )
                    if (googleFailure != null) {
                        // The failure of the *link* attempt, shown in the dialog that caused it
                        // rather than behind it — the dialog stays open so the password can be
                        // corrected without starting a second sign-in.
                        Text(
                            text = stringResource(
                                // A 401 here has two causes, not the one the general message names:
                                // the password, or an email that is not the Google address just
                                // used. The server cannot say which, so the sentence covers both.
                                if (googleFailure == ConnectFailure.InvalidCredentials) {
                                    R.string.account_error_link_credentials
                                } else {
                                    failureMessage(googleFailure)
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                val linkingLabel = stringResource(R.string.account_linking)
                TextButton(
                    onClick = { onLinkAccount(linkEmail, linkPassword) },
                    enabled = !linking && linkEmail.isNotBlank() && linkPassword.isNotEmpty(),
                    modifier = Modifier.testTag(AccountTags.LINK_CONFIRM),
                ) {
                    if (linking) {
                        // Same treatment as Connect and the Google button: the spinner replaces the
                        // label, so it carries the name the label was providing.
                        LoadingIndicator(
                            Modifier
                                .size(18.dp)
                                .semantics { contentDescription = linkingLabel },
                        )
                    } else {
                        Text(stringResource(R.string.account_link_confirm))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onCancelLink,
                    enabled = !linking,
                    modifier = Modifier.testTag(AccountTags.LINK_CANCEL),
                ) {
                    Text(stringResource(R.string.account_link_cancel))
                }
            },
            modifier = Modifier.testTag(AccountTags.LINK_DIALOG),
        )
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
 * Sign in with Google, drawn as the screen's primary action.
 *
 * A **neutral** container rather than the scheme's `primary`, and that is Google's requirement
 * rather than a taste call: the four-colour G is a brand mark that may not be recoloured and is
 * specified against a light or dark neutral, not against an app's accent. `surfaceContainerHighest`
 * with `onSurface` is the M3 token pair that lands closest in both themes, so the button stays part
 * of this app's surface language while the mark keeps its own colours.
 *
 * It is the most prominent control on the card because it is the only one: the second route is a
 * text link below. Full width for the same reason the connect button is — this card is capped at
 * 560dp, and a centred half-width button inside it reads as one of two things when there is one.
 *
 * The spinner replaces the whole label, so the button carries an explicit content description for
 * as long as it is up; without it the control loses its accessible name mid-request.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GoogleSignInButton(
    enabled: Boolean,
    signingIn: Boolean,
    onClick: () -> Unit,
) {
    val signingInLabel = stringResource(R.string.account_google_signing_in)

    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AccountTags.GOOGLE),
    ) {
        if (signingIn) {
            LoadingIndicator(
                Modifier
                    .size(18.dp)
                    .testTag(AccountTags.GOOGLE_PROGRESS)
                    .semantics { contentDescription = signingInLabel },
            )
        } else {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_google_g),
                contentDescription = null,
                // Untinted: the mark carries its own four colours and tinting it would flatten it
                // to one. This is the same reason `AboutDialog` draws `ic_github` unrecoloured.
                tint = Color.Unspecified,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.account_google_sign_in))
        }
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
    connected: ServerConnection.Connected,
    /** Only the sign-in that created the account says so, and only for that session. */
    accountCreated: Boolean,
    syncStatus: SyncStatus,
    syncing: Boolean,
    onSync: () -> Unit,
    disconnecting: Boolean,
    disconnectFailure: ConnectFailure?,
    onDisconnect: () -> Unit,
    onForceDisconnect: () -> Unit,
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
                    // "Account created" is the one thing the single Google button cannot show any
                    // other way: the server is the only party that knows whether it found the
                    // account or made it, and it says so once, in the response.
                    text = stringResource(
                        if (accountCreated) {
                            R.string.account_connected_created
                        } else {
                            R.string.account_connected_title
                        },
                    ),
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

    // A failed revoke is not a locked door. The stored registration is what this screen shows in
    // place of the connect form, so without a local-only way out a server that never answers again
    // would keep this installation for itself — see `SyncAccounts.forgetConnection`. It appears only
    // here, after the polite route has actually been tried and has actually failed, and it says what
    // it leaves behind rather than asking a second time whether the person is sure.
    if (disconnectFailure != null) {
        Text(
            text = stringResource(R.string.account_disconnect_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = stringResource(R.string.account_disconnect_anyway_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onForceDisconnect,
            enabled = !disconnecting,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(AccountTags.DISCONNECT_ANYWAY),
        ) {
            Text(
                text = stringResource(R.string.account_disconnect_anyway),
                color = MaterialTheme.colorScheme.error,
            )
        }
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The same glyph the ribbon badges the account button with, so the badge somebody saw on
        // the way here is recognisable beside the sentence that explains it. Only for the state it
        // actually means: every other failure has a different cause and a different fix.
        if (status.serverUnreachable) {
            Icon(
                imageVector = MaterialSymbols.CloudOff,
                // Decoration beside text that already says it.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .size(16.dp)
                    .testTag(AccountTags.SYNC_OFFLINE),
            )
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else LocalIconAccents.current.green,
            modifier = Modifier.testTag(AccountTags.SYNC_STATUS),
        )
    }
}

/**
 * "Synced just now", and what that run moved when it moved anything.
 *
 * The counts are appended only when they are not both zero. Every interval reporting "Pulled 0 ·
 * Pushed 0" is a line that trains its reader to stop looking at it, and "nothing to do" is already
 * what a bare timestamp means.
 *
 * Pictures are counted separately and shown only when a run carried any, because they are the one
 * thing that makes a sync take noticeably long — a page of photographs is megabytes where a page of
 * writing is kilobytes — and a reader watching a slow first sync deserves to know that is what it
 * is doing. A row and its picture are two different units, so they are two different numbers.
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
    if (summary == null) return whenText
    val pictures = if (summary.pictures == 0) {
        ""
    } else {
        stringResource(R.string.account_sync_pictures, summary.pictures)
    }
    if (summary.pulled == 0 && summary.pushed == 0) return whenText + pictures
    return whenText +
        stringResource(R.string.account_sync_counts, summary.pulled, summary.pushed) +
        pictures
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
    // Not "an error" so much as "you are behind": the cursor is deliberately parked so the changes
    // this build cannot store stay on the server rather than being skipped past.
    PermanentSyncFailure.UnsupportedKind -> R.string.account_sync_error_unsupported
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
private fun ConnectionStatus(connection: ServerConnection, testTag: String) {
    when (connection) {
        is ServerConnection.Failed -> Text(
            text = stringResource(failureMessage(connection.reason)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(testTag),
        )

        is ServerConnection.Connected,
        ServerConnection.Idle,
        ServerConnection.Connecting,
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
    ConnectFailure.InvalidChallenge -> R.string.account_error_challenge
    ConnectFailure.InvalidGoogleToken -> R.string.account_error_google_token
    ConnectFailure.GoogleUnavailable -> R.string.account_error_google_unavailable
    ConnectFailure.IdentityConflict -> R.string.account_error_identity_conflict
    ConnectFailure.IdempotencyConflict -> R.string.account_error_idempotency
    ConnectFailure.AccountUnavailable -> R.string.account_error_account_unavailable
    ConnectFailure.NoGoogleAccount -> R.string.account_error_no_google_account
    ConnectFailure.GoogleNotConfigured -> R.string.account_error_google_unconfigured
    ConnectFailure.SignupClosed -> R.string.account_error_signup_closed
    ConnectFailure.EmailTaken -> R.string.account_error_email_taken
    ConnectFailure.AccountCreatedNotRegistered -> R.string.account_error_created_not_registered
}
