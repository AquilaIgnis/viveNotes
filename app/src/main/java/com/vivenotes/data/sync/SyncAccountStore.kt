package com.vivenotes.data.sync

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

private val Context.syncPreferences: DataStore<Preferences> by preferencesDataStore("sync")

/**
 * This installation's registration with one self-hosted server.
 *
 * One record, not a list: an installation syncs with a single server, and a second one would mean
 * two copies of the notebook tree with no rule for which owns a page.
 *
 * The password is deliberately absent and must stay absent. It is typed once, exchanged for
 * [token], and forgotten — that split is the whole reason `POST /v1/devices` exists as a separate
 * endpoint (`viveCServer/docs/openapi.yaml`), and keeping the password here would throw it away.
 */
@Serializable
data class SyncAccount(
    /** Already normalised by [normaliseServerAddress]; every later request is built from it. */
    val serverUrl: String,
    val accountId: String,
    val deviceId: String,
    /**
     * The bearer credential, held because the server cannot reproduce it — only its SHA-256 is
     * stored. Losing this row requires authenticating for a replacement and can leave an unusable
     * credential behind, which is why it is written in the same `edit` as everything else here.
     */
    val token: String,
    /** What the server will show for this device, kept so the UI can name it without a round trip. */
    val deviceName: String,
)

/**
 * Persists the device token across launches.
 *
 * **Plain DataStore in app-private storage, not an encrypted store**, and that is a decision rather
 * than an oversight. `EncryptedSharedPreferences` is deprecated in androidx.security and its
 * replacement is not settled; what it bought on a device with file-based encryption and a lock
 * screen was protection against another app reading the file, which app-private storage already
 * gives. The credential's real defence is that it is revocable: `DELETE /v1/devices/{id}` from any
 * other device, or the admin dashboard, kills it. Revisit if ViveNotes ever holds something that
 * cannot be revoked.
 *
 * One JSON blob rather than five keys, for the reason [com.vivenotes.data.PenSettingsStore] uses
 * one per pen: these five values are only ever read and written together, and a half-written
 * registration is a credential that cannot work.
 */
class SyncAccountStore(context: Context) {

    private val appContext = context.applicationContext
    private val store = appContext.syncPreferences

    /** Null until this installation has been connected to a server. */
    val account: Flow<SyncAccount?> = store.data.map { prefs ->
        prefs[ACCOUNT]?.let(::decode)
    }

    /**
     * This device's stable, server-scoped app id, assigned on first use and retained locally.
     *
     * **Deliberately outside [SyncAccount] and deliberately untouched by [clear].** It is what the
     * Google routes send as `device.installationId`, and the server uses it to recognise a returning
     * installation: signing out and back in then rotates the one device row instead of adding a
     * second. Tie it to the account record and every disconnect would mint a new identity, which is
     * exactly the growing list of unprunable "Pixel Tablet" rows the device-name suffix exists to
     * make readable.
     *
     * New installations derive the UUID from `ANDROID_ID` plus [serverBaseUrl]. That Android value
     * is scoped by Android to this signing key, user, and device; hashing it with the server origin
     * means neither the raw value nor a cross-server identifier leaves the app. Unlike the previous
     * random UUID, the result survives reinstalling or clearing app data on the same tablet—the
     * failure mode that otherwise produced several active rows carrying the same device suffix.
     * Existing stored random UUIDs are retained, avoiding a one-time duplicate on upgrade.
     *
     * The read-then-write is safe against two callers because DataStore serialises `edit`
     * transactions: the second one sees the first one's value and returns it rather than replacing it.
     */
    @SuppressLint("HardwareIds")
    suspend fun installationId(serverBaseUrl: String): String {
        store.data.map { it[INSTALLATION_ID] }.first()?.let { return it }

        val androidId = Settings.Secure
            .getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
        val candidate = androidId?.let { serverScopedInstallationId(it, serverBaseUrl) }
            ?: UUID.randomUUID().toString()
        var assigned = ""
        store.edit { prefs ->
            assigned = prefs[INSTALLATION_ID]
                ?: candidate.also { prefs[INSTALLATION_ID] = it }
        }
        return assigned
    }

    suspend fun setAccount(account: SyncAccount) {
        store.edit { it[ACCOUNT] = syncAccountJson.encodeToString(SyncAccount.serializer(), account) }
    }

    /**
     * Forgets the registration locally. It does **not** revoke the current token on the server —
     * that is a request this app cannot make once the token is gone, so revocation belongs on the
     * device list or the admin dashboard.
     */
    suspend fun clear() {
        store.edit { it.remove(ACCOUNT) }
    }

    /**
     * A blob written by a build with a field this one does not have decodes anyway; one missing a
     * required field is treated as no registration at all, because a partial credential would fail
     * every request while the UI claimed to be connected.
     */
    private fun decode(text: String): SyncAccount? =
        runCatching { syncAccountJson.decodeFromString(SyncAccount.serializer(), text) }.getOrNull()

    private companion object {
        val ACCOUNT = stringPreferencesKey("account")

        /** Separate from [ACCOUNT] so that forgetting a registration does not forget the install. */
        val INSTALLATION_ID = stringPreferencesKey("installationId")
    }
}

private val syncAccountJson: Json = Json { ignoreUnknownKeys = true }

/** A UUIDv8-shaped, one-way identifier that cannot be correlated between two server origins. */
internal fun serverScopedInstallationId(androidId: String, serverBaseUrl: String): String {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest("viveNotes-installation\u0000$serverBaseUrl\u0000$androidId".encodeToByteArray())
        .copyOf(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x80).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val buffer = ByteBuffer.wrap(bytes)
    return UUID(buffer.long, buffer.long).toString()
}
