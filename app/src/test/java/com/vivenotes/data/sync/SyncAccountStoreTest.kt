package com.vivenotes.data.sync

import java.util.UUID
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncAccountStoreTest {

    @Test
    fun deviceIdentityIsStableWithinOneServerAndUnlinkableAcrossServers() {
        val androidId = "0123456789abcdef"
        val cloud = serverScopedInstallationId(androidId, "https://sync.vivenotes.net")

        assertEquals(cloud, serverScopedInstallationId(androidId, "https://sync.vivenotes.net"))
        assertNotEquals(cloud, serverScopedInstallationId(androidId, "https://notes.example.test"))
        assertNotEquals(cloud, serverScopedInstallationId("fedcba9876543210", "https://sync.vivenotes.net"))
        assertEquals(8, UUID.fromString(cloud).version())
        assertEquals(2, UUID.fromString(cloud).variant())
    }

    @Test
    fun anAccountStoredBeforeIdentityMetadataStillLoads() {
        val account = Json.decodeFromString<SyncAccount>(
            """{"serverUrl":"https://sync.vivenotes.net","accountId":"account","deviceId":"device","token":"vive_token","deviceName":"Pixel Tablet"}""",
        )

        assertNull(account.email)
        assertNull(account.authProvider)
    }

    @Test
    fun accountIdentityAndProviderRoundTrip() {
        val account = SyncAccount(
            serverUrl = "https://sync.vivenotes.net",
            accountId = "account",
            deviceId = "device",
            token = "vive_token",
            deviceName = "Pixel Tablet",
            email = "owner@example.com",
            authProvider = AccountAuthProvider.Google,
        )

        assertEquals(
            account,
            Json.decodeFromString<SyncAccount>(Json.encodeToString(account)),
        )
    }
}
