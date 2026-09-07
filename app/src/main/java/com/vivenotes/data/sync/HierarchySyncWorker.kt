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

/** Persistent, connected-network drain for the hierarchy outbox and pull cursor. */
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
         * One startup catch-up, with explicit removal of the timer installed by older APKs.
         *
         * Unique periodic work survives application upgrades. Merely stopping its creation would
         * leave every existing installation polling forever, so cancellation is part of the
         * migration to the event stream.
         */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(LEGACY_PERIODIC_WORK)
            requestNow(context)
        }

        /** Coalesces app startup, Connect, and future local-write hints into one outbox drain. */
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
