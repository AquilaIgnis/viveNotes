package com.vivenotes.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Why connecting to a server failed, in the terms the person who typed the address can act on.
 *
 * Deliberately not the HTTP status and not the server's `message` field: viveCServer's contract
 * (`viveCServer/docs/openapi.yaml`) says client logic branches on the `error` code and treats `message` as
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

/** One authenticated API operation, without turning transport failures into exceptions for callers. */
sealed interface ServerResult<out T> {

    data class Success<T>(val value: T) : ServerResult<T>

    /** The server explicitly rejected this stored bearer credential. */
    data object Unauthorized : ServerResult<Nothing>

    /**
     * [retryable] distinguishes an outage/5xx from a request the same client would send identically
     * forever. WorkManager retries only the first group.
     */
    data class Failed(
        val reason: ConnectFailure,
        val retryable: Boolean,
    ) : ServerResult<Nothing>
}

/** One complete `GET /v1/changes` response page. */
data class PullChangesPage(
    val changes: List<JsonObject>,
    val cursor: Long,
    val hasMore: Boolean,
)

data class AppliedServerChange(val kind: String, val id: String, val version: Long)

data class RejectedServerChange(
    val kind: String,
    val id: String,
    val reason: String,
    val message: String?,
    val current: JsonObject?,
)

data class PushChangesReply(
    val applied: List<AppliedServerChange>,
    val rejected: List<RejectedServerChange>,
    /** Informational only. The contract explicitly says this is not a pull cursor. */
    val serverCursor: Long,
)

/** Narrow authenticated contract used by the hierarchy coordinator and its deterministic tests. */
interface SyncTransport {
    suspend fun getCursor(serverBaseUrl: String, token: String): ServerResult<Long>

    suspend fun pullChanges(
        serverBaseUrl: String,
        token: String,
        since: Long,
        limit: Int = 512,
    ): ServerResult<PullChangesPage>

    suspend fun pushChanges(
        serverBaseUrl: String,
        token: String,
        batchId: String,
        changes: List<JsonObject>,
    ): ServerResult<PushChangesReply>

    suspend fun revokeDevice(
        serverBaseUrl: String,
        token: String,
        deviceId: String,
    ): ServerResult<Unit>

    // --- attachment bytes ------------------------------------------------------------------------
    //
    // The three byte routes of OpenAPI 0.5.0. They are on this interface rather than beside it so a
    // test double for the change protocol cannot silently answer "the server has every picture" —
    // uploading before the change that names a digest is the invariant the server refuses a push to
    // protect, and a fake that skipped it would prove nothing about the client half.

    /**
     * Whether this account already holds [digest] — `HEAD /v1/blobs/{sha256}`.
     *
     * The cheap half of deduplication: the same photograph imported on two tablets costs one round
     * trip on the second rather than a second upload of the megabytes.
     */
    suspend fun hasBlob(
        serverBaseUrl: String,
        token: String,
        digest: String,
    ): ServerResult<Boolean>

    /**
     * Uploads [file] under [digest] — `PUT /v1/blobs/{sha256}`.
     *
     * True when the bytes were stored (201), false when the account already held them (204). Both
     * are success: the upload is idempotent because the name is the content.
     */
    suspend fun uploadBlob(
        serverBaseUrl: String,
        token: String,
        digest: String,
        file: File,
    ): ServerResult<Boolean>

