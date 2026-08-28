package com.nutrilens.core.data.sync

import com.nutrilens.core.common.di.IoDispatcher
import com.nutrilens.core.common.network.ConnectivityObserver
import com.nutrilens.core.common.time.TimeProvider
import com.nutrilens.core.data.mapper.toCreateDto
import com.nutrilens.core.data.mapper.toDomain
import com.nutrilens.core.data.mapper.toEntity
import com.nutrilens.core.database.dao.MealDao
import com.nutrilens.core.database.entity.MealWithItems
import com.nutrilens.core.model.AppError
import com.nutrilens.core.model.Outcome
import com.nutrilens.core.model.SyncState
import com.nutrilens.core.model.sync.RetryPolicy
import com.nutrilens.core.network.ApiErrorMapper
import com.nutrilens.core.network.api.NutriLensApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** What one sync pass achieved, for logging and the status indicator. */
data class SyncOutcome(
    val uploaded: Int,
    val deleted: Int,
    val pulled: Int,
    val failed: Int,
    val exhausted: Int,
    val stoppedBecauseOffline: Boolean = false,
) {
    val didAnything: Boolean get() = uploaded + deleted + pulled > 0
    val hasFailures: Boolean get() = failed > 0 || exhausted > 0
}

/**
 * Reconciles the device with the server.
 *
 * The rules that make this safe to run at any moment, repeatedly:
 *
 * - **Push before pull.** Local work is uploaded first, so a pull cannot
 *   overwrite a meal the user just logged with a stale server copy.
 * - **Idempotency keys.** Every upload carries the key stored with the row, so
 *   a request the server already applied but whose response was lost is
 *   recognised as a replay instead of creating a duplicate.
 * - **Nothing is discarded.** A record that exhausts its retries stays FAILED
 *   and visible; it is never deleted to make the queue tidy.
 * - **Failures are per-record.** One bad meal does not block the queue behind
 *   it, and the batch it was in still uploads.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val api: NutriLensApi,
    private val mealDao: MealDao,
    private val errorMapper: ApiErrorMapper,
    private val connectivity: ConnectivityObserver,
    private val timeProvider: TimeProvider,
    private val checkpoints: SyncCheckpointStore,
    private val retryPolicy: RetryPolicy,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun sync(): SyncOutcome = withContext(ioDispatcher) {
        if (!connectivity.isCurrentlyOnline()) {
            return@withContext SyncOutcome(0, 0, 0, 0, 0, stoppedBecauseOffline = true)
        }

        val pushed = push()
        val pulled = pull()

        if (pushed.failed == 0 && pushed.exhausted == 0) {
            checkpoints.setLastSyncedAt(timeProvider.now().toEpochMilli())
        }

        pushed.copy(pulled = pulled)
    }

    /** Upload every eligible local change. */
    private suspend fun push(): SyncOutcome {
        var uploaded = 0
        var deleted = 0
        var failed = 0
        var exhausted = 0

        val now = timeProvider.now().toEpochMilli()
        val batch = mealDao.getUploadable(now, UPLOAD_BATCH_SIZE)

        for (row in batch) {
            markSyncing(row)
            val result = if (row.meal.isDeleted) {
                uploadDeletion(row)
            } else {
                uploadCreation(row)
            }

            when (result) {
                is Outcome.Success -> if (row.meal.isDeleted) deleted++ else uploaded++
                is Outcome.Failure -> {
                    val terminal = recordFailure(row, result.error)
                    if (terminal) exhausted++ else failed++
                }
            }
        }

        return SyncOutcome(uploaded, deleted, 0, failed, exhausted)
    }

    private suspend fun uploadCreation(row: MealWithItems): Outcome<Unit> {
        val meal = row.toDomain()
        val response = errorMapper.execute {
            api.createMeal(meal.toCreateDto(row.meal.idempotencyKey))
        }
        return response.map { dto ->
            mealDao.markSynced(
                mealId = row.meal.id,
                remoteId = dto.id,
                updatedAtEpochMillis = timeProvider.now().toEpochMilli(),
            )
        }
    }

    private suspend fun uploadDeletion(row: MealWithItems): Outcome<Unit> {
        // A meal deleted before it ever synced has nothing to delete remotely;
        // the local row can simply go.
        val remoteId = row.meal.remoteId
            ?: return Outcome.success(Unit).also { mealDao.purgeDeleted(row.meal.id) }

        val response = errorMapper.executeUnit { api.deleteMeal(remoteId) }
        return when {
            response is Outcome.Success -> {
                mealDao.purgeDeleted(row.meal.id)
                Outcome.success(Unit)
            }
            // Already gone server-side: the intent is satisfied, so treat it as
            // success rather than retrying a deletion that can never succeed.
            response is Outcome.Failure && response.error == AppError.NotFound -> {
                mealDao.purgeDeleted(row.meal.id)
                Outcome.success(Unit)
            }
            else -> response as Outcome.Failure
        }
    }

    private suspend fun markSyncing(row: MealWithItems) {
        mealDao.updateSyncState(
            mealId = row.meal.id,
            state = SyncState.SYNCING.name,
            attempts = row.meal.syncAttempts,
            error = null,
            nextAttemptAtEpochMillis = null,
            updatedAtEpochMillis = timeProvider.now().toEpochMilli(),
        )
    }

    /** Record a failed attempt. Returns true when the retry budget is spent. */
    private suspend fun recordFailure(row: MealWithItems, error: AppError): Boolean {
        val attempts = row.meal.syncAttempts + 1
        val now = timeProvider.now().toEpochMilli()

        // A permanent rejection will fail identically forever; spending eight
        // retries on it only delays telling the user something is wrong.
        val retryable = error.isRetryable && retryPolicy.shouldRetry(attempts)

        mealDao.updateSyncState(
            mealId = row.meal.id,
            state = if (retryable) SyncState.RETRYING.name else SyncState.FAILED.name,
            attempts = attempts,
            error = describe(error),
            nextAttemptAtEpochMillis =
            if (retryable) retryPolicy.nextAttemptAt(now, attempts) else null,
            updatedAtEpochMillis = now,
        )
        return !retryable
    }

    /** Fetch server-side changes and reconcile them locally. */
    private suspend fun pull(): Int {
        val since = checkpoints.lastSyncedAtEpochMillis.first()
        val cursor = since?.let { DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(it)) }

        val response = errorMapper.execute { api.pullSync(since = cursor) }
        val body = response.getOrNull() ?: return 0

        var applied = 0
        for (dto in body.meals) {
            val local = mealDao.getById(dto.id)
            // Never clobber a local edit that has not reached the server yet.
            // The user's unsynced change is newer intent than the server's copy.
            if (local != null && parseState(local.meal.syncState).isOutstanding) continue

            val meal = dto.toDomain(localImagePath = local?.meal?.localImagePath)
            mealDao.upsertMealWithItems(
                meal.toEntity(idempotencyKey = local?.meal?.idempotencyKey ?: dto.id),
                meal.items.mapIndexed { index, item -> item.toEntity(meal.id, index) },
            )
            applied++
        }

        for (deletedId in body.deletedMealIds) {
            val local = mealDao.getById(deletedId) ?: continue
            if (parseState(local.meal.syncState).isOutstanding) continue
            mealDao.softDelete(deletedId, timeProvider.now().toEpochMilli())
            mealDao.purgeDeleted(deletedId)
            applied++
        }

        return applied
    }

    private fun parseState(value: String): SyncState =
        runCatching { SyncState.valueOf(value) }.getOrDefault(SyncState.PENDING)

    /**
     * A short, non-sensitive description for the sync-status row.
     *
     * Deliberately not the server's raw message: it can contain identifiers
     * that do not belong in a persisted local error string.
     */
    private fun describe(error: AppError): String = when (error) {
        AppError.Offline -> "offline"
        AppError.Timeout -> "timeout"
        AppError.SessionExpired -> "session_expired"
        AppError.NotFound -> "not_found"
        is AppError.RateLimited -> "rate_limited"
        is AppError.ServerError -> "server_error:${error.code ?: "unknown"}"
        is AppError.DeviceError -> "device_error"
        else -> error::class.simpleName ?: "unknown"
    }

    private companion object {
        const val UPLOAD_BATCH_SIZE = 25
    }
}
