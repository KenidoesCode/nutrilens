package com.nutrilens.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.nutrilens.core.database.entity.MealEntity
import com.nutrilens.core.database.entity.MealItemEntity
import com.nutrilens.core.database.entity.MealWithItems
import kotlinx.coroutines.flow.Flow

@Dao
interface MealDao {

    @Transaction
    @Query(
        """
        SELECT * FROM meals
        WHERE isDeleted = 0
        ORDER BY consumedAtEpochMillis DESC, id DESC
        """,
    )
    fun observeAll(): Flow<List<MealWithItems>>

    @Transaction
    @Query(
        """
        SELECT * FROM meals
        WHERE isDeleted = 0
          AND consumedAtEpochMillis >= :startEpochMillis
          AND consumedAtEpochMillis < :endEpochMillis
        ORDER BY consumedAtEpochMillis ASC
        """,
    )
    fun observeBetween(startEpochMillis: Long, endEpochMillis: Long): Flow<List<MealWithItems>>

    @Transaction
    @Query("SELECT * FROM meals WHERE id = :mealId AND isDeleted = 0")
    fun observeById(mealId: String): Flow<MealWithItems?>

    @Transaction
    @Query("SELECT * FROM meals WHERE id = :mealId")
    suspend fun getById(mealId: String): MealWithItems?

    @Query("SELECT COUNT(*) FROM meals WHERE syncState != 'SYNCED'")
    fun observeOutstandingCount(): Flow<Int>

    /**
     * Meals that have stopped retrying.
     *
     * Counted separately from the outstanding total because "waiting to
     * upload" and "could not upload" call for different things to be said to
     * the user.
     */
    @Query("SELECT COUNT(*) FROM meals WHERE syncState = 'FAILED'")
    fun observeFailedCount(): Flow<Int>

    /**
     * Meals due for an upload attempt.
     *
     * Rows whose backoff window has not elapsed are excluded here rather than
     * filtered in Kotlin, so a worker cannot accidentally hammer a failing row.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM meals
        WHERE syncState IN ('PENDING', 'RETRYING', 'FAILED')
          AND (nextAttemptAtEpochMillis IS NULL OR nextAttemptAtEpochMillis <= :nowEpochMillis)
        ORDER BY createdAtEpochMillis ASC
        LIMIT :limit
        """,
    )
    suspend fun getUploadable(nowEpochMillis: Long, limit: Int): List<MealWithItems>

    @Upsert
    suspend fun upsertMeal(meal: MealEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<MealItemEntity>)

    @Query("DELETE FROM meal_items WHERE mealId = :mealId")
    suspend fun deleteItemsFor(mealId: String)

    /**
     * Replace a meal and its items atomically.
     *
     * Items are deleted and reinserted rather than diffed: an analysis result
     * is authoritative for the whole meal, and a partial update could leave an
     * item from a superseded analysis behind.
     */
    @Transaction
    suspend fun upsertMealWithItems(meal: MealEntity, items: List<MealItemEntity>) {
        upsertMeal(meal)
        deleteItemsFor(meal.id)
        insertItems(items)
    }

    @Query(
        """
        UPDATE meals
        SET syncState = :state,
            syncAttempts = :attempts,
            lastSyncErrorMessage = :error,
            nextAttemptAtEpochMillis = :nextAttemptAtEpochMillis,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :mealId
        """,
    )
    suspend fun updateSyncState(
        mealId: String,
        state: String,
        attempts: Int,
        error: String?,
        nextAttemptAtEpochMillis: Long?,
        updatedAtEpochMillis: Long,
    )

    @Query(
        """
        UPDATE meals
        SET syncState = 'SYNCED',
            remoteId = :remoteId,
            syncAttempts = 0,
            lastSyncErrorMessage = NULL,
            nextAttemptAtEpochMillis = NULL,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :mealId
        """,
    )
    suspend fun markSynced(mealId: String, remoteId: String, updatedAtEpochMillis: Long)

    /** Soft delete: the server still has to be told, so the row must remain. */
    @Query(
        """
        UPDATE meals
        SET isDeleted = 1,
            syncState = 'PENDING',
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :mealId
        """,
    )
    suspend fun softDelete(mealId: String, updatedAtEpochMillis: Long)

    /** Drop a soft-deleted meal once the server has confirmed the deletion. */
    @Query("DELETE FROM meals WHERE id = :mealId AND isDeleted = 1")
    suspend fun purgeDeleted(mealId: String)

    @Query("DELETE FROM meals")
    suspend fun clear()
}