    /**
     * Downloads [digest] into [target] — `GET /v1/blobs/{sha256}`.
     *
     * False means the server has no such attachment for this account (404), which is a fact about
     * that picture rather than a failure of the connection. [target] exists afterwards only if the
     * bytes hashed to their own name.
     */
    suspend fun downloadBlob(
        serverBaseUrl: String,
        token: String,
        digest: String,
        target: File,
    ): ServerResult<Boolean>
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
 * [com.vivenotes.ai.VerifiedArtifactDownloader]: this API is low-frequency WorkManager traffic and
 * does not need an interceptor stack or an application-owned connection pool. [openConnection] is
 * injectable for the same reason it is there.
 *
 * Android-free on purpose, so `app/src/test` can drive it against a real loopback server rather than
 * a mock of the thing being tested.
 */
class SyncServerClient(
    private val openConnection: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
) : SyncTransport {

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
            // A redirect can cross hosts. Following one here would hand the account password to a
            // different origin, so a proxy must route this API without redirecting it.
            connection.instanceFollowRedirects = false
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

    /** The cheap idle poll described by `GET /v1/cursor`. */
    override suspend fun getCursor(serverBaseUrl: String, token: String): ServerResult<Long> =
        when (
            val raw = authenticatedRequest(
                serverBaseUrl = serverBaseUrl,
                path = "/v1/cursor",
                token = token,
            )
        ) {
            is RawServerResult.Response -> decodeAuthenticated(raw) { payload ->
                syncJson.decodeFromString(CursorResponse.serializer(), payload).cursor
            }
            RawServerResult.InvalidAddress -> invalidAddress()
            RawServerResult.Unreachable -> unreachable()
        }

    /** Pulls one complete server page after [since]. */
    override suspend fun pullChanges(
        serverBaseUrl: String,
        token: String,
        since: Long,
        limit: Int,
    ): ServerResult<PullChangesPage> {
        if (since < 0 || limit !in 1..MAX_PULL_LIMIT) {
            return ServerResult.Failed(ConnectFailure.InvalidRequest, retryable = false)
        }
        return when (
            val raw = authenticatedRequest(
                serverBaseUrl = serverBaseUrl,
                path = "/v1/changes?since=$since&limit=$limit",
                token = token,
                maxResponseBytes = MAX_API_RESPONSE_BYTES,
            )
        ) {
            is RawServerResult.Response -> decodeAuthenticated(raw) { payload ->
                val decoded = syncJson.decodeFromString(PullChangesResponse.serializer(), payload)
                PullChangesPage(decoded.changes, decoded.cursor, decoded.hasMore)
            }
            RawServerResult.InvalidAddress -> invalidAddress()
            RawServerResult.Unreachable -> unreachable()
        }
    }

    /**
     * Pushes one already-snapshotted hierarchy batch.
     *
     * The caller owns [batchId] and must reuse it if this request is retried. This method never
     * invents one because doing so below the durable outbox would defeat the server's idempotency
     * guarantee precisely when the response is lost.
     */
    override suspend fun pushChanges(
        serverBaseUrl: String,
        token: String,
        batchId: String,
        changes: List<JsonObject>,
    ): ServerResult<PushChangesReply> {
        if (changes.size > MAX_PUSH_CHANGES) {
            return ServerResult.Failed(ConnectFailure.PayloadTooLarge, retryable = false)
        }
        val body = syncJson.encodeToString(
            PushChangesRequest.serializer(),
            PushChangesRequest(batchId, changes),
        ).encodeToByteArray()
        if (body.size > MAX_SYNC_PUSH_BYTES) {
            return ServerResult.Failed(ConnectFailure.PayloadTooLarge, retryable = false)
        }

        return when (
            val raw = authenticatedRequest(
                serverBaseUrl = serverBaseUrl,
                path = "/v1/changes",
                token = token,
                method = "POST",
                body = body,
                maxResponseBytes = MAX_API_RESPONSE_BYTES,
            )
        ) {
            is RawServerResult.Response -> decodeAuthenticated(raw) { payload ->
                val decoded = syncJson.decodeFromString(PushChangesResponse.serializer(), payload)
                PushChangesReply(
                    applied = decoded.applied.map { AppliedServerChange(it.kind, it.id, it.version) },
                    rejected = decoded.rejected.map {
                        RejectedServerChange(it.kind, it.id, it.reason, it.message, it.current)
                    },
                    serverCursor = decoded.cursor,
                )
            }
            RawServerResult.InvalidAddress -> invalidAddress()
            RawServerResult.Unreachable -> unreachable()
        }
    }

    /** Revokes this device. A repeated delete remains a success according to the contract. */
    override suspend fun revokeDevice(
        serverBaseUrl: String,
        token: String,
        deviceId: String,
    ): ServerResult<Unit> {
        val encodedId = URLEncoder.encode(deviceId, StandardCharsets.UTF_8.name())
        return when (
            val raw = authenticatedRequest(
                serverBaseUrl = serverBaseUrl,
                path = "/v1/devices/$encodedId",
                token = token,
                method = "DELETE",
            )
        ) {
            is RawServerResult.Response -> decodeAuthenticated(raw) { Unit }
            RawServerResult.InvalidAddress -> invalidAddress()
            RawServerResult.Unreachable -> unreachable()
        }
    }

    /**
     * `HEAD /v1/blobs/{sha256}` — 204 means the account holds the bytes, 404 that it does not.
     *
     * **`Expect: 100-continue` is deliberately not used**, although the contract offers it as the
     * way to skip this request. `HttpURLConnection` turns an early final response into a
     * `ProtocolException` thrown out of the output stream rather than a status a caller can read,
     * on both the JDK and the OkHttp-backed Android implementation — so a 204 for a picture the
     * server already had would arrive as a transport error. This costs the same round trip the
     * `Expect` handshake costs and reports the same fact as a status code.
     */
    override suspend fun hasBlob(
        serverBaseUrl: String,
        token: String,
        digest: String,
    ): ServerResult<Boolean> = withContext(Dispatchers.IO) {
        val url = blobUrl(serverBaseUrl, digest) ?: return@withContext invalidAddress()
        val connection = try {
            openConnection(url)
        } catch (unreachable: IOException) {
            return@withContext unreachable()
        }
        try {
            connection.requestMethod = "HEAD"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")

            when (val status = connection.responseCode) {
                204, 200 -> ServerResult.Success(true)
                404 -> ServerResult.Success(false)
                401 -> ServerResult.Unauthorized
                else -> ServerResult.Failed(
                    reason = failureForStatus(status),
                    retryable = status >= 500,
                )
            }
        } catch (failed: IOException) {
            unreachable()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * `PUT /v1/blobs/{sha256}` — the bytes themselves, streamed from disk.
     *
     * Streamed rather than read into a `ByteArray` first: an attachment may be 32 MiB, and this runs
     * on a tablet that is also holding a page of ink. `setFixedLengthStreamingMode` is what keeps
     * `HttpURLConnection` from buffering the whole body to compute a `Content-Length` itself.
     *
     * The size is checked here as well as by the server, so a file that could only ever be refused
     * is not sent over somebody's mobile connection first.
     */
    override suspend fun uploadBlob(
        serverBaseUrl: String,
        token: String,
        digest: String,
        file: File,
    ): ServerResult<Boolean> = withContext(Dispatchers.IO) {
        val url = blobUrl(serverBaseUrl, digest) ?: return@withContext invalidAddress()
        val length = file.length()
        if (!file.isFile) {
            // The bytes this device was going to prove it holds are not there. Reported as a
            // permanent local failure so the caller stops rather than retrying a missing file.
            return@withContext ServerResult.Failed(ConnectFailure.InvalidRequest, retryable = false)
        }
        if (length > MAX_BLOB_BYTES) {
            return@withContext ServerResult.Failed(ConnectFailure.PayloadTooLarge, retryable = false)
        }

        val connection = try {
            openConnection(url)
        } catch (unreachable: IOException) {
            return@withContext unreachable()
        }
        try {
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setRequestProperty("Accept", "application/json")
            connection.setFixedLengthStreamingMode(length)
            connection.outputStream.use { out ->
                file.inputStream().use { input -> input.copyTo(out, TRANSFER_BUFFER_BYTES) }
            }

            when (val status = connection.responseCode) {
                201 -> ServerResult.Success(true)
                204, 200 -> ServerResult.Success(false)
                401 -> ServerResult.Unauthorized
                else -> ServerResult.Failed(
                    reason = failureFor(status, readBounded(connection.errorStream)),
                    retryable = status >= 500,
                )
            }
        } catch (failed: IOException) {
            unreachable()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * `GET /v1/blobs/{sha256}` into [target], which is written only if the bytes prove their name.
     *
     * **The digest is verified here rather than by the caller**, because this is the only place that
     * sees the bytes as they arrive and the only place that can refuse them without a second read of
     * the file. Content addressing makes that check free of trust: bytes that do not hash to the
     * name they were fetched under are not the picture the document asked for, whatever produced
     * them — a truncating proxy, a captive portal, the wrong server behind one address.
     *
     * [target] is removed on every failure path, so a partial body can never be renamed into the
     * attachment store by a caller that only checked for an exception.
     */
    override suspend fun downloadBlob(
        serverBaseUrl: String,
        token: String,
        digest: String,
        target: File,
    ): ServerResult<Boolean> = withContext(Dispatchers.IO) {
        val url = blobUrl(serverBaseUrl, digest) ?: return@withContext invalidAddress()
        val connection = try {
            openConnection(url)
        } catch (unreachable: IOException) {
            return@withContext unreachable()
        }
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/octet-stream")

            val status = connection.responseCode
            when {
                status == 404 -> return@withContext ServerResult.Success(false)
                status == 401 -> return@withContext ServerResult.Unauthorized
                status !in 200..299 -> return@withContext ServerResult.Failed(
                    reason = failureFor(status, readBounded(connection.errorStream)),
                    retryable = status >= 500,
                )
            }

            val digested = MessageDigest.getInstance("SHA-256")
            var written = 0L
            connection.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        // Bounded while it is being written, not after: a server answering with an
                        // endless body would otherwise fill the device before anything checked.
                        if (written > MAX_BLOB_BYTES) {
                            target.delete()
                            return@withContext ServerResult.Failed(
                                ConnectFailure.PayloadTooLarge,
                                retryable = false,
                            )
                        }
                        digested.update(buffer, 0, count)
                        out.write(buffer, 0, count)
                    }
                }
            }
            if (digested.digest().toHex() != digest) {
                target.delete()
                return@withContext ServerResult.Failed(
                    ConnectFailure.NotAViveServer,
                    retryable = false,
                )
            }
            ServerResult.Success(true)
        } catch (failed: IOException) {
            target.delete()
            unreachable()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The byte route's URL, or null when [digest] is not one.
     *
     * The shape is checked before a request is built and again wherever the digest becomes a file
     * name: it arrives from a server response and is used as a path segment on both sides, so
     * anything but 64 lowercase hex characters is refused rather than escaped. Uppercase is refused
     * too, exactly as the server refuses it — two spellings of one identity is how one picture
     * becomes two files, two rows and two uploads.
     */
    private fun blobUrl(serverBaseUrl: String, digest: String): URL? {
        if (!isBlobDigest(digest)) return null
        return try {
            URL("$serverBaseUrl/v1/blobs/$digest")
        } catch (malformed: java.net.MalformedURLException) {
            null
        }
    }

    private suspend fun authenticatedRequest(
        serverBaseUrl: String,
        path: String,
        token: String,
        method: String = "GET",
        body: ByteArray? = null,
        maxResponseBytes: Int = MAX_RESPONSE_BYTES,
    ): RawServerResult = withContext(Dispatchers.IO) {
        val url = try {
            URL("$serverBaseUrl$path")
        } catch (malformed: java.net.MalformedURLException) {
            return@withContext RawServerResult.InvalidAddress
        }
        val connection = try {
            openConnection(url)
        } catch (unreachable: IOException) {
            return@withContext RawServerResult.Unreachable
        }

        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }

            val status = connection.responseCode
            RawServerResult.Response(
                status = status,
                payload = readBounded(
                    stream = if (status in 200..299) connection.inputStream else connection.errorStream,
                    maxBytes = maxResponseBytes,
                ),
            )
        } catch (failed: IOException) {
            RawServerResult.Unreachable
        } finally {
            connection.disconnect()
        }
    }

    private inline fun <T> decodeAuthenticated(
        raw: RawServerResult.Response,
        decodeSuccess: (String) -> T,
    ): ServerResult<T> = when {
        raw.status == 401 -> ServerResult.Unauthorized
        raw.status in 200..299 -> runCatching { decodeSuccess(raw.payload) }.fold(
            onSuccess = { ServerResult.Success(it) },
            onFailure = {
                ServerResult.Failed(ConnectFailure.NotAViveServer, retryable = false)
            },
        )
        else -> ServerResult.Failed(
            reason = failureFor(raw.status, raw.payload),
            retryable = raw.status >= 500,
        )
    }

    private fun <T> invalidAddress(): ServerResult<T> =
        ServerResult.Failed(ConnectFailure.InvalidAddress, retryable = false)

    private fun <T> unreachable(): ServerResult<T> =
        ServerResult.Failed(ConnectFailure.Unreachable, retryable = true)

    /**
     * Maps an error response to a [ConnectFailure], preferring the `error` code and falling back to
     * the status when there is no readable body.
     *
     * **The fallback is not defensive padding — a 401 reaches it routinely.** `HttpURLConnection`
     * handles authentication itself, and on the JVM implementation it consumes a 401's body while
     * looking for a challenge it can answer, leaving `getErrorStream()` null. Android's
     * implementation is OkHttp-backed and hands the body over, so without this the *same* wrong
     * password would report "not a ViveNotes server" in unit tests and "credentials rejected" on
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
    private fun readBounded(
        stream: InputStream?,
        maxBytes: Int = MAX_RESPONSE_BYTES,
    ): String {
        if (stream == null) return ""
        return stream.use { input ->
            val buffer = ByteArray(maxBytes)
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
        /** One page of a file at a time, on both byte routes. */
        const val TRANSFER_BUFFER_BYTES = 64 * 1024
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_RESPONSE_BYTES = 64 * 1024
        const val MAX_API_RESPONSE_BYTES = 10 * 1024 * 1024
        const val MAX_PUSH_CHANGES = 512
        const val MAX_PULL_LIMIT = 2048

        /** `RegisterDeviceRequest.name` is capped at 128 in the contract, `platform` at 64. */
        const val MAX_DEVICE_NAME = 128
        const val MAX_PLATFORM = 64

        /**
         * `name` is required and must be non-empty after trimming.
         *
         * Spelt as the product is spelt to the user (`R.string.app_name`), because that is where it
         * is read: the account screen shows it back as "Registered as ViveNotes", and it names the
         * row in the server's own device list. It is a literal rather than a string resource on
         * purpose — this file is deliberately Android-free so `app/src/test` can drive it against a
         * real loopback server, and reaching for a `Context` here would end that.
         */
        const val FALLBACK_DEVICE_NAME = "ViveNotes"
    }
}

