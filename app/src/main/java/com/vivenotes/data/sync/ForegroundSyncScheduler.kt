package com.vivenotes.data.sync

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.vivenotes.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The sync clock: one run every [intervalMillis] for as long as the app is in the foreground
 * (`viveCServer/memory/syncPlan.md` SD6).
 *
 * **WorkManager cannot be this clock**, which is the whole reason this class exists.
 * `PeriodicWorkRequest` has a hard 15-minute floor, and the floor is the optimistic case: the job
 * carries a `CONNECTED` network constraint and a standby bucket, so a tablet sitting idle can go far
 * longer than that without a run — long enough that a change made on a second device looks like it
 * never arrived. [HierarchySyncWorker] therefore stays what SD6 asks it to be, opportunistic
 * background catch-up, and the cadence a user actually feels is this in-process loop.
 *
 * Deliberately decoupled from autosave. Autosave debounces at 400 ms; sync flushes on its own
 * interval and neither waits on the other, so a minute of typing is one outbox row and one document
 * on the wire rather than one request per keystroke burst.
 *
 * The seams are lambdas rather than a [SyncAccounts] so the clock can be tested on the JVM with
 * virtual time, against a counter, with no Context and no server.
 */
class ForegroundSyncScheduler(
    private val scope: CoroutineScope,
    /** Whether a registration exists. False parks the clock; it does not slow it down. */
    private val registered: Flow<Boolean>,
    private val sync: suspend () -> Unit,
    private val requestBackgroundCatchUp: () -> Unit = {},
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
) : DefaultLifecycleObserver {

    private var ticker: Job? = null

    override fun onStart(owner: LifecycleOwner) = start()

    override fun onStop(owner: LifecycleOwner) = stop()

    /**
     * Starts ticking. Re-entrant: a second call while the clock is already running is ignored rather
     * than starting a second loop, because two loops would double the poll rate for good.
     */
    fun start() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                // A poll with no server to poll is not worth a wakeup. This suspends rather than
                // spinning, and resumes the instant Connect stores a registration.
                registered.first { it }
                runSync()
                delay(intervalMillis)
            }
        }
    }

    /**
     * Stops ticking and flushes once more.
     *
     * The flush is mandatory (SD6): without it, closing the app strands up to a full interval of
     * work until the next launch. It runs on the application scope, not on the cancelled ticker, and
     * cancelling that ticker mid-run costs nothing — [HierarchySync] serialises runs on a mutex and
     * keeps its outgoing batch on disk under one `batchId`, so the flush resumes the same logical
     * request instead of repeating its effects.
     */
    fun stop() {
        ticker?.cancel()
        ticker = null
        scope.launch { runSync() }
        // If the process dies before that flush finishes, WorkManager is what is left holding the
        // outbox. This is the one thing the periodic job is for.
        requestBackgroundCatchUp()
    }

    private suspend fun runSync() {
        try {
            sync()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // One bad tick must not end the clock. Transport and protocol failures are already
            // results rather than exceptions, so reaching here means local storage failed — which
            // the next tick retries, exactly as the worker's `Result.retry()` would.
            Log.w(TAG, "Foreground sync tick failed", failure)
        }
    }

    companion object {
        private const val TAG = "HierarchySync"

        /**
         * 60 s, and 5 s in debug builds — SD6's two stated values.
         *
         * `BuildConfig.DEBUG` is a compile-time constant, so the release APK contains only the 60 s
         * one. The debug value is what makes a two-device test legible: a change made on one tablet
         * shows up on the other while both are still on screen.
         */
        val DEFAULT_INTERVAL_MILLIS: Long = if (BuildConfig.DEBUG) 5_000L else 60_000L
    }
}
