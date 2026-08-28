package com.nutrilens.core.data.repository

import androidx.work.WorkManager
import com.nutrilens.core.data.sync.SyncWorker
import com.nutrilens.core.database.dao.MealDao
import com.nutrilens.core.datastore.UserPreferencesStore
import com.nutrilens.core.model.SyncState
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
 * A count and a timestamp, not a spinner: the user's real question is "is my
 * data safe", and "3 meals waiting to upload" answers it honestly while
 * "syncing..." does not.
 */
@Singleton
class DefaultSyncRepository @Inject constructor(
    private val mealDao: MealDao,
    private val preferences: UserPreferencesStore,
    private val workManager: WorkManager,
) : SyncRepository {

    override fun observeSyncState(): Flow<SyncStatus> = combine(
        mealDao.observeOutstandingCount(),
        preferences.lastSyncedAtEpochMillis,
    ) { pending, lastSyncedAt ->
        SyncStatus(
            pendingCount = pending,
            state = if (pending == 0) SyncState.SYNCED else SyncState.PENDING,
            lastSyncedAt = lastSyncedAt?.let(Instant::ofEpochMilli),
        )
    }

    override suspend fun requestSync() {
        SyncWorker.enqueueImmediate(workManager)
    }
}
