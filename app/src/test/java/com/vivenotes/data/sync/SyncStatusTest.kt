package com.vivenotes.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which failures put a Cloud Off on the account button.
 *
 * The badge is a claim about the *server*, not about sync, and the distinction is the whole value of
 * it: a cloud with a line through it that also appears for a rejected change or a revoked token
 * teaches its reader to check the network when the network is fine. So the mapping is pinned here
 * rather than left to whoever next adds a failure kind — a new [ConnectFailure] that should badge
 * and does not is a silent regression, and one that badges and should not is a misleading UI.
 */
class SyncStatusTest {

    private fun after(result: SyncRunResult) = SyncStatus(failure = result)

    private fun retrying(reason: ConnectFailure) = after(SyncRunResult.Retryable(reason))

    @Test
    fun `nothing answering is the cloud off state`() {
        assertTrue(retrying(ConnectFailure.Unreachable).serverUnreachable)
    }

    @Test
    fun `something answering that is not the server is also cloud off`() {
        // The address resolves and a proxy error page comes back. Different fix, same fact: this
        // device is not talking to viveCServer.
        assertTrue(retrying(ConnectFailure.NotAViveServer).serverUnreachable)
    }

    @Test
    fun `a server that answers with an error is not cloud off`() {
        assertFalse(retrying(ConnectFailure.ServerError).serverUnreachable)
    }

    @Test
    fun `a revoked device is not cloud off`() {
        // The server is right there and saying no. The account screen sends this one back to its
        // sign-in form, which is a different thing to show than a broken connection.
        assertFalse(after(SyncRunResult.Revoked).serverUnreachable)
        assertFalse(retrying(ConnectFailure.Revoked).serverUnreachable)
    }

    @Test
    fun `a change this build cannot store is not cloud off`() {
        val stuck = SyncRunResult.Failed(PermanentSyncFailure.UnsupportedKind)
        assertFalse(after(stuck).serverUnreachable)
    }

    @Test
    fun `local trouble is not cloud off`() {
        assertFalse(after(SyncRunResult.Failed(PermanentSyncFailure.LocalData)).serverUnreachable)
    }

    @Test
    fun `a working sync is not cloud off`() {
        assertFalse(SyncStatus().serverUnreachable)
        assertFalse(
            SyncStatus(
                lastSucceededAtMillis = 1_755_000_000_000L,
                lastSummary = SyncSummary(pulled = 2, pushed = 1, conflictsResolved = 0),
            ).serverUnreachable,
        )
    }

    /**
     * A run in flight does not clear the badge. The last thing anyone knows is still the last thing
     * that happened, and a state that blinked off every interval while the retry was attempted would
     * be unreadable at the debug clock's five seconds.
     */
    @Test
    fun `a retry in progress still shows the last verdict`() {
        val retrying = SyncStatus(
            running = true,
            failure = SyncRunResult.Retryable(ConnectFailure.Unreachable),
        )

        assertTrue(retrying.serverUnreachable)
    }
}
