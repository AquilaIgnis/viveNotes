package com.vivenotes.data.sync

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.vivenotes.BuildConfig

/** What asking Android for a Google identity produced. */
sealed interface GoogleIdToken {

    /**
     * A signed ID token, straight from Credential Manager and not inspected here.
     *
     * Deliberately not parsed on this side. The token's claims mean nothing until Google's signature
     * over them has been checked, and the only party that does that is the server — reading an email
     * out of it here to show in the UI would be displaying an unverified string as a fact.
     */
    data class Received(val idToken: String) : GoogleIdToken

    /**
     * The person closed the sheet.
     *
     * Its own outcome rather than a failure, because it is not one: the screen goes quietly back to
     * where it was. An error message here would tell somebody who changed their mind that something
     * went wrong.
     */
    data object Dismissed : GoogleIdToken

    data class Rejected(val reason: ConnectFailure) : GoogleIdToken
}

/**
 * Sign in with Google, through Android's Credential Manager.
 *
 * One [GetSignInWithGoogleOption] rather than a [com.google.android.libraries.identity.googleid.GetGoogleIdOption]
 * pass for returning users followed by a fallback for new ones. The account screen offers a single
 * button because `POST /v1/auth/google` is a single endpoint that logs in or registers as needed, so
 * a two-stage credential request would be the client re-introducing a distinction neither the person
 * nor the server makes — and paying for it with a second sheet.
 *
 * **[nonce] is not optional and not decorative.** The server issued it seconds earlier, Google seals
 * it inside the signed token, and the server checks that the token it receives carries the challenge
 * it issued. Without it, a token captured from any other app using the same client id would
 * authenticate here.
 *
 * The Android half of the flow lives in its own class so that [SyncAccounts] can be driven by tests
 * without Credential Manager, which needs a real Activity and a real Google account to answer.
 */
class GoogleIdentityProvider(
    /**
     * The Google **Web** client id — the audience the server accepts. Empty when the build was not
     * configured with one, which [configured] reports rather than letting the request fail with a
     * provider error that reads like the user's Google account is at fault.
     */
    private val serverClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID,
    private val getCredential: suspend (Context, GetCredentialRequest) -> GetCredentialResponse =
        { context, request -> CredentialManager.create(context).getCredential(context, request) },
) {

    /** False when this build has no Web client id, which is a state the screen shows rather than hides. */
    val configured: Boolean get() = serverClientId.isNotBlank()

    /**
     * Opens the Google sheet and returns the ID token it produced.
     *
     * [activityContext] must be an Activity: Credential Manager shows UI, and an application context
     * has no window to show it in.
     */
    suspend fun requestIdToken(activityContext: Context, nonce: String): GoogleIdToken {
        if (!configured) return GoogleIdToken.Rejected(ConnectFailure.GoogleNotConfigured)

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetSignInWithGoogleOption.Builder(serverClientId)
                    .setNonce(nonce)
                    .build(),
            )
            .build()

        val response = try {
            getCredential(activityContext, request)
        } catch (dismissed: GetCredentialCancellationException) {
            return GoogleIdToken.Dismissed
        } catch (none: NoCredentialException) {
            // No Google account on the device, or none the user was willing to use. Distinct from
            // a dismissal because it names something to go and do.
            return GoogleIdToken.Rejected(ConnectFailure.NoGoogleAccount)
        } catch (missing: GetCredentialProviderConfigurationException) {
            // No provider answered: a device without Play services, or the dependency missing.
            return GoogleIdToken.Rejected(ConnectFailure.GoogleNotConfigured)
        } catch (unsupported: GetCredentialUnsupportedException) {
            return GoogleIdToken.Rejected(ConnectFailure.GoogleNotConfigured)
        } catch (failed: GetCredentialException) {
            // Interrupted, unknown, or a provider-specific failure. Nothing was sent anywhere, so
            // this is the same instruction as the others: it did not work, try again.
            return GoogleIdToken.Rejected(ConnectFailure.GoogleNotConfigured)
        }

        val credential = response.credential
        // Two type constants, because `GetSignInWithGoogleOption` returns the SIWG subtype while
        // `GetGoogleIdOption` returns the plain one. Matching on both keeps this correct if the
        // request above ever changes.
        val isGoogleIdToken = credential is CustomCredential &&
            (
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ||
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL
                )
        if (!isGoogleIdToken) return GoogleIdToken.Rejected(ConnectFailure.GoogleNotConfigured)

        return try {
            GoogleIdToken.Received(GoogleIdTokenCredential.createFrom(credential.data).idToken)
        } catch (malformed: GoogleIdTokenParsingException) {
            GoogleIdToken.Rejected(ConnectFailure.GoogleNotConfigured)
        }
    }
}
