package com.vivenotes.data.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The clock, on virtual time.
 *
 * Every assertion here is about *how often* a sync happens, which is the part WorkManager could not
 * express and the reason this class exists at all. `runCurrent()` follows each `advanceTimeBy` on
 * purpose: the latter stops short of tasks scheduled at exactly the instant it advances to, and an
 * interval boundary is exactly such an instant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundSyncSchedulerTest {

    @Test
    fun `syncs immediately and then once per interval`() = runTest {
        var syncs = 0
        val scheduler = ForegroundSyncScheduler(
            scope = backgroundScope,
            registered = flowOf(true),
            sync = { syncs++ },
            intervalMillis = INTERVAL,
        )

        scheduler.start()
        runCurrent()
        // Foregrounding is itself a reason to sync: whatever arrived while the app was away should
        // be on screen before the first interval elapses.
        assertEquals(1, syncs)

        advanceTimeBy(INTERVAL)
        runCurrent()
        assertEquals(2, syncs)

        advanceTimeBy(3 * INTERVAL)
        runCurrent()
        assertEquals(5, syncs)
    }

    @Test
    fun `stays parked until a registration exists`() = runTest {
        var syncs = 0
        val registered = MutableStateFlow(false)
        val scheduler = ForegroundSyncScheduler(
            scope = backgroundScope,
            registered = registered,
            sync = { syncs++ },
            intervalMillis = INTERVAL,
        )

        scheduler.start()
        advanceTimeBy(100 * INTERVAL)
        runCurrent()
        // An installation with no server does not poll one.
        assertEquals(0, syncs)

        registered.value = true
        runCurrent()
        assertEquals(1, syncs)
    }

    @Test
    fun `stopping flushes once more and hands the outbox to WorkManager`() = runTest {
        var syncs = 0
        var catchUps = 0
        val scheduler = ForegroundSyncScheduler(
            scope = backgroundScope,
            registered = flowOf(true),
            sync = { syncs++ },
            requestBackgroundCatchUp = { catchUps++ },
            intervalMillis = INTERVAL,
        )

        scheduler.start()
        runCurrent()
        assertEquals(1, syncs)

        scheduler.stop()
        runCurrent()
        // SD6's mandatory final flush, or backgrounding the app strands up to a full interval of
        // work — plus the worker, for the case where the process does not survive the flush.
        assertEquals(2, syncs)
        assertEquals(1, catchUps)

        advanceTimeBy(100 * INTERVAL)
        runCurrent()
        assertEquals(2, syncs)
    }

    @Test
    fun `starting twice does not double the cadence`() = runTest {
        var syncs = 0
        val scheduler = ForegroundSyncScheduler(
            scope = backgroundScope,
            registered = flowOf(true),
            sync = { syncs++ },
            intervalMillis = INTERVAL,
        )

        scheduler.start()
        runCurrent()
        scheduler.start()
        runCurrent()
        assertEquals(1, syncs)

        advanceTimeBy(INTERVAL)
        runCurrent()
        // Two loops would have polled twice here, and would go on doing so for the life of the
        // process — the failure mode is permanent, not a one-off duplicate request.
        assertEquals(2, syncs)
    }

    private companion object {
        const val INTERVAL = 60_000L
    }
}
