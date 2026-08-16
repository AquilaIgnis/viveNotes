package com.vivenotes

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownServiceException

/**
 * Guards `src/main/res/xml/network_security_config.xml`.
 *
 * Instrumented rather than a JVM test because the subject is not code: it is what the manifest
 * merger and the platform did with a resource. A unit test could only re-read the XML and assert it
 * says what it says.
 *
 * The failure this catches is quiet. If the attribute is dropped from the manifest, or the resource
 * is renamed or moved into a build-type source set, nothing breaks at build time — every `http://`
 * URL simply starts failing with `UnknownServiceException`, which reads like the server is down.
 * Since the address is typed by a self-hoster, that lands as a support report about their server
 * rather than a bug report about this app.
 */
@RunWith(AndroidJUnit4::class)
class NetworkSecurityConfigTest {

    @Test
    fun cleartextIsPermittedApplicationWide() {
        assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted())
    }

    /**
     * Per-host, because the application-wide flag above is only the platform's summary of the
     * config. A `domain-config` can still deny an individual host while that summary reads true, and
     * a self-hoster's address is unknowable at build time — so every host has to be permitted, not
     * just the ones a developer happens to use.
     */
    @Test
    fun cleartextIsPermittedForAnyHostASelfHosterMightType() {
        val policy = NetworkSecurityPolicy.getInstance()

        assertTrue(
            "the emulator's alias for the host machine's loopback",
            policy.isCleartextTrafficPermitted(EMULATOR_HOST_LOOPBACK),
        )
        assertTrue(
            "a physical device reaching the server through `adb reverse`",
            policy.isCleartextTrafficPermitted("127.0.0.1"),
        )
        assertTrue("a server on the LAN", policy.isCleartextTrafficPermitted("192.168.1.50"))
        assertTrue("a named host", policy.isCleartextTrafficPermitted("notes.example.com"))
    }

    /**
     * Proves the request actually reaches the network stack, which the policy queries above cannot:
     * they read configuration, and configuration is only half of what stops a connection.
     *
     * No server is required. Connection refused is a pass — it means `connect()` was attempted — and
     * so is a real response, if the sync server does happen to be reachable on this port. The single
     * outcome that fails is [UnknownServiceException], which is the policy refusing to open a socket
     * at all.
     */
    @Test
    fun aCleartextRequestReachesTheNetworkStack() {
        val connection = URL("http://127.0.0.1:$SYNC_PORT/healthz")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = CONNECT_TIMEOUT_MS

        val failure: IOException? = try {
            connection.responseCode
            null
        } catch (blocked: IOException) {
            blocked
        } finally {
            connection.disconnect()
        }

        assertFalse(
            "cleartext was blocked by policy instead of attempted: $failure",
            failure is UnknownServiceException,
        )
    }

    private companion object {
        const val EMULATOR_HOST_LOOPBACK = "10.0.2.2"

        /** `VIVE_SYNC_PORT` in the server's `deploy/.env.example`. */
        const val SYNC_PORT = 5444

        const val CONNECT_TIMEOUT_MS = 2_000
    }
}
