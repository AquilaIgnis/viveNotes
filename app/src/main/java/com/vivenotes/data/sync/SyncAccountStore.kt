package com.vivenotes.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
     * The bearer credential, held because the server cannot reissue it — only its SHA-256 is
     * stored. Losing this row means registering a new device and leaving an orphan on the server,
     * which is why it is written in the same `edit` as everything else here.
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

    private val store = context.applicationContext.syncPreferences

    /** Null until this installation has been connected to a server. */
    val account: Flow<SyncAccount?> = store.data.map { prefs ->
        prefs[ACCOUNT]?.let(::decode)
    }

    suspend fun setAccount(account: SyncAccount) {
        store.edit { it[ACCOUNT] = syncAccountJson.encodeToString(SyncAccount.serializer(), account) }
    }

    /**
     * Forgets the registration locally. It does **not** revoke the device on the server — that is a
     * request this app cannot make once the token is gone, so revocation belongs on the device list
     * or the admin dashboard.
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
    }
}

private val syncAccountJson: Json = Json { ignoreUnknownKeys = true }
