package com.vivenotes.data

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vivenotes.NotesApplication
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Scheduled maintenance for the seven-day deletion recovery window. */
class DeletionPurgeWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {

    override suspend fun doWork(): Result = try {
        val repository = (applicationContext as NotesApplication).repository
        val purge = repository.purgeExpiredDeletions()
        Result.success(workDataOf(PURGED_TOMBSTONES to purge.tombstones))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // Database maintenance is idempotent. A transient open/lock/storage failure can safely use
        // WorkManager's backoff and try the exact same cutoff operation again.
        Log.w(TAG, "Deletion purge will be retried", failure)
        Result.retry()
    }

    companion object {
        private const val TAG = "DeletionPurge"
        private const val STARTUP_WORK = "deletion-purge-startup"
        private const val PERIODIC_WORK = "deletion-purge-daily"
        private const val PURGED_TOMBSTONES = "purgedTombstones"

        /**
         * Queues one prompt catch-up and one durable daily pass.
         *
         * Both names are unique because [NotesApplication.onCreate] runs for every process start.
         * `KEEP` prevents repeated launches from multiplying workers while still allowing a new
         * one-time catch-up after the previous request has finished.
         */
        fun schedule(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.enqueueUniqueWork(
                STARTUP_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DeletionPurgeWorker>().build(),
            )
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DeletionPurgeWorker>(24, TimeUnit.HOURS).build(),
            )
        }
    }
}