/** Contract cap shared with [HierarchySync], which splits its durable outbox before transport. */
internal const val MAX_SYNC_PUSH_BYTES = 4 * 1024 * 1024

/**
 * `VIVE_MAX_BLOB_BYTES`, the server's per-attachment cap and the only storage limit it has — there
 * is no per-account quota (`viveCServer/memory/syncPlan.md` §12 decision 1). It is also
 * `NotebookTransferManager`'s cap for the same field, so a picture that imports from a `.vive`
 * bundle is a picture that syncs.
 */
internal const val MAX_BLOB_BYTES = 32L * 1024 * 1024

/**
 * Whether [value] can be an attachment's identity: 64 lowercase hex characters.
 *
 * The digest is three things at once — the entity id, a `blobRefs` element and a URL path segment —
 * and on this side it is a *file name* as well. So it is validated wherever it crosses a boundary
 * rather than trusted because the server promised a pattern: a client that took an id on faith would
 * let a response name a path outside the attachment directory.
 */
internal fun isBlobDigest(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

internal fun ByteArray.toHex(): String = buildString(size * 2) {
    this@toHex.forEach { byte -> append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF]) }
}

private const val HEX = "0123456789abcdef"

private sealed interface RawServerResult {
    data class Response(val status: Int, val payload: String) : RawServerResult
    data object InvalidAddress : RawServerResult
    data object Unreachable : RawServerResult
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
private data class CursorResponse(val cursor: Long)

@Serializable
private data class PullChangesResponse(
    val changes: List<JsonObject>,
    val cursor: Long,
    @SerialName("hasMore") val hasMore: Boolean,
)

@Serializable
private data class PushChangesRequest(
    @SerialName("batchId") val batchId: String,
    val changes: List<JsonObject>,
)

@Serializable
private data class PushChangesResponse(
    val applied: List<AppliedChangeResponse>,
    val rejected: List<RejectedChangeResponse>,
    val cursor: Long,
)

@Serializable
private data class AppliedChangeResponse(
    val kind: String,
    val id: String,
    val version: Long,
)

@Serializable
private data class RejectedChangeResponse(
    val kind: String,
    val id: String,
    val reason: String,
    val message: String? = null,
    val current: JsonObject? = null,
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
