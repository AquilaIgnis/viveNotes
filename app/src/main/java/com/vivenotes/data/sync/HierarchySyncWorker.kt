package com.vivenotes.data.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vivenotes.NotesApplication
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

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
        private const val PERIODIC_WORK = "hierarchy-sync-periodic"
        private const val FAILURE_REASON = "failureReason"

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Startup catch-up plus the server contract's low-cost periodic cursor poll. */
        fun schedule(context: Context) {
            requestNow(context)
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<HierarchySyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build(),
            )
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
