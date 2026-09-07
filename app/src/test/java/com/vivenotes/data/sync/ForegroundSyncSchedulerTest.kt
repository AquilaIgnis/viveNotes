package com.vivenotes.data.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Test

/** The event coordinator on virtual time, with no Room, lifecycle process, or network. */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundSyncSchedulerTest {

    @Test
    fun `an idle foreground never syncs on time alone`() = runTest {
        var syncs = 0
        val scheduler = scheduler(sync = { syncs++ })

        scheduler.start()
        runCurrent()
        advanceTimeBy(24 * 60 * 60 * 1_000L)
        runCurrent()

        assertEquals(0, syncs)
    }

    @Test
    fun `a non-empty outbox wakes once until it drains`() = runTest {
        var syncs = 0
        val pending = MutableStateFlow(false)
        val scheduler = scheduler(localChanges = pending, sync = { syncs++ })
        scheduler.start()
        runCurrent()

        pending.value = true
        runCurrent()
        assertEquals(1, syncs)

        // More rows while it is already non-empty are part of the same durable drain.
        pending.value = true
        runCurrent()
        assertEquals(1, syncs)

        pending.value = false
        runCurrent()
        pending.value = true
        runCurrent()
        assertEquals(2, syncs)
    }

    @Test
    fun `remote notifications wake sync and bursts are conflated`() = runTest {
        var syncs = 0
        val remote = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val scheduler = scheduler(
            remoteChanges = remote,
            sync = {
                syncs++
                // Hold the collector long enough for a burst to arrive behind this run.
                kotlinx.coroutines.delay(1_000)
            },
        )
        scheduler.start()
        runCurrent()

        remote.emit(Unit)
        runCurrent()
        repeat(5) { remote.emit(Unit) }
        runCurrent()
        assertEquals(1, syncs)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, syncs)
    }

    @Test
    fun `stays parked until a registration exists`() = runTest {
        var syncs = 0
        val registered = MutableStateFlow(false)
        val pending = MutableStateFlow(true)
        val scheduler = scheduler(
            registered = registered,
            localChanges = pending,
            sync = { syncs++ },
        )

        scheduler.start()
        runCurrent()
        assertEquals(0, syncs)

        registered.value = true
        runCurrent()
        assertEquals(1, syncs)
    }

    @Test
    fun `stopping an idle listener does not contact the server`() = runTest {
        var syncs = 0
        var catchUps = 0
        val scheduler = scheduler(
            hasPendingChanges = { false },
            sync = { syncs++ },
            requestBackgroundCatchUp = { catchUps++ },
        )

        scheduler.start()
        runCurrent()
        scheduler.stop()
        runCurrent()

        assertEquals(0, syncs)
        assertEquals(0, catchUps)
    }

    @Test
    fun `stopping with local work flushes and schedules a durable catch-up`() = runTest {
        var syncs = 0
        var catchUps = 0
        val scheduler = scheduler(
            hasPendingChanges = { true },
            sync = { syncs++ },
            requestBackgroundCatchUp = { catchUps++ },
        )

        scheduler.start()
        runCurrent()
        scheduler.stop()
        runCurrent()

        assertEquals(1, syncs)
        assertEquals(1, catchUps)
    }

    @Test
    fun `starting twice does not duplicate listeners`() = runTest {
        var syncs = 0
        val remote = MutableSharedFlow<Unit>()
        val scheduler = scheduler(remoteChanges = remote, sync = { syncs++ })

        scheduler.start()
        runCurrent()
        scheduler.start()
        runCurrent()
        remote.emit(Unit)
        runCurrent()

        assertEquals(1, syncs)
    }

    private fun TestScope.scheduler(
        registered: MutableStateFlow<Boolean> = MutableStateFlow(true),
        localChanges: MutableStateFlow<Boolean> = MutableStateFlow(false),
        remoteChanges: MutableSharedFlow<Unit> = MutableSharedFlow(),
        hasPendingChanges: suspend () -> Boolean = { false },
        sync: suspend () -> Unit,
        requestBackgroundCatchUp: () -> Unit = {},
    ) = ForegroundSyncScheduler(
        scope = backgroundScope,
        registered = registered,
        localChanges = localChanges,
        remoteChanges = remoteChanges,
        hasPendingChanges = hasPendingChanges,
        sync = sync,
        requestBackgroundCatchUp = requestBackgroundCatchUp,
    )
}
