package com.vivenotes.data.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vivenotes.NotesApplication
import kotlinx.coroutines.CancellationException

/** Persistent, connected-network fallback for work handed off while the app backgrounds. */
class HierarchySyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {

    override suspend fun doWork(): Result = try {
        when (val sync = (applicationContext as NotesApplication).syncAccounts.synchronize()) {
            null,
            is SyncRunResult.Succeeded,
            SyncRunResult.Revoked,
            -> Result.success()

            is SyncRunResult.Retryable -> {
                Log.w(TAG, "Hierarchy sync will retry: ${sync.reason}")
                Result.retry()
            }

            is SyncRunResult.Failed -> {
                Log.e(TAG, "Hierarchy sync stopped: ${sync.reason}")
                Result.failure(workDataOf(FAILURE_REASON to sync.reason.name))
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Log.w(TAG, "Hierarchy sync will retry after an unexpected failure", failure)
        Result.retry()
    }

    companion object {
        private const val TAG = "HierarchySync"
        private const val IMMEDIATE_WORK = "hierarchy-sync-now"
        private const val LEGACY_PERIODIC_WORK = "hierarchy-sync-periodic"
        private const val FAILURE_REASON = "failureReason"

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * Removes the timer installed by older APKs.
         *
         * The foreground stream now delivers its own reconnect backlog, so launch does not enqueue
         * a second catch-up request. Existing one-time work is left intact because it represents a
         * real background handoff whose outbox may still need retrying.
         */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(LEGACY_PERIODIC_WORK)
        }

        /** Coalesces background/process-death handoffs into one durable fallback run. */
        fun requestNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<HierarchySyncWorker>()
                    .setConstraints(constraints)
                    .build(),
            )
        }
    }
}
