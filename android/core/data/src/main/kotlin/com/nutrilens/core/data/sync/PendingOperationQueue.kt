package com.nutrilens.core.data.sync

import com.nutrilens.core.common.di.IoDispatcher
import com.nutrilens.core.common.time.TimeProvider
import com.nutrilens.core.database.dao.SyncOperationDao
import com.nutrilens.core.database.entity.SyncOperationEntity
import com.nutrilens.core.model.SyncState
import com.nutrilens.core.model.sync.RetryPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A queued operation, decoded and paired with its bookkeeping. */
data class QueuedOperation(
    val idempotencyKey: String,
    val operation: PendingOperation,
    val attempts: Int,
)

/**
 * Durable queue of edits waiting to reach the server.
 *
 * Backed by the database rather than memory, so an edit made offline survives
 * the app being killed. Each entry carries an idempotency key for the same
 * reason meal creation does: an operation the server already applied but whose
 * response was lost must not be applied twice.
 */
@Singleton
class PendingOperationQueue @Inject constructor(
    private val dao: SyncOperationDao,
    private val timeProvider: TimeProvider,
    private val retryPolicy: RetryPolicy,
    private val json: Json,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun enqueue(operation: PendingOperation) = withContext(ioDispatcher) {
        dao.enqueue(
            SyncOperationEntity(
                idempotencyKey = UUID.randomUUID().toString(),
                operation = operation.operationName(),
                entityType = PendingOperation.ENTITY_TYPE_MEAL_ITEM,
                entityId = operation.remoteMealId,
                payloadJson = json.encodeToString(PendingOperation.serializer(), operation),
                state = SyncState.PENDING.name,
                createdAtEpochMillis = timeProvider.now().toEpochMilli(),
            ),
        )
        Unit
    }

    suspend fun due(limit: Int = BATCH_SIZE): List<QueuedOperation> =
        withContext(ioDispatcher) {
            dao.getDue(timeProvider.now().toEpochMilli(), limit).mapNotNull { row ->
                val payload = row.payloadJson ?: return@mapNotNull null
                val decoded = runCatching {
                    json.decodeFromString(PendingOperation.serializer(), payload)
                }.getOrNull()

                if (decoded == null) {
                    // An entry we cannot decode will never succeed, so it is
                    // dropped rather than retried forever. This can only happen
                    // if a schema change lands mid-flight.
                    dao.remove(row.idempotencyKey)
                    return@mapNotNull null
                }
                QueuedOperation(row.idempotencyKey, decoded, row.attempts)
            }
        }

    suspend fun markSucceeded(idempotencyKey: String) = withContext(ioDispatcher) {
        dao.remove(idempotencyKey)
    }

    /** Record a failure. Returns true when the retry budget is spent. */
    suspend fun markFailed(
        idempotencyKey: String,
        attempts: Int,
        retryable: Boolean,
        error: String,
    ): Boolean = withContext(ioDispatcher) {
        val next = attempts + 1
        val shouldRetry = retryable && retryPolicy.shouldRetry(next)
        dao.updateState(
            idempotencyKey = idempotencyKey,
            state = if (shouldRetry) SyncState.RETRYING.name else SyncState.FAILED.name,
            attempts = next,
            error = error.take(MAX_ERROR_LENGTH),
            nextAttemptAtEpochMillis = if (shouldRetry) {
                retryPolicy.nextAttemptAt(timeProvider.now().toEpochMilli(), next)
            } else {
                null
            },
        )
        !shouldRetry
    }

    private fun PendingOperation.operationName(): String = when (this) {
        is PendingOperation.CorrectPortion -> "correct_portion"
        is PendingOperation.RenameItem -> "rename_item"
        is PendingOperation.RemoveItem -> "remove_item"
    }

    private companion object {
        const val BATCH_SIZE = 50
        const val MAX_ERROR_LENGTH = 200
    }
}
