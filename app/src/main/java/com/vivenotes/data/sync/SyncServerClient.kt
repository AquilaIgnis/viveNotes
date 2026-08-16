package com.vivenotes.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Why connecting to a server failed, in the terms the person who typed the address can act on.
 *
 * Deliberately not the HTTP status and not the server's `message` field: viveCServer's contract
 * (`docs/openapi.yaml`) says client logic branches on the `error` code and treats `message` as
 * human-readable detail that may change. This enum is that decision made once, at the boundary, so
 * no caller is tempted to match on prose.
 */
enum class ConnectFailure {
    /** Not a usable http/https address at all — nothing was sent. */
    InvalidAddress,

    /** Nothing answered: wrong host or port, server down, no route. */
    Unreachable,

    /** `401 invalid_credentials`. */
    InvalidCredentials,

    /** `400 invalid_request` — the server refused the email or password as malformed or too short. */
    InvalidRequest,

    /** `413 payload_too_large`, which for this endpoint means an implausibly long field. */
    PayloadTooLarge,

    /** `500 internal`, or any error code this build does not know. */
    ServerError,

    /**
     * Something answered and it was not viveCServer: a proxy error page, a different service on
     * that port, a captive portal. Worth distinguishing from [Unreachable] because the fix is
     * different — the address resolves, it is just pointing at the wrong thing.
     */
    NotAViveServer,

    /**
     * The device registered but the token could not be written to disk, so it is gone. Not a
     * network outcome at all — it is here because it is the one other way connecting ends without a
     * usable credential, and the user needs to be told rather than shown a success. See
     * [SyncAccounts.connect].
     */
    NotStored,

    /**
     * `401 unauthenticated` — "unknown or revoked token". The device was revoked on the server, or
     * the account was rebuilt underneath it.
     *
     * Distinct from [InvalidCredentials] because the two are opposite instructions: bad credentials
     * mean *retype the password*, while a revoked token means *this stored registration is dead and
     * there is nothing to retry with*. Only the second one is a reason to delete a credential.
     */
    Revoked,
}

/** Whether a stored device token still works — see [SyncServerClient.checkToken]. */
sealed interface TokenCheck {

    data object Valid : TokenCheck

    /** The server said so, in as many words. The only outcome that may delete a stored token. */
    data object Revoked : TokenCheck

    /**
     * Nothing was learned: offline, DNS failure, the server down or answering 5xx.
     *
     * Deliberately not folded into [Revoked]. A tablet carried out of wifi range would otherwise
     * delete a working, non-reissuable credential because it could not reach the server to ask.
     */
    data class Unknown(val reason: ConnectFailure) : TokenCheck
}

/** The outcome of `POST /v1/devices`. */
sealed interface DeviceRegistration {

    /**
     * [token] is the only copy that will ever exist. The server stores its SHA-256 and cannot
     * reissue it, so a caller that drops this value has silently orphaned a device row and must
     * register again to get a working credential.
     */
    data class Registered(
        val deviceId: String,
        val accountId: String,
        val token: String,
    ) : DeviceRegistration

    data class Rejected(val reason: ConnectFailure) : DeviceRegistration
}

/**
 * Normalises what somebody typed into the Server URL field into a base URL, or null if it cannot be
 * one.
 *
 * A bare `notes.example.com` gets **https**, not http. The app permits cleartext (see
 * `res/xml/network_security_config.xml`), which makes the default the only thing standing between a
 * typo-free entry and sending an account password in the clear — so plain http has to be asked for
 * explicitly, by typing the scheme. The server's own README writes its local address as
 * `http://10.0.2.2:5444` for exactly that reason.
 *
 * A path is kept, because a self-hoster may well put the server behind a reverse proxy at a prefix.
 * A query or fragment is refused rather than dropped: they cannot be meaningful here, so their
 * presence means the field holds something other than a server address.
 */
