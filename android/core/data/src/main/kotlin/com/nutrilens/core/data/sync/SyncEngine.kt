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
import com.nutrilens.core.model.repository.FoodCatalogRepository
import com.nutrilens.core.model.sync.RetryPolicy
import com.nutrilens.core.network.ApiErrorMapper
import com.nutrilens.core.network.api.NutriLensApi
import com.nutrilens.core.network.dto.MealDto
import com.nutrilens.core.network.dto.PortionCorrectionDto
import com.nutrilens.core.network.dto.RenameItemDto
import com.nutrilens.core.network.dto.SyncPushOperationDto
import com.nutrilens.core.network.dto.SyncPushRequestDto
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
    val edits: Int = 0,
    val stoppedBecauseOffline: Boolean = false,
) {
    val didAnything: Boolean get() = uploaded + deleted + pulled + edits > 0
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
    private val foodCatalog: FoodCatalogRepository,
    private val operations: PendingOperationQueue,
    private val retryPolicy: RetryPolicy,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun sync(): SyncOutcome = withContext(ioDispatcher) {
        if (!connectivity.isCurrentlyOnline()) {
            return@withContext SyncOutcome(0, 0, 0, 0, 0, stoppedBecauseOffline = true)
        }

        // Edits first. An edit belongs to a meal the server already has, and
        // applying it before pulling avoids a pull overwriting it with the
        // pre-edit copy.
        val edits = pushEdits()
        val pushed = push()
        val pulled = pull()

        // The food picker has to work offline, so the catalog is refreshed as
        // part of every sync pass rather than fetched when a screen asks for
        // it. Its failure does not affect the sync outcome: a stale catalog is
        // a degraded picker, not a lost meal.
        refreshFoodCatalog()

        if (pushed.failed == 0 && pushed.exhausted == 0) {
            checkpoints.setLastSyncedAt(timeProvider.now().toEpochMilli())
        }

        pushed.copy(pulled = pulled, edits = edits)
    }

    /**
     * Upload every eligible local change, in one request.
     *
     * The batch endpoint applies each operation independently and reports them
     * individually, so a single malformed meal fails alone while the rest
     * apply. Batching matters most in exactly the situation this feature
     * exists for: a device that has been offline for a day reconnects with a
     * dozen meals queued and makes one round trip instead of a dozen.
     */
    private suspend fun push(): SyncOutcome {
        val now = timeProvider.now().toEpochMilli()
        val batch = mealDao.getUploadable(now, UPLOAD_BATCH_SIZE)
        if (batch.isEmpty()) return SyncOutcome(0, 0, 0, 0, 0)

        // A meal deleted before it ever reached the server has nothing to
        // delete remotely, so it never enters the batch.
        val (locallyOnly, uploadable) = batch.partition {
            it.meal.isDeleted && it.meal.remoteId == null
        }
        locallyOnly.forEach { mealDao.purgeDeleted(it.meal.id) }

        if (uploadable.isEmpty()) {
            return SyncOutcome(0, locallyOnly.size, 0, 0, 0)
        }

        uploadable.forEach { markSyncing(it) }

        val operations = uploadable.map { row ->
            if (row.meal.isDeleted) {
                SyncPushOperationDto(
                    idempotencyKey = row.meal.idempotencyKey,
                    operation = OPERATION_DELETE,
                    mealId = row.meal.remoteId,
                )
            } else {
                SyncPushOperationDto(
                    idempotencyKey = row.meal.idempotencyKey,
                    operation = OPERATION_CREATE,
                    meal = row.toDomain().toCreateDto(row.meal.idempotencyKey),
                )
            }
        }

        val response = errorMapper.execute { api.pushSync(SyncPushRequestDto(operations)) }
        if (response is Outcome.Failure) {
            // The batch never reached the server. Every row is recorded as one
            // failed attempt, so backoff applies to the batch as a whole rather
            // than each meal burning its budget on the same outage.
            var exhausted = 0
            uploadable.forEach { if (recordFailure(it, response.error)) exhausted++ }
            return SyncOutcome(0, locallyOnly.size, 0, uploadable.size - exhausted, exhausted)
        }

        val results = (response as Outcome.Success).data.results.associateBy { it.idempotencyKey }

        var uploaded = 0
        var deleted = locallyOnly.size
        var failed = 0
        var exhausted = 0

        for (row in uploadable) {
            val result = results[row.meal.idempotencyKey]
            when {
                result == null -> {
                    // The server did not report on this operation at all, which
                    // is a contract violation; treat it as retryable rather
                    // than assuming either outcome.
                    if (recordFailure(row, AppError.ServerError(MISSING_RESULT))) {
                        exhausted++
                    } else {
                        failed++
                    }
                }

                result.status == STATUS_FAILED -> {
                    if (recordFailure(row, mapOperationError(result.errorCode))) {
                        exhausted++
                    } else {
                        failed++
                    }
                }

                row.meal.isDeleted -> {
                    mealDao.purgeDeleted(row.meal.id)
                    deleted++
                }

                else -> {
                    val meal = result.meal
                    if (meal == null) {
                        // Accepted, but without the meal there are no server
                        // item ids, so a later correction could not be sent.
                        // Mark it synced and let the next pull supply them.
                        mealDao.markSynced(
                            mealId = row.meal.id,
                            remoteId = result.entityId.orEmpty(),
                            updatedAtEpochMillis = timeProvider.now().toEpochMilli(),
                        )
                    } else {
                        adoptServerMeal(row, meal)
                    }
                    uploaded++
                }
            }
        }

        return SyncOutcome(uploaded, deleted, 0, failed, exhausted)
    }

    /**
     * Translate a per-operation error code into the domain taxonomy.
     *
     * Unknown codes are treated as server errors, which are retryable: an
     * unrecognised code is more likely a newer server than a permanently
     * invalid meal, and discarding a user's meal on that guess would be worse
     * than one wasted retry.
     */
    private fun mapOperationError(code: String?): AppError = when (code) {
        CODE_VALIDATION_FAILED -> AppError.DeviceError(code)
        CODE_NOT_FOUND -> AppError.NotFound
        CODE_CONFLICT -> AppError.DeviceError(code)
        else -> AppError.ServerError(code)
    }

    /**
     * Apply queued edits through the item endpoints.
     *
     * Re-uploading an edited meal does not work: the server treats the original
     * idempotency key as a replay and returns the meal unchanged, so the edit
     * would be silently discarded. Item-level endpoints addressed by the
     * server's own ids are the only path that actually applies a correction.
     */
    private suspend fun pushEdits(): Int {
        var applied = 0

        for (queued in operations.due()) {
            val result: Outcome<Unit> = when (val operation = queued.operation) {
                is PendingOperation.CorrectPortion -> errorMapper.execute {
                    api.correctPortion(
                        operation.remoteItemId,
                        PortionCorrectionDto(operation.volumeMl),
                    )
                }.map { }

                is PendingOperation.RenameItem -> errorMapper.execute {
                    api.renameItem(operation.remoteItemId, RenameItemDto(operation.displayName))
                }.map { }

                is PendingOperation.RemoveItem ->
                    errorMapper.executeUnit { api.removeItem(operation.remoteItemId) }
            }

            when {
                result is Outcome.Success -> {
                    operations.markSucceeded(queued.idempotencyKey)
                    applied++
                }

                // The item is already gone server-side, so the intent is
                // satisfied. Retrying a deletion that can never succeed would
                // block every operation queued behind it.
                result is Outcome.Failure && result.error == AppError.NotFound -> {
                    operations.markSucceeded(queued.idempotencyKey)
                    applied++
                }

                result is Outcome.Failure -> operations.markFailed(
                    idempotencyKey = queued.idempotencyKey,
                    attempts = queued.attempts,
                    retryable = result.error.isRetryable,
                    error = describe(result.error),
                )
            }
        }

        return applied
    }

    /**
     * Replace a local meal with what the server stored.
     *
     * The server recomputes mass from its own density table and assigns its own
     * item ids. Adopting both matters: without the ids a later correction
     * cannot be addressed to an item at all, and without the masses the local
     * copy silently drifts from what every other device sees.
     *
     * The local meal id and image are kept. The id is what every local
     * reference uses, and the client generated it precisely so it would never
     * have to change.
     */
    private suspend fun adoptServerMeal(row: MealWithItems, dto: MealDto) {
        val server = dto.toDomain(localImagePath = row.meal.localImagePath)
        val now = timeProvider.now()

        mealDao.upsertMealWithItems(
            server.copy(
                id = row.meal.id,
                remoteId = dto.id,
                syncState = SyncState.SYNCED,
                createdAt = Instant.ofEpochMilli(row.meal.createdAtEpochMillis),
                updatedAt = now,
            ).toEntity(idempotencyKey = row.meal.idempotencyKey),
            server.items.mapIndexed { index, item -> item.toEntity(row.meal.id, index) },
        )
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

    private suspend fun refreshFoodCatalog() {
        foodCatalog.refresh()
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

        const val OPERATION_CREATE = "create_meal"
        const val OPERATION_DELETE = "delete_meal"
        const val STATUS_FAILED = "failed"
        const val MISSING_RESULT = "MISSING_OPERATION_RESULT"

        const val CODE_VALIDATION_FAILED = "VALIDATION_FAILED"
        const val CODE_NOT_FOUND = "NOT_FOUND"
        const val CODE_CONFLICT = "CONFLICT"
    }
}
