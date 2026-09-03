package com.vivenotes.data.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress

/**
 * Exercises the client against a real HTTP server on loopback rather than a fake connection.
 *
 * The `HttpURLConnection` behaviour this depends on is not incidental — a POST body written through
 * fixed-length streaming, a status line, and error bodies arriving on `errorStream` rather than
 * `inputStream` — and a hand-written fake would be a fake of exactly the part that can be wrong.
 * The JDK's `HttpServer` costs one port and no dependency.
 *
 * Responses here are copied from `viveCServer/docs/openapi.yaml`. When that contract changes, this
 * is the file that should fail first.
 */
class SyncServerClientTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    /** Set per test, before the call. */
    private var respond: (HttpExchange) -> Unit = { send(it, 404, "") }

    private var requestMethod: String? = null
    private var requestPath: String? = null
    private var requestQuery: String? = null
    private var requestBody: String? = null
    private var requestBytes: ByteArray? = null
    private var authorization: String? = null

    @Before
    fun startServer() {
        // Port 0: the OS picks a free one, so a developer running the suite while their own
        // viveCServer is up does not collide with it.
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requestMethod = exchange.requestMethod
            requestPath = exchange.requestURI.path
            requestQuery = exchange.requestURI.query
            requestBytes = exchange.requestBody.readBytes()
            requestBody = requestBytes!!.decodeToString()
            authorization = exchange.requestHeaders.getFirst("Authorization")
            respond(exchange)
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun registeringADeviceSendsTheContractsBodyAndReturnsTheToken() = runBlocking {
        respond = {
            send(
                it,
                201,
                """
                {
                  "deviceId": "7af9be36-8f89-4b31-bc78-3ef246837469",
                  "accountId": "a62c615f-5a73-47bb-b704-ad49cf527ec2",
                  "token": "vive_c29tZS1leGFtcGxlLWRldmljZS10b2tlbg"
                }
                """.trimIndent(),
            )
        }

        val result = SyncServerClient().registerDevice(
            serverBaseUrl = baseUrl,
            email = "  owner@example.com  ",
            password = " correct horse ",
            deviceName = "Pixel Tablet",
            platform = "Android 16",
        )

        assertEquals("POST", requestMethod)
        assertEquals("/v1/devices", requestPath)

        val sent = Json.parseToJsonElement(requestBody.orEmpty()).jsonObject
        assertEquals("owner@example.com", sent.getValue("email").jsonPrimitive.content)
        // Not trimmed: leading and trailing spaces are part of a password, and silently removing
        // them would authenticate against something the user did not type.
        assertEquals(" correct horse ", sent.getValue("password").jsonPrimitive.content)
        assertEquals("Pixel Tablet", sent.getValue("name").jsonPrimitive.content)
        assertEquals("Android 16", sent.getValue("platform").jsonPrimitive.content)

        val registered = result as DeviceRegistration.Registered
        assertEquals("7af9be36-8f89-4b31-bc78-3ef246837469", registered.deviceId)
        assertEquals("a62c615f-5a73-47bb-b704-ad49cf527ec2", registered.accountId)
        assertEquals("vive_c29tZS1leGFtcGxlLWRldmljZS10b2tlbg", registered.token)
    }

    @Test
    fun aBlankDeviceNameFallsBackRatherThanBeingRefusedByTheServer() = runBlocking {
        respond = { send(it, 201, """{"deviceId":"d","accountId":"a","token":"vive_t"}""") }

        SyncServerClient().registerDevice(baseUrl, "owner@example.com", "pw", "   ", "Android")

        val sent = Json.parseToJsonElement(requestBody.orEmpty()).jsonObject
        // `name` is required and must be non-empty after trimming, so an emulator image with a
        // blank Build.MODEL must not produce a 400.
        assertEquals("ViveNotes", sent.getValue("name").jsonPrimitive.content)
    }

    /**
     * Covers the status fallback rather than the `error` code, and does so without asking to.
     *
     * `HttpURLConnection` consumes a 401's body itself while looking for an authentication
     * challenge, so on the JVM `getErrorStream()` here is null and the code below never sees
     * `invalid_credentials`. Android's OkHttp-backed implementation does hand the body over. The
     * client has to reach the same answer either way, which is what this asserts — the body is sent
     * exactly as the contract documents it, deliberately, even though this JVM will drop it.
     */
    @Test
    fun aWrongPasswordIsReportedAsCredentials() = runBlocking {
        respond = {
            send(
                it,
                401,
                """{"error":"invalid_credentials","message":"email or password is incorrect"}""",
            )
        }

        assertEquals(
            DeviceRegistration.Rejected(ConnectFailure.InvalidCredentials),
            SyncServerClient().registerDevice(baseUrl, "owner@example.com", "wrong", "Tablet", "Android"),
        )
    }

    @Test
    fun aRejectedFieldIsReportedAsAnInvalidRequest() = runBlocking {
        respond = {
            send(it, 400, """{"error":"invalid_request","message":"password is too short"}""")
        }

        assertEquals(
            DeviceRegistration.Rejected(ConnectFailure.InvalidRequest),
            SyncServerClient().registerDevice(baseUrl, "owner@example.com", "short", "Tablet", "Android"),
        )
    }

    /** An error code added to the contract after this build shipped is the server's problem. */
    @Test
    fun anUnknownErrorCodeIsAServerProblemRatherThanAWrongAddress() = runBlocking {
        respond = { send(it, 418, """{"error":"some_future_code","message":"?"}""") }

        assertEquals(
            DeviceRegistration.Rejected(ConnectFailure.ServerError),
            SyncServerClient().registerDevice(baseUrl, "owner@example.com", "pw", "Tablet", "Android"),
        )
    }

    @Test
    fun somethingThatIsNotViveCServerIsDistinguishedFromAnOutage() = runBlocking {
        respond = { send(it, 404, "<html><body>nginx</body></html>", contentType = "text/html") }

        assertEquals(
            DeviceRegistration.Rejected(ConnectFailure.NotAViveServer),
            SyncServerClient().registerDevice(baseUrl, "owner@example.com", "pw", "Tablet", "Android"),
        )
    }

    /** A proxy with no backend answers 5xx in its own words. That is an outage, not a wrong port. */
    @Test
    fun aServerErrorWithoutAViveBodyIsStillAServerError() = runBlocking {
        respond = { send(it, 502, "Bad Gateway", contentType = "text/plain") }

        assertEquals(
            DeviceRegistration.Rejected(ConnectFailure.ServerError),
            SyncServerClient().registerDevice(baseUrl, "owner@example.com", "pw", "Tablet", "Android"),
        )
    }

    /**
     * A 201 whose body has no token is not a success. Storing the registration anyway would leave
     * the app believing it is connected while holding a credential it can never authenticate with.
     */
    @Test
    fun aSuccessMissingTheTokenIsNotTreatedAsASuccess() = runBlocking {
        respond = { send(it, 201, """{"deviceId":"d","accountId":"a"}""") }

        assertEquals(
            DeviceRegistration.Rejected(ConnectFailure.NotAViveServer),
            SyncServerClient().registerDevice(baseUrl, "owner@example.com", "pw", "Tablet", "Android"),
        )
    }

    @Test
    fun nothingListeningIsUnreachable() = runBlocking {
        val address = baseUrl
        server.stop(0)

        assertEquals(
            DeviceRegistration.Rejected(ConnectFailure.Unreachable),
            SyncServerClient().registerDevice(address, "owner@example.com", "pw", "Tablet", "Android"),
        )
    }

    @Test
    fun aWorkingTokenChecksOutAndIsSentAsABearerCredential() = runBlocking {
        respond = {
            send(it, 200, """{"devices":[]}""")
        }

        assertEquals(TokenCheck.Valid, SyncServerClient().checkToken(baseUrl, "vive_abc"))
        assertEquals("GET", requestMethod)
        assertEquals("/v1/devices", requestPath)
        assertEquals("Bearer vive_abc", authorization)
    }

    /** The one answer that may delete a token that cannot be reissued. */
    @Test
    fun aRevokedTokenIsReportedAsRevoked() = runBlocking {
        respond = {
            it.responseHeaders.add("WWW-Authenticate", """Bearer realm="vivecserver"""")
            send(it, 401, """{"error":"unauthenticated","message":"unknown or revoked token"}""")
        }

        assertEquals(TokenCheck.Revoked, SyncServerClient().checkToken(baseUrl, "vive_revoked"))
    }

    /**
     * The distinction the whole check exists for. A tablet carried out of wifi range must come back
     * still connected, so nothing short of the server itself saying no may look like revocation.
     */
    @Test
    fun anUnreachableServerLeavesTheTokensStandingUnknown() = runBlocking {
        val address = baseUrl
        server.stop(0)

        assertEquals(
            TokenCheck.Unknown(ConnectFailure.Unreachable),
            SyncServerClient().checkToken(address, "vive_abc"),
        )
    }

    @Test
    fun aServerErrorIsNotRevocationEither() = runBlocking {
        respond = { send(it, 503, "Service Unavailable", contentType = "text/plain") }

        assertEquals(
            TokenCheck.Unknown(ConnectFailure.ServerError),
            SyncServerClient().checkToken(baseUrl, "vive_abc"),
        )
    }

    @Test
    fun cursorPollUsesBearerAuthAndDecodesTheContract() = runBlocking {
        respond = { send(it, 200, """{"cursor":41}""") }

        assertEquals(ServerResult.Success(41L), SyncServerClient().getCursor(baseUrl, "vive_abc"))
        assertEquals("GET", requestMethod)
        assertEquals("/v1/cursor", requestPath)
        assertEquals("Bearer vive_abc", authorization)
    }

    @Test
    fun pullSendsItsExclusiveCursorAndKeepsUnknownChangeFields() = runBlocking {
        respond = {
            send(
                it,
                200,
                """
                {
                  "changes": [{
                    "kind":"notebook", "id":"n", "version":2, "seq":42,
                    "updatedAt":10, "deletedAt":null, "name":"N", "colorArgb":1,
                    "sortIndex":0, "expanded":true, "createdAt":1, "future":"kept"
                  }],
                  "cursor":42,
                  "hasMore":false
                }
                """.trimIndent(),
            )
        }

        val result = SyncServerClient().pullChanges(baseUrl, "vive_abc", 41, 512)

        assertEquals("since=41&limit=512", requestQuery)
        val page = (result as ServerResult.Success).value
        assertEquals(42L, page.cursor)
        assertEquals("kept", page.changes.single().getValue("future").jsonPrimitive.content)
    }

    @Test
    fun pushSendsTheSuppliedIdempotencyKeyAndDecodesAppliedAndRejectedRows() = runBlocking {
        respond = {
            send(
                it,
                200,
                """
                {
                  "applied":[{"kind":"notebook","id":"n","version":3}],
                  "rejected":[{
                    "kind":"page", "id":"p", "reason":"missing_parent",
                    "message":"section is absent"
                  }],
                  "cursor":8
                }
                """.trimIndent(),
            )
        }
        val change = buildJsonObject {
            put("kind", "notebook")
            put("id", "n")
            put("baseVersion", 2)
            put("updatedAt", 10)
        }

        val result = SyncServerClient().pushChanges(
            baseUrl,
            "vive_abc",
            "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
            listOf(change),
        )

        assertEquals("POST", requestMethod)
        assertEquals("/v1/changes", requestPath)
        assertEquals("Bearer vive_abc", authorization)
        val sent = Json.parseToJsonElement(requestBody.orEmpty()).jsonObject
        assertEquals(
            "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
            sent.getValue("batchId").jsonPrimitive.content,
        )
        val reply = (result as ServerResult.Success).value
        assertEquals(AppliedServerChange("notebook", "n", 3), reply.applied.single())
        assertEquals("missing_parent", reply.rejected.single().reason)
        assertEquals(8L, reply.serverCursor)
    }

    @Test
    fun authenticatedSync401IsRevocationRatherThanARetryableOutage() = runBlocking {
        respond = {
            it.responseHeaders.add("WWW-Authenticate", "Bearer")
            send(it, 401, """{"error":"unauthenticated"}""")
        }

        assertEquals(ServerResult.Unauthorized, SyncServerClient().getCursor(baseUrl, "vive_dead"))
    }

    @Test
    fun revokingThisDeviceUsesTheDocumentedPathAndNoBody() = runBlocking {
        respond = { send(it, 204, "") }

        assertEquals(
            ServerResult.Success(Unit),
            SyncServerClient().revokeDevice(baseUrl, "vive_abc", "device-id"),
        )
        assertEquals("DELETE", requestMethod)
        assertEquals("/v1/devices/device-id", requestPath)
        assertEquals("", requestBody)
    }

    // --- attachment bytes (S5) --------------------------------------------------------------

    @Test
    fun aBlobHeadAsksTheDocumentedPathAndReportsWhatTheAccountHolds() = runBlocking {
        respond = { sendEmpty(it, 204) }

        assertEquals(
            ServerResult.Success(true),
            SyncServerClient().hasBlob(baseUrl, "vive_abc", DIGEST),
        )
        assertEquals("HEAD", requestMethod)
        assertEquals("/v1/blobs/$DIGEST", requestPath)
        assertEquals("Bearer vive_abc", authorization)

        respond = { sendEmpty(it, 404) }
        assertEquals(
            ServerResult.Success(false),
            SyncServerClient().hasBlob(baseUrl, "vive_abc", DIGEST),
        )
    }

    @Test
    fun uploadingSendsTheRawBytesAndSaysWhetherTheyWereStored() = runBlocking {
        val file = temporaryFile(PICTURE)
        respond = { sendEmpty(it, 201) }

        assertEquals(
            ServerResult.Success(true),
            SyncServerClient().uploadBlob(baseUrl, "vive_abc", DIGEST, file),
        )
        assertEquals("PUT", requestMethod)
        assertEquals("/v1/blobs/$DIGEST", requestPath)
        // The body is the bytes themselves: not JSON, not base64.
        assertArrayEquals(PICTURE, requestBytes)

        // The same picture from a second device is one file and a 204, which is a success that did
        // not store anything — the difference the summary counts.
        respond = { sendEmpty(it, 204) }
        assertEquals(
            ServerResult.Success(false),
            SyncServerClient().uploadBlob(baseUrl, "vive_abc", DIGEST, file),
        )
    }

    @Test
    fun anAttachmentOverTheServersCapIsARefusalToRetry() = runBlocking {
        val file = temporaryFile(PICTURE)
        respond = {
            send(it, 413, """{"error":"payload_too_large","message":"an attachment may not exceed 33554432 bytes"}""")
        }

        assertEquals(
            ServerResult.Failed(ConnectFailure.PayloadTooLarge, retryable = false),
            SyncServerClient().uploadBlob(baseUrl, "vive_abc", DIGEST, file),
        )
    }

    @Test
    fun aDownloadIsKeptOnlyWhenTheBytesHashToTheNameTheyWereFetchedUnder() = runBlocking {
        val target = temporaryFile(ByteArray(0)).also { it.delete() }
        respond = { send(it, 200, PICTURE.decodeToString(), "application/octet-stream") }

        assertEquals(
            ServerResult.Success(true),
            SyncServerClient().downloadBlob(baseUrl, "vive_abc", DIGEST, target),
        )
        assertEquals("GET", requestMethod)
        assertArrayEquals(PICTURE, target.readBytes())

        // Content addressing makes this check free of trust: bytes that do not hash to their own
        // name are not the picture the document asked for, whatever produced them. Nothing is left
        // behind for a caller that only looked for an exception.
        target.delete()
        respond = { send(it, 200, "not the picture", "application/octet-stream") }
        assertEquals(
            ServerResult.Failed(ConnectFailure.NotAViveServer, retryable = false),
            SyncServerClient().downloadBlob(baseUrl, "vive_abc", DIGEST, target),
        )
        assertFalse(target.exists())
    }

    @Test
    fun aPictureTheServerDoesNotHaveIsAFactRatherThanAFailure() = runBlocking {
        val target = temporaryFile(ByteArray(0)).also { it.delete() }
        respond = { send(it, 404, """{"error":"not_found","message":"no such attachment"}""") }

        assertEquals(
            ServerResult.Success(false),
            SyncServerClient().downloadBlob(baseUrl, "vive_abc", DIGEST, target),
        )
        assertFalse(target.exists())
    }

    @Test
    fun anIdentityThatIsNotADigestNeverReachesTheNetwork() = runBlocking {
        val target = temporaryFile(ByteArray(0)).also { it.delete() }
        respond = { send(it, 200, "") }

        // Uppercase is refused rather than folded, exactly as the server refuses it: two spellings
        // of one identity is how one picture becomes two files. The traversal case is why this is
        // checked on the client at all — the digest is a file name here.
        listOf(DIGEST.uppercase(), "abc", "../../etc/passwd", "").forEach { notADigest ->
            assertEquals(
                ServerResult.Failed(ConnectFailure.InvalidAddress, retryable = false),
                SyncServerClient().hasBlob(baseUrl, "vive_abc", notADigest),
            )
            assertEquals(
                ServerResult.Failed(ConnectFailure.InvalidAddress, retryable = false),
                SyncServerClient().downloadBlob(baseUrl, "vive_abc", notADigest, target),
            )
        }
        assertNull(requestPath)
    }

    @Test
    fun aBareHostGetsHttpsSoCleartextHasToBeAskedFor() {
        assertEquals("https://notes.example.com", normaliseServerAddress("notes.example.com"))
        assertEquals("https://notes.example.com", normaliseServerAddress("  notes.example.com/  "))
    }

    @Test
    fun anExplicitSchemePortAndPathSurvive() {
        assertEquals("http://10.0.2.2:5444", normaliseServerAddress("http://10.0.2.2:5444"))
        assertEquals("http://10.0.2.2:5444", normaliseServerAddress("http://10.0.2.2:5444/"))
        assertEquals("https://example.com/vive", normaliseServerAddress("https://EXAMPLE.com/vive/"))
    }

    @Test
    fun somethingThatIsNotAServerAddressIsRefusedBeforeAnyRequest() {
        assertNull(normaliseServerAddress(""))
        assertNull(normaliseServerAddress("   "))
        assertNull(normaliseServerAddress("ftp://example.com"))
        assertNull(normaliseServerAddress("https://"))
        // A query or fragment cannot mean anything for a base URL, so its presence means the field
        // holds something else - a pasted dashboard link, most likely.
        assertNull(normaliseServerAddress("https://example.com/?token=abc"))
        assertNull(normaliseServerAddress("https://example.com/#setup"))
        assertNull(normaliseServerAddress("https://user:pw@example.com"))
    }

    /** A response with no body at all, which is the only shape a `HEAD` may answer with. */
    // ------------------------------------------------------------ Account creation ----

    @Test
    fun creatingAnAccountSendsTheContractsBodyAndReturnsTheId() = runBlocking {
        respond = {
            send(it, 201, """{"accountId":"a62c615f-5a73-47bb-b704-ad49cf527ec2"}""")
        }

        val result = SyncServerClient().createAccount(
            serverBaseUrl = baseUrl,
            email = "  Owner@Example.com  ",
            password = " correct horse ",
        )

        assertEquals("POST", requestMethod)
        assertEquals("/v1/accounts", requestPath)
        // No credential exists yet; this is the request that makes one possible.
        assertNull(authorization)
        val sent = Json.parseToJsonElement(requestBody!!).jsonObject
        assertEquals("Owner@Example.com", sent["email"]!!.jsonPrimitive.content)
        // Never trimmed: whitespace in a password is part of it.
        assertEquals(" correct horse ", sent["password"]!!.jsonPrimitive.content)
        assertEquals(
            AccountCreation.Created("a62c615f-5a73-47bb-b704-ad49cf527ec2"),
            result,
        )
    }

    /** The default posture of a viveCServer, so it must not read as anything having gone wrong. */
    @Test
    fun aServerWithRegistrationsClosedSaysSoRatherThanRefusingCredentials() = runBlocking {
        respond = {
            send(
                it,
                403,
                """{"error":"signup_closed","message":"this server does not accept registrations"}""",
            )
        }

        val result = SyncServerClient().createAccount(baseUrl, "owner@example.com", "correct horse")

        assertEquals(AccountCreation.Rejected(ConnectFailure.SignupClosed), result)
    }

    @Test
    fun anAddressAlreadyInUseIsItsOwnFailure() = runBlocking {
        respond = {
            send(it, 409, """{"error":"email_taken","message":"an account already exists"}""")
        }

        val result = SyncServerClient().createAccount(baseUrl, "owner@example.com", "correct horse")

        assertEquals(AccountCreation.Rejected(ConnectFailure.EmailTaken), result)
    }

    /**
     * The status has to carry these two on its own whenever the body does not arrive. Everywhere
     * else in this contract a 403 is bad credentials and a 409 is nothing at all, so without the
     * per-route fallback a closed signup would tell the user their password was wrong.
     */
    @Test
    fun bodilessRefusalsStillMeanClosedSignupAndTakenAddress() = runBlocking {
        respond = { sendEmpty(it, 403) }
        val closed = SyncServerClient().createAccount(baseUrl, "owner@example.com", "correct horse")

        respond = { sendEmpty(it, 409) }
        val taken = SyncServerClient().createAccount(baseUrl, "owner@example.com", "correct horse")

        assertEquals(AccountCreation.Rejected(ConnectFailure.SignupClosed), closed)
        assertEquals(AccountCreation.Rejected(ConnectFailure.EmailTaken), taken)
    }

    /** A password under the contract's minimum, if the disabled button is ever got around. */
    @Test
    fun aRejectedPasswordComesBackAsAnInvalidRequest() = runBlocking {
        respond = {
            send(
                it,
                400,
                """{"error":"invalid_request","message":"password must be at least 8 characters"}""",
            )
        }

        val result = SyncServerClient().createAccount(baseUrl, "owner@example.com", "short")

        assertEquals(AccountCreation.Rejected(ConnectFailure.InvalidRequest), result)
    }

    // ---------------------------------------------------------------- Google auth ----

    @Test
    fun creatingAChallengeReturnsTheNonceToHandToCredentialManager() = runBlocking {
        respond = {
            send(
                it,
                201,
                """
                {
                  "challengeId": "0d1f6b4c-1b6f-4a3f-9c2e-3a5f7b8c9d01",
                  "nonce": "Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MGFiY2RlZmdoaWprbG0",
                  "expiresAt": "2026-08-30T12:05:00Z"
                }
                """.trimIndent(),
            )
        }

        val result = SyncServerClient().createGoogleChallenge(baseUrl)

        assertEquals("POST", requestMethod)
        assertEquals("/v1/auth/google/challenges", requestPath)
        // No credential: this is the one call made before the installation has any.
        assertNull(authorization)
        val challenge = (result as ServerResult.Success).value
        assertEquals("0d1f6b4c-1b6f-4a3f-9c2e-3a5f7b8c9d01", challenge.challengeId)
        assertEquals("Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MGFiY2RlZmdoaWprbG0", challenge.nonce)
    }

    /** `503 google_auth_unavailable` is the one challenge failure worth trying again. */
    @Test
    fun anUnconfiguredServerMakesTheChallengeRetryable() = runBlocking {
        respond = {
            send(it, 503, """{"error":"google_auth_unavailable","message":"not configured"}""")
        }

        val result = SyncServerClient().createGoogleChallenge(baseUrl)

        val failed = result as ServerResult.Failed
        assertEquals(ConnectFailure.GoogleUnavailable, failed.reason)
        assertTrue(failed.retryable)
    }

    @Test
    fun signingInWithGoogleSendsTheContractsBodyAndReturnsTheToken() = runBlocking {
        respond = {
            send(
                it,
                200,
                """
                {
                  "accountId": "a62c615f-5a73-47bb-b704-ad49cf527ec2",
                  "deviceId": "7af9be36-8f89-4b31-bc78-3ef246837469",
                  "token": "vive_c29tZS1leGFtcGxlLWRldmljZS10b2tlbg",
                  "tokenExpiresAt": "2026-11-28T12:00:00Z",
                  "createdAccount": true
                }
                """.trimIndent(),
            )
        }

        val result = SyncServerClient().signInWithGoogle(
            serverBaseUrl = baseUrl,
            challengeId = "0d1f6b4c-1b6f-4a3f-9c2e-3a5f7b8c9d01",
            idToken = "header.payload.signature",
            idempotencyKey = "4f2a1c88-0e3d-4c1b-8b4a-77c9f0e5d612",
            device = GoogleDeviceDetails(
                installationId = "9b1c2d3e-4f50-4a6b-8c7d-9e0f1a2b3c4d",
                name = "  Pixel Tablet  ",
                platform = "Android 16",
                appVersion = "1.1.1",
                locale = "en-US",
            ),
        )

        assertEquals("/v1/auth/google", requestPath)
        val sent = Json.parseToJsonElement(requestBody!!).jsonObject
        assertEquals("header.payload.signature", sent["idToken"]!!.jsonPrimitive.content)
        val device = sent["device"]!!.jsonObject
        assertEquals("9b1c2d3e-4f50-4a6b-8c7d-9e0f1a2b3c4d", device["installationId"]!!.jsonPrimitive.content)
        assertEquals("Pixel Tablet", device["name"]!!.jsonPrimitive.content)
        // Absent optional details are omitted rather than sent as explicit nulls: the schema marks
        // them optional, not nullable.
        assertFalse(device.containsKey("model"))

        val authenticated = result as GoogleAuthentication.Authenticated
        assertEquals("vive_c29tZS1leGFtcGxlLWRldmljZS10b2tlbg", authenticated.token)
        assertTrue(authenticated.createdAccount)
    }

    /**
     * The one response that is not an outcome: it is the contract naming the next request. Reading
     * it as a failure would send the user back to a button that will do exactly this again.
     */
    @Test
    fun anExistingPasswordAccountAsksForLinkingRatherThanFailing() = runBlocking {
        respond = {
            send(
                it,
                409,
                """{"error":"account_link_required","message":"verify the existing account"}""",
            )
        }

        val result = SyncServerClient().signInWithGoogle(
            serverBaseUrl = baseUrl,
            challengeId = "0d1f6b4c-1b6f-4a3f-9c2e-3a5f7b8c9d01",
            idToken = "header.payload.signature",
            idempotencyKey = "4f2a1c88-0e3d-4c1b-8b4a-77c9f0e5d612",
            device = googleDevice(),
        )

        assertEquals(GoogleAuthentication.LinkRequired, result)
    }

    /** The other 409 on the same route, which is a failure and must not be read as the first. */
    @Test
    fun anIdentityHeldByAnotherAccountIsAFailure() = runBlocking {
        respond = {
            send(it, 409, """{"error":"identity_conflict","message":"linked elsewhere"}""")
        }

        val result = SyncServerClient().signInWithGoogle(
            serverBaseUrl = baseUrl,
            challengeId = "0d1f6b4c-1b6f-4a3f-9c2e-3a5f7b8c9d01",
            idToken = "header.payload.signature",
            idempotencyKey = "4f2a1c88-0e3d-4c1b-8b4a-77c9f0e5d612",
            device = googleDevice(),
        )

        assertEquals(
            GoogleAuthentication.Rejected(ConnectFailure.IdentityConflict),
            result,
        )
    }

    @Test
    fun anExpiredChallengeIsItsOwnFailure() = runBlocking {
        respond = {
            send(it, 400, """{"error":"invalid_challenge","message":"expired"}""")
        }

        val result = SyncServerClient().signInWithGoogle(
            serverBaseUrl = baseUrl,
            challengeId = "0d1f6b4c-1b6f-4a3f-9c2e-3a5f7b8c9d01",
            idToken = "header.payload.signature",
            idempotencyKey = "4f2a1c88-0e3d-4c1b-8b4a-77c9f0e5d612",
            device = googleDevice(),
        )

        assertEquals(GoogleAuthentication.Rejected(ConnectFailure.InvalidChallenge), result)
    }

    /**
     * The trap `failureFor` documents, on this route: `HttpURLConnection` eats a 401's body on the
     * JVM while looking for a challenge it can answer, so the code is routinely unavailable and the
     * status has to carry the meaning. On sign-in a 401 is the ID token; on the link route it is far
     * more likely the password, and telling somebody to retype a correct password is the failure
     * this distinction exists to prevent.
     */
    @Test
    fun aBodilessUnauthorizedMeansTheTokenOnSignInAndThePasswordOnLinking() = runBlocking {
        respond = { sendEmpty(it, 401) }

        val signIn = SyncServerClient().signInWithGoogle(
            serverBaseUrl = baseUrl,
            challengeId = "0d1f6b4c-1b6f-4a3f-9c2e-3a5f7b8c9d01",
            idToken = "header.payload.signature",
            idempotencyKey = "4f2a1c88-0e3d-4c1b-8b4a-77c9f0e5d612",
            device = googleDevice(),
        )
        val link = SyncServerClient().linkGoogleIdentity(
            serverBaseUrl = baseUrl,
            challengeId = "0d1f6b4c-1b6f-4a3f-9c2e-3a5f7b8c9d01",
            idToken = "header.payload.signature",
            idempotencyKey = "5a3b2d99-1f4e-4d2c-9c5b-88daf1e6c723",
            device = googleDevice(),
            email = "owner@example.com",
            password = "correct horse",
        )

        assertEquals(GoogleAuthentication.Rejected(ConnectFailure.InvalidGoogleToken), signIn)
        assertEquals(GoogleAuthentication.Rejected(ConnectFailure.InvalidCredentials), link)
    }

    @Test
    fun linkingSendsTheSameChallengeAndTokenPlusTheAccountsCredentials() = runBlocking {
        respond = {
            send(
                it,
                200,
                """
                {
                  "accountId": "a62c615f-5a73-47bb-b704-ad49cf527ec2",
                  "deviceId": "7af9be36-8f89-4b31-bc78-3ef246837469",
                  "token": "vive_c29tZS1leGFtcGxlLWRldmljZS10b2tlbg",
                  "tokenExpiresAt": "2026-11-28T12:00:00Z",
                  "createdAccount": false
                }
                """.trimIndent(),
            )
        }

        val result = SyncServerClient().linkGoogleIdentity(
            serverBaseUrl = baseUrl,
            challengeId = "0d1f6b4c-1b6f-4a3f-9c2e-3a5f7b8c9d01",
            idToken = "header.payload.signature",
            idempotencyKey = "5a3b2d99-1f4e-4d2c-9c5b-88daf1e6c723",
            device = googleDevice(),
            email = "  Owner@Example.com  ",
            password = " correct horse ",
        )

        assertEquals("/v1/auth/google/link", requestPath)
        val sent = Json.parseToJsonElement(requestBody!!).jsonObject
        assertEquals("0d1f6b4c-1b6f-4a3f-9c2e-3a5f7b8c9d01", sent["challengeId"]!!.jsonPrimitive.content)
        assertEquals("header.payload.signature", sent["idToken"]!!.jsonPrimitive.content)
        assertEquals("Owner@Example.com", sent["email"]!!.jsonPrimitive.content)
        // Never trimmed: whitespace in a password is part of it.
        assertEquals(" correct horse ", sent["password"]!!.jsonPrimitive.content)
        assertFalse((result as GoogleAuthentication.Authenticated).createdAccount)
    }

    /** An over-long detail is clipped here rather than costing a 400 at the end of a sign-in. */
    @Test
    fun overLongDeviceDetailsAreClippedToTheContractsLimits() = runBlocking {
        respond = { send(it, 503, """{"error":"google_auth_unavailable"}""") }

        SyncServerClient().signInWithGoogle(
            serverBaseUrl = baseUrl,
            challengeId = "0d1f6b4c-1b6f-4a3f-9c2e-3a5f7b8c9d01",
            idToken = "header.payload.signature",
            idempotencyKey = "4f2a1c88-0e3d-4c1b-8b4a-77c9f0e5d612",
            device = googleDevice().copy(name = "n".repeat(200), model = "m".repeat(200)),
        )

        val device = Json.parseToJsonElement(requestBody!!).jsonObject["device"]!!.jsonObject
        assertEquals(128, device["name"]!!.jsonPrimitive.content.length)
        assertEquals(128, device["model"]!!.jsonPrimitive.content.length)
    }

    /** A stripped ROM reporting no model must not produce the empty name the contract refuses. */
    @Test
    fun aBlankDeviceNameFallsBackToTheProductName() = runBlocking {
        respond = { send(it, 503, """{"error":"google_auth_unavailable"}""") }

        SyncServerClient().signInWithGoogle(
            serverBaseUrl = baseUrl,
            challengeId = "0d1f6b4c-1b6f-4a3f-9c2e-3a5f7b8c9d01",
            idToken = "header.payload.signature",
            idempotencyKey = "4f2a1c88-0e3d-4c1b-8b4a-77c9f0e5d612",
            device = googleDevice().copy(name = "   "),
        )

        val device = Json.parseToJsonElement(requestBody!!).jsonObject["device"]!!.jsonObject
        assertEquals("ViveNotes", device["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun managedSubscriptionDecodesPaidAndPromotionalSources() = runBlocking {
        respond = {
            send(
                it,
                200,
                """
                {
                  "state":"active",
                  "validUntil":"2026-12-03T12:00:00Z",
                  "promotionalValidUntil":"2026-12-03T12:00:00Z",
                  "paidValidUntil":"2026-10-03T12:00:00Z",
                  "paidState":"active",
                  "autoRenewing":true,
                  "productId":"vivenotes_storage_monthly"
                }
                """.trimIndent(),
            )
        }

        val result = SyncServerClient().getSubscription(baseUrl, "vive_abc")

        assertEquals("GET", requestMethod)
        assertEquals("/v1/subscription", requestPath)
        assertEquals("Bearer vive_abc", authorization)
        assertEquals(
            SubscriptionResult.Success(
                ManagedSubscriptionStatus(
                    active = true,
                    validUntil = "2026-12-03T12:00:00Z",
                    paidState = PaidSubscriptionState.Active,
                    paidValidUntil = "2026-10-03T12:00:00Z",
                    promotionalValidUntil = "2026-12-03T12:00:00Z",
                    autoRenewing = true,
                    productId = "vivenotes_storage_monthly",
                ),
            ),
            result,
        )
    }

    @Test
    fun redeemingCouponTrimsTheCodeAndUsesTheAuthenticatedContract() = runBlocking {
        respond = {
            send(
                it,
                200,
                """{
                  "code":"FREE-MONTH",
                  "monthsGranted":1,
                  "redeemedAt":"2026-09-03T12:00:00Z",
                  "validUntil":"2026-10-03T12:00:00Z"
                }""",
            )
        }

        val result = SyncServerClient().redeemCoupon(baseUrl, "vive_abc", " free-month ")

        assertEquals("POST", requestMethod)
        assertEquals("/v1/coupons/redeem", requestPath)
        assertEquals("Bearer vive_abc", authorization)
        assertEquals(
            "free-month",
            Json.parseToJsonElement(requestBody.orEmpty()).jsonObject["code"]!!.jsonPrimitive.content,
        )
        assertEquals(
            SubscriptionResult.Success(
                CouponGrant("FREE-MONTH", 1, "2026-10-03T12:00:00Z"),
            ),
            result,
        )
    }

    @Test
    fun completedPlayPurchaseSendsOnlyTheOpaqueTokenAndProduct() = runBlocking {
        respond = {
            send(it, 200, """{"state":"active","autoRenewing":true}""")
        }

        val result = SyncServerClient().confirmGooglePlaySubscription(
            serverBaseUrl = baseUrl,
            token = "vive_abc",
            purchaseToken = "opaque-play-token",
            productId = "vivenotes_storage_monthly",
        )

        assertEquals("POST", requestMethod)
        assertEquals("/v1/billing/google-play/subscriptions", requestPath)
        assertEquals("Bearer vive_abc", authorization)
        val sent = Json.parseToJsonElement(requestBody.orEmpty()).jsonObject
        assertEquals("opaque-play-token", sent["purchaseToken"]!!.jsonPrimitive.content)
        assertEquals("vivenotes_storage_monthly", sent["productId"]!!.jsonPrimitive.content)
        assertEquals(
            SubscriptionResult.Success(
                ManagedSubscriptionStatus(
                    active = true,
                    validUntil = null,
                    paidState = null,
                    paidValidUntil = null,
                    promotionalValidUntil = null,
                    autoRenewing = true,
                    productId = null,
                ),
            ),
            result,
        )
    }

    @Test
    fun subscriptionErrorsKeepCouponAndPurchaseFailuresDistinct() = runBlocking {
        val failures = listOf(
            404 to ("invalid_coupon" to SubscriptionFailure.InvalidCoupon),
            410 to ("coupon_expired" to SubscriptionFailure.CouponExpired),
            409 to ("coupon_already_redeemed" to SubscriptionFailure.CouponAlreadyRedeemed),
            400 to ("invalid_purchase" to SubscriptionFailure.InvalidPurchase),
            409 to ("purchase_already_claimed" to SubscriptionFailure.PurchaseAlreadyClaimed),
            503 to ("billing_unavailable" to SubscriptionFailure.BillingUnavailable),
        )
        for ((status, expectation) in failures) {
            val (code, expected) = expectation
            respond = { send(it, status, """{"error":"$code","message":"refused"}""") }

            assertEquals(
                SubscriptionResult.Failed(expected),
                SyncServerClient().redeemCoupon(baseUrl, "vive_abc", "ABC"),
            )
        }
    }

    @Test
    fun subscriptionUnauthorizedIsTheOnlyTransportAnswerThatRevokesTheSession() = runBlocking {
        respond = {
            it.responseHeaders.add("WWW-Authenticate", "Bearer")
            send(it, 401, """{"error":"unauthenticated"}""")
        }

        assertEquals(
            SubscriptionResult.Unauthorized,
            SyncServerClient().getSubscription(baseUrl, "vive_dead"),
        )
    }

    private fun googleDevice(): GoogleDeviceDetails = GoogleDeviceDetails(
        installationId = "9b1c2d3e-4f50-4a6b-8c7d-9e0f1a2b3c4d",
        name = "Pixel Tablet",
        platform = "Android 16",
    )

    private fun sendEmpty(exchange: HttpExchange, status: Int) {
        exchange.sendResponseHeaders(status, -1)
        exchange.close()
    }

    private fun temporaryFile(contents: ByteArray): File =
        File.createTempFile("vive-blob", null).apply {
            deleteOnExit()
            writeBytes(contents)
        }

    private fun send(
        exchange: HttpExchange,
        status: Int,
        body: String,
        contentType: String = "application/json",
    ) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", contentType)
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        } else {
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private companion object {
        /** SHA-256 of [PICTURE], the id an attachment would have. */
        const val DIGEST = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        val PICTURE = "hello".toByteArray()
    }

    /**
     * The identity the push batch builder's size accounting rests on — see
     * `HierarchySync.loadOrCreatePendingBatch`.
     *
     * It stopped encoding the whole candidate batch per row (quadratic, and 96% of a first upload's
     * time) and now adds each change's own encoded length plus one comma. That is only exact while
     * the encoder writes compact JSON with no spacing, so this pins the assumption rather than the
     * arithmetic: a serialization upgrade that started pretty-printing would make the accumulator
     * under-count and put a request over the transport's cap, which is a permanent failure.
     */
    @Test
    fun aBatchIsExactlyItsEmptyEnvelopePlusItsChangesAndTheCommasBetweenThem() {
        val changes = listOf(
            buildJsonObject { put("kind", "inkStroke"); put("id", "a") },
            buildJsonObject { put("kind", "inkStroke"); put("id", "b") },
            buildJsonObject { put("kind", "page"); put("title", "an em dash — and a ü") },
        )
        fun envelope(of: List<kotlinx.serialization.json.JsonObject>) = kotlinx.serialization.json.JsonObject(
            linkedMapOf(
                "batchId" to kotlinx.serialization.json.JsonPrimitive("batch"),
                "changes" to kotlinx.serialization.json.JsonArray(of),
            ),
        ).toString().encodeToByteArray().size

        val accumulated = envelope(emptyList()) +
            changes.sumOf { it.toString().encodeToByteArray().size } +
            (changes.size - 1)

        assertEquals(envelope(changes), accumulated)
    }
}
