package com.nutrilens.core.data.repository

import androidx.work.WorkManager
import com.nutrilens.core.common.network.ConnectivityObserver
import com.nutrilens.core.data.sync.SyncCheckpointStore
import com.nutrilens.core.data.sync.SyncWorker
import com.nutrilens.core.database.dao.MealDao
import com.nutrilens.core.model.repository.SyncRepository
import com.nutrilens.core.model.repository.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The sync status shown to the user.
 *
 * Four separate facts, because they call for four different things to be said:
 * how many meals are waiting, how many have stopped retrying, whether the
 * device is online at all, and when the last full sync succeeded. Collapsing
 * them into one spinner would leave the user unable to tell "uploading" from
 * "cannot upload", which is the difference they actually care about.
 */
@Singleton
class DefaultSyncRepository @Inject constructor(
    private val mealDao: MealDao,
    private val checkpoints: SyncCheckpointStore,
    private val connectivity: ConnectivityObserver,
    private val workManager: WorkManager,
) : SyncRepository {

    override fun observeSyncState(): Flow<SyncStatus> = combine(
        mealDao.observeOutstandingCount(),
        mealDao.observeFailedCount(),
        checkpoints.lastSyncedAtEpochMillis,
        connectivity.isOnline,
    ) { pending, failed, lastSyncedAt, online ->
        SyncStatus(
            pendingCount = pending,
            failedCount = failed,
            lastSyncedAt = lastSyncedAt?.let(Instant::ofEpochMilli),
            isOnline = online,
        )
    }

    override suspend fun requestSync() {
        SyncWorker.enqueueImmediate(workManager)
    }
}