fun normaliseServerAddress(typed: String): String? {
    val trimmed = typed.trim()
    if (trimmed.isEmpty()) return null

    val withScheme = if (SCHEME_PREFIX.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
    val uri = try {
        URI(withScheme)
    } catch (malformed: java.net.URISyntaxException) {
        return null
    }

    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    if (uri.host.isNullOrBlank()) return null
    if (uri.query != null || uri.fragment != null) return null
    if (uri.userInfo != null) return null

    val port = if (uri.port == -1) "" else ":${uri.port}"
    val path = uri.path.orEmpty().trimEnd('/')
    return "$scheme://${uri.host.lowercase()}$port$path"
}

/**
 * The viveCServer sync API — `viveCServer/docs/openapi.yaml`, which is the contract this file is
 * written against and the thing to re-read before adding an operation.
 *
 * `HttpURLConnection` rather than a new HTTP dependency, following
 * [com.vivenotes.ai.VerifiedArtifactDownloader]: this makes two request shapes in the whole app, and
 * neither needs connection pooling, interceptors or a client lifecycle. [openConnection] is
 * injectable for the same reason it is there.
 *
 * Android-free on purpose, so `app/src/test` can drive it against a real loopback server rather than
 * a mock of the thing being tested.
 */
class SyncServerClient(
    private val openConnection: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
) {

    /**
     * Exchanges account credentials for a device token — the one endpoint that takes a password, and
     * the one that returns a token, so it is called once per installation rather than per sync.
     *
     * [serverBaseUrl] must already have been through [normaliseServerAddress].
     */
    suspend fun registerDevice(
        serverBaseUrl: String,
        email: String,
        password: String,
        deviceName: String,
        platform: String,
    ): DeviceRegistration = withContext(Dispatchers.IO) {
        val body = syncJson.encodeToString(
            RegisterDeviceRequest.serializer(),
            RegisterDeviceRequest(
                // Trimmed here as well as on the server, which lowercases and trims before lookup:
                // a trailing space picked up from autofill should not read as a wrong password.
                // The password is never trimmed - whitespace in it is part of it.
                email = email.trim(),
                password = password,
                name = deviceName.trim().take(MAX_DEVICE_NAME).ifBlank { FALLBACK_DEVICE_NAME },
                platform = platform.take(MAX_PLATFORM),
            ),
        ).encodeToByteArray()

        val url = try {
            URL("$serverBaseUrl/v1/devices")
        } catch (malformed: java.net.MalformedURLException) {
            return@withContext DeviceRegistration.Rejected(ConnectFailure.InvalidAddress)
        }

        val connection = try {
            openConnection(url)
        } catch (unreachable: IOException) {
            return@withContext DeviceRegistration.Rejected(ConnectFailure.Unreachable)
        }

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            // Generous, because the server hashes the password with Argon2id before answering. A
            // tight read timeout here reads to the user as "server down" on a server that is
            // working exactly as designed.
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }

            val status = connection.responseCode
            val payload = readBounded(
                if (status in 200..299) connection.inputStream else connection.errorStream,
            )

            if (status in 200..299) {
                val registered = runCatching {
                    syncJson.decodeFromString(RegisterDeviceResponse.serializer(), payload)
                }.getOrNull()
                    ?: return@withContext DeviceRegistration.Rejected(ConnectFailure.NotAViveServer)
                DeviceRegistration.Registered(
                    deviceId = registered.deviceId,
                    accountId = registered.accountId,
                    token = registered.token,
                )
            } else {
                DeviceRegistration.Rejected(failureFor(status, payload))
            }
        } catch (failed: IOException) {
            // Covers DNS, connection refused, both timeouts and a connection dropped mid-body. They
            // are one thing to the person looking at the screen: nothing answered.
            DeviceRegistration.Rejected(ConnectFailure.Unreachable)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Asks the server whether a stored device token is still accepted, with `GET /v1/devices`.
     *
     * That endpoint is used rather than a dedicated probe because it is the cheapest authenticated
     * call in the contract and the answer is a side effect of the middleware, not of the handler —
     * any authenticated endpoint would do, and this one has no arguments to get wrong. The device
     * list itself is discarded; only the verdict matters here.
     *
     * **[TokenCheck.Revoked] is returned only for an explicit 401.** Everything else that goes wrong
     * is [TokenCheck.Unknown], because the caller deletes a non-reissuable credential on the strength
     * of this answer and "the server did not respond" is not the server saying no.
     */
    suspend fun checkToken(serverBaseUrl: String, token: String): TokenCheck =
        withContext(Dispatchers.IO) {
            val url = try {
                URL("$serverBaseUrl/v1/devices")
            } catch (malformed: java.net.MalformedURLException) {
                return@withContext TokenCheck.Unknown(ConnectFailure.InvalidAddress)
            }

            val connection = try {
                openConnection(url)
            } catch (unreachable: IOException) {
                return@withContext TokenCheck.Unknown(ConnectFailure.Unreachable)
            }

            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $token")

                val status = connection.responseCode
                val payload = readBounded(
                    if (status in 200..299) connection.inputStream else connection.errorStream,
                )

                when {
                    status in 200..299 -> TokenCheck.Valid
                    status == 401 -> TokenCheck.Revoked
                    else -> TokenCheck.Unknown(failureFor(status, payload))
                }
            } catch (failed: IOException) {
                TokenCheck.Unknown(ConnectFailure.Unreachable)
            } finally {
                connection.disconnect()
            }
        }

    /**
     * Maps an error response to a [ConnectFailure], preferring the `error` code and falling back to
     * the status when there is no readable body.
     *
     * **The fallback is not defensive padding — a 401 reaches it routinely.** `HttpURLConnection`
     * handles authentication itself, and on the JVM implementation it consumes a 401's body while
     * looking for a challenge it can answer, leaving `getErrorStream()` null. Android's
     * implementation is OkHttp-backed and hands the body over, so without this the *same* wrong
     * password would report "not a viveNotes server" in unit tests and "credentials rejected" on
     * device. Reading the status is what makes the two agree.
     */
    private fun failureFor(status: Int, payload: String): ConnectFailure {
        val error = runCatching {
            syncJson.decodeFromString(ErrorResponse.serializer(), payload)
        }.getOrNull()?.error

        return when (error) {
            "invalid_credentials", "unauthenticated" -> ConnectFailure.InvalidCredentials
            "invalid_request" -> ConnectFailure.InvalidRequest
            "payload_too_large" -> ConnectFailure.PayloadTooLarge
            null -> failureForStatus(status)
            // "internal", and anything this build has not heard of. A code added to the contract
            // later is a server-side problem from here, not a reason to claim the address is wrong.
            else -> ConnectFailure.ServerError
        }
    }

    /**
     * The statuses this endpoint documents, read without a body.
     *
     * Anything else that answers without a viveCServer error body is treated as the wrong service
     * rather than a failure of this one — a 404 from a reverse proxy is the common case, and telling
     * the user their password is wrong would send them to fix the one thing that is fine.
     */
    private fun failureForStatus(status: Int): ConnectFailure = when {
        status == 400 -> ConnectFailure.InvalidRequest
        status == 401 || status == 403 -> ConnectFailure.InvalidCredentials
        status == 413 -> ConnectFailure.PayloadTooLarge
        status >= 500 -> ConnectFailure.ServerError
        else -> ConnectFailure.NotAViveServer
    }

    /**
     * Reads at most [MAX_RESPONSE_BYTES].
     *
     * The bodies in this contract are a few hundred bytes. Reading unboundedly from an address the
     * user typed means an arbitrary host can decide how much memory this allocates.
     */
    private fun readBounded(stream: InputStream?): String {
        if (stream == null) return ""
        return stream.use { input ->
            val buffer = ByteArray(MAX_RESPONSE_BYTES)
            var filled = 0
            while (filled < buffer.size) {
                val count = input.read(buffer, filled, buffer.size - filled)
                if (count < 0) break
                filled += count
            }
            String(buffer, 0, filled, Charsets.UTF_8)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_RESPONSE_BYTES = 64 * 1024

        /** `RegisterDeviceRequest.name` is capped at 128 in the contract, `platform` at 64. */
        const val MAX_DEVICE_NAME = 128
        const val MAX_PLATFORM = 64

        /** `name` is required and must be non-empty after trimming. */
        const val FALLBACK_DEVICE_NAME = "viveNotes"
    }
}

@Serializable
private data class RegisterDeviceRequest(
    val email: String,
    val password: String,
    val name: String,
    val platform: String,
)

@Serializable
private data class RegisterDeviceResponse(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("accountId") val accountId: String,
    val token: String,
)

@Serializable
private data class ErrorResponse(val error: String, val message: String? = null)

/**
 * `ignoreUnknownKeys`, so a field the server adds later does not fail a response this build
 * understands — the contract marks its request schemas `additionalProperties: true` with the same
 * intent in the other direction.
 *
 * Not `coerceInputValues`: unlike the settings blobs in [com.vivenotes.data.PenSettingsStore], every
 * field here is required and a missing one means the answer did not come from viveCServer. Quietly
 * defaulting an absent `token` to an empty string would store a credential that cannot work.
 */
private val syncJson: Json = Json { ignoreUnknownKeys = true }

/**
 * Matches a leading `scheme://`, the one part of an address [normaliseServerAddress] may supply.
 *
 * Anchored, so `containsMatchIn` still only accepts it at the start — a host that merely contains
 * `://` further along is not a scheme.
 */
private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
