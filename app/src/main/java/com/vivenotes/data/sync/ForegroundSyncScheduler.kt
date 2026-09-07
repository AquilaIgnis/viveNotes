package com.vivenotes.data.sync

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Event-driven foreground synchronisation.
 *
 * [localChanges] is Room's durable outbox and [remoteChanges] is the server's authenticated event
 * stream. Both are hints, not state: [HierarchySync] still reads the outbox and the server cursor as
 * the authorities, which makes coalescing safe and makes reconnect catch-up identical to startup.
 *
 * A conflated channel is the important bit. Ten strokes committed while a run is in flight ask for
 * one more run, not ten; the outbox generation and batch idempotency keep the exact work durable.
 *
 * The seams are flows and lambdas rather than a [SyncAccounts] so this can be tested on the JVM
 * without Room, a lifecycle process, or a server.
 */
class ForegroundSyncScheduler(
    private val scope: CoroutineScope,
    /** Whether a registration exists. False closes the stream and parks both collectors. */
    private val registered: Flow<Boolean>,
    private val localChanges: Flow<Boolean>,
    private val remoteChanges: Flow<Unit>,
    private val hasPendingChanges: suspend () -> Boolean,
    private val sync: suspend () -> Unit,
    private val requestBackgroundCatchUp: () -> Unit = {},
) : DefaultLifecycleObserver {

    private var collector: Job? = null

    override fun onStart(owner: LifecycleOwner) = start()

    override fun onStop(owner: LifecycleOwner) = stop()

    /**
     * Starts listening. Re-entrant: a second call while already running is ignored rather than
     * opening a second server stream and duplicating every local wakeup.
     */
    fun start() {
        if (collector?.isActive == true) return
        collector = scope.launch {
            registered.distinctUntilChanged().collectLatest { isRegistered ->
                if (!isRegistered) return@collectLatest

                coroutineScope {
                    val wakeups = Channel<Unit>(Channel.CONFLATED)
                    launch {
                        localChanges.distinctUntilChanged().collect { pending ->
                            if (pending) wakeups.trySend(Unit)
                        }
                    }
                    launch {
                        remoteChanges.collect { wakeups.trySend(Unit) }
                    }

                    for (ignored in wakeups) runSync()
                }
            }
        }
    }

    /**
     * Stops listening and hands off only when local work is actually waiting.
     *
     * The point-in-time query runs after the collectors are cancelled so an invalidation racing the
     * lifecycle transition cannot be missed. An empty outbox means no final cursor request and no
     * WorkManager job. With work, the direct flush handles the ordinary case and WorkManager is the
     * process-death backstop.
     */
    fun stop() {
        collector?.cancel()
        collector = null
        scope.launch {
            if (!hasPendingChanges()) return@launch
            requestBackgroundCatchUp()
            runSync()
        }
    }

    private suspend fun runSync() {
        try {
            sync()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // One bad wakeup must not end both listeners. Transport and protocol failures are
            // already results rather than exceptions, so reaching here means local storage failed;
            // the next outbox event, reconnect, or worker retry gets another chance.
            Log.w(TAG, "Foreground event sync failed", failure)
        }
    }

    private companion object {
        const val TAG = "HierarchySync"
    }
}
