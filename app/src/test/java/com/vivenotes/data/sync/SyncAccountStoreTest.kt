package com.vivenotes.data.sync

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
}
