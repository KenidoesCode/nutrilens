package com.nutrilens.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nutrilens.core.database.entity.SyncOperationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncOperationDao {

    /**
     * Enqueue an operation, ignoring one already queued under the same key.
     *
     * IGNORE rather than REPLACE: re-enqueueing must not reset an existing
     * row's attempt count and backoff, which would defeat the retry limit.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(operation: SyncOperationEntity): Long

    @Query(
        """
        SELECT * FROM sync_queue
        WHERE state IN ('PENDING', 'RETRYING', 'FAILED')
          AND (nextAttemptAtEpochMillis IS NULL OR nextAttemptAtEpochMillis <= :nowEpochMillis)
        ORDER BY createdAtEpochMillis ASC
        LIMIT :limit
        """,
    )
    suspend fun getDue(nowEpochMillis: Long, limit: Int): List<SyncOperationEntity>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE state != 'SYNCED'")
    fun observeOutstandingCount(): Flow<Int>

    @Query(
        """
        UPDATE sync_queue
        SET state = :state,
            attempts = :attempts,
            lastError = :error,
            nextAttemptAtEpochMillis = :nextAttemptAtEpochMillis
        WHERE idempotencyKey = :idempotencyKey
        """,
    )
    suspend fun updateState(
        idempotencyKey: String,
        state: String,
        attempts: Int,
        error: String?,
        nextAttemptAtEpochMillis: Long?,
    )

    @Query("DELETE FROM sync_queue WHERE idempotencyKey = :idempotencyKey")
    suspend fun remove(idempotencyKey: String)

    @Query("DELETE FROM sync_queue")
    suspend fun clear()
}
