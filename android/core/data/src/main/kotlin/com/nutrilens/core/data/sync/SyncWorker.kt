package com.nutrilens.core.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Runs a sync pass in the background.
 *
 * WorkManager rather than a bare coroutine because the work has to survive the
 * app being killed and the device rebooting -- a meal logged on a train must
 * still upload hours later without the user reopening anything.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val syncEngine: SyncEngine,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result = try {
        val outcome = syncEngine.sync()
        when {
            // Nothing was wrong; the device simply had no network when the
            // constraint was satisfied. Let WorkManager reschedule normally.
            outcome.stoppedBecauseOffline -> Result.retry()

            // Transient failures: ask WorkManager to back off and try again.
            // The engine has already recorded per-record backoff, so this is
            // the outer of two independent retry mechanisms.
            outcome.failed > 0 -> Result.retry()

            else -> Result.success()
        }
    } catch (e: Exception) {
        // A crash here must not lose the queue. Records keep their state, and
        // the next run picks them up.
        if (runAttemptCount < MAX_WORKER_ATTEMPTS) Result.retry() else Result.failure()
    }

    companion object {
        const val UNIQUE_PERIODIC_NAME = "nutrilens-periodic-sync"
        const val UNIQUE_ONE_SHOT_NAME = "nutrilens-immediate-sync"
        private const val MAX_WORKER_ATTEMPTS = 5

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * Schedule the recurring background sync.
         *
         * KEEP, not REPLACE: replacing on every app start would reset the
         * interval and mean a frequently-opened app never actually runs it.
         */
        fun enqueuePeriodic(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(
                repeatInterval = PERIODIC_INTERVAL_MINUTES,
                repeatIntervalTimeUnit = TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_DELAY_SECONDS,
                    TimeUnit.SECONDS,
                )
                .build()

            workManager.enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Ask for a sync now -- after logging a meal, or on pull-to-refresh.
         *
         * APPEND_OR_REPLACE so several rapid triggers collapse into one run
         * rather than queueing a pass per tap.
         */
        fun enqueueImmediate(workManager: WorkManager) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_DELAY_SECONDS,
                    TimeUnit.SECONDS,
                )
                .build()

            workManager.enqueueUniqueWork(
                UNIQUE_ONE_SHOT_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }

        private const val PERIODIC_INTERVAL_MINUTES = 30L
        private const val BACKOFF_DELAY_SECONDS = 30L
    }
}
