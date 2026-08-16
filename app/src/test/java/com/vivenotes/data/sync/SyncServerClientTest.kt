package com.vivenotes.data.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
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
    private var requestBody: String? = null

    @Before
    fun startServer() {
        // Port 0: the OS picks a free one, so a developer running the suite while their own
        // viveCServer is up does not collide with it.
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requestMethod = exchange.requestMethod
            requestPath = exchange.requestURI.path
            requestBody = exchange.requestBody.readBytes().decodeToString()
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
        assertEquals("viveNotes", sent.getValue("name").jsonPrimitive.content)
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
        var authorization: String? = null
        respond = {
            authorization = it.requestHeaders.getFirst("Authorization")
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

    private fun send(
        exchange: HttpExchange,
        status: Int,
        body: String,
        contentType: String = "application/json",
    ) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
