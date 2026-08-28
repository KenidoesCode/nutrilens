package com.nutrilens.core.data.repository

import androidx.work.WorkManager
import com.nutrilens.core.common.di.IoDispatcher
import com.nutrilens.core.common.time.TimeProvider
import com.nutrilens.core.data.mapper.toDomain
import com.nutrilens.core.data.mapper.toEntity
import com.nutrilens.core.data.sync.PendingOperation
import com.nutrilens.core.data.sync.PendingOperationQueue
import com.nutrilens.core.data.sync.SyncEngine
import com.nutrilens.core.data.sync.SyncWorker
import com.nutrilens.core.database.dao.FoodCatalogDao
import com.nutrilens.core.database.dao.MealDao
import com.nutrilens.core.database.entity.MealWithItems
import com.nutrilens.core.model.AppError
import com.nutrilens.core.model.Meal
import com.nutrilens.core.model.MealItem
import com.nutrilens.core.model.Outcome
import com.nutrilens.core.model.SyncState
import com.nutrilens.core.model.repository.MealRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The meal repository. Local database is the single source of truth.
 *
 * Every read observes Room, so the UI renders from disk and never waits on the
 * network. Every write commits locally and then *asks* for a sync; the write
 * has already succeeded by the time the request is made, so a missing network
 * can delay a meal reaching the server but can never lose it.
 */
@Singleton
class OfflineFirstMealRepository @Inject constructor(
    private val mealDao: MealDao,
    private val foodCatalogDao: FoodCatalogDao,
    private val operations: PendingOperationQueue,
    private val syncEngine: SyncEngine,
    private val workManager: WorkManager,
    private val timeProvider: TimeProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : MealRepository {

    override fun observeMeals(): Flow<List<Meal>> =
        mealDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeMeal(mealId: String): Flow<Meal?> =
        mealDao.observeById(mealId).map { it?.toDomain() }

    override suspend fun logMeal(meal: Meal): Outcome<Meal> = withContext(ioDispatcher) {
        if (meal.items.isEmpty()) {
            return@withContext Outcome.failure(
                AppError.DeviceError("A meal must contain at least one food."),
            )
        }

        val now = timeProvider.now()
        val stored = meal.copy(
            syncState = SyncState.PENDING,
            createdAt = now,
            updatedAt = now,
        )

        try {
            mealDao.upsertMealWithItems(
                // The key is generated once, here, and reused for every retry.
                // Regenerating it per attempt would defeat server-side
                // deduplication and create a meal per retry.
                stored.toEntity(idempotencyKey = UUID.randomUUID().toString()),
                stored.items.mapIndexed { index, item -> item.toEntity(stored.id, index) },
            )
        } catch (e: Exception) {
            return@withContext Outcome.failure(AppError.DeviceError(e.message))
        }

        requestSync()
        Outcome.success(stored)
    }

    override suspend fun updateItemPortion(
        mealId: String,
        itemId: String,
        correctedVolumeMl: Double,
    ): Outcome<Meal> = mutateItem(
        mealId = mealId,
        itemId = itemId,
        remoteOperation = { remoteMealId, _ ->
            PendingOperation.CorrectPortion(remoteMealId, itemId, correctedVolumeMl)
        },
    ) { item ->
        item.withCorrectedVolume(correctedVolumeMl)
    }

    /**
     * Correct a misidentified food.
     *
     * Changing *what* the food is changes its density, so the mass must be
     * recomputed rather than carried over from the wrong food. The density
     * comes from the locally cached catalog, so a correction made with no
     * network still produces a correct mass immediately. When the food is not
     * in the cache the item is marked as resting on a fallback density rather
     * than silently keeping the previous food's figure.
     */
    override suspend fun renameItem(
        mealId: String,
        itemId: String,
        displayName: String,
    ): Outcome<Meal> {
        val trimmed = displayName.trim()
        if (trimmed.isEmpty()) {
            return Outcome.failure(AppError.DeviceError("The food name must not be empty."))
        }

        val catalogEntry = withContext(ioDispatcher) {
            foodCatalogDao.getByKey(trimmed.lowercase().replace(' ', '_'))
                ?: foodCatalogDao.search(trimmed, limit = 1).first().firstOrNull()
        }

        return mutateItem(
            mealId = mealId,
            itemId = itemId,
            remoteOperation = { remoteMealId, _ ->
                PendingOperation.RenameItem(remoteMealId, itemId, trimmed)
            },
        ) { item ->
            val density = catalogEntry?.densityGramsPerMl
            val mass = density?.let { item.estimatedVolumeMl * it }
            val energyPer100g = catalogEntry?.energyKcalPer100g

            item.copy(
                displayName = catalogEntry?.displayName ?: trimmed,
                foodKey = catalogEntry?.foodKey,
                category = catalogEntry?.let {
                    com.nutrilens.core.model.FoodCategory.fromWireOrDefault(it.category)
                } ?: item.category,
                densityGramsPerMl = density ?: item.densityGramsPerMl,
                densitySource = if (catalogEntry != null) {
                    CATALOG_DENSITY_SOURCE
                } else {
                    item.densitySource
                },
                estimatedMassGrams = mass ?: item.estimatedMassGrams,
                isFallbackDensity = catalogEntry == null,
                energyKcal = if (mass != null && energyPer100g != null) {
                    energyPer100g * mass / 100.0
                } else {
                    // Carrying the previous food's calories over would be a
                    // number about a food the user just told us this is not.
                    null
                },
                proteinGrams = null,
                carbohydrateGrams = null,
                fatGrams = null,
                originalDisplayName = item.originalDisplayName ?: item.displayName,
                originalMassGrams = item.originalMassGrams ?: item.estimatedMassGrams,
                wasUserCorrected = true,
            )
        }
    }

    override suspend fun removeItem(mealId: String, itemId: String): Outcome<Meal> =
        withContext(ioDispatcher) {
            val row = mealDao.getById(mealId)
                ?: return@withContext Outcome.failure(AppError.NotFound)
            val existing = row.toDomain()

            val remaining = existing.items.filterNot { it.id == itemId }
            if (remaining.size == existing.items.size) {
                return@withContext Outcome.failure(AppError.NotFound)
            }
            if (remaining.isEmpty()) {
                // A meal with no foods is not a meal. Deleting it is the honest
                // outcome, rather than leaving an empty record in the timeline.
                return@withContext deleteMeal(mealId).map { existing }
            }

            row.meal.remoteId?.let { remoteMealId ->
                operations.enqueue(PendingOperation.RemoveItem(remoteMealId, itemId))
            }

            persist(row, existing.copy(items = remaining))
        }

    override suspend fun deleteMeal(mealId: String): Outcome<Unit> = withContext(ioDispatcher) {
        val row = mealDao.getById(mealId)
            ?: return@withContext Outcome.failure(AppError.NotFound)

        mealDao.softDelete(mealId, timeProvider.now().toEpochMilli())
        // A meal that never reached the server has nothing to delete remotely.
        if (row.meal.remoteId == null) {
            mealDao.purgeDeleted(mealId)
        } else {
            requestSync()
        }
        Outcome.success(Unit)
    }

    override suspend fun refresh(): Outcome<Unit> = withContext(ioDispatcher) {
        val outcome = syncEngine.sync()
        when {
            outcome.stoppedBecauseOffline -> Outcome.failure(AppError.Offline)
            outcome.hasFailures -> Outcome.failure(AppError.ServerError("SYNC_INCOMPLETE"))
            else -> Outcome.success(Unit)
        }
    }

    private suspend fun mutateItem(
        mealId: String,
        itemId: String,
        remoteOperation: (remoteMealId: String, item: MealItem) -> PendingOperation?,
        transform: (MealItem) -> MealItem,
    ): Outcome<Meal> = withContext(ioDispatcher) {
        val row = mealDao.getById(mealId)
            ?: return@withContext Outcome.failure(AppError.NotFound)
        val existing = row.toDomain()

        val target = existing.items.firstOrNull { it.id == itemId }
            ?: return@withContext Outcome.failure(AppError.NotFound)

        val updated = try {
            existing.copy(
                items = existing.items.map { if (it.id == itemId) transform(it) else it },
            )
        } catch (e: IllegalArgumentException) {
            return@withContext Outcome.failure(AppError.DeviceError(e.message))
        }

        row.meal.remoteId?.let { remoteMealId ->
            remoteOperation(remoteMealId, target)?.let { operations.enqueue(it) }
        }

        persist(row, updated)
    }

    /**
     * Write an edited meal back.
     *
     * How the edit reaches the server depends on whether the server has the
     * meal yet:
     *
     * - **Not yet uploaded.** The row stays `PENDING` and the whole meal is
     *   uploaded once, already carrying the edit.
     * - **Already uploaded.** The edit was queued as an item operation by the
     *   caller and the row stays `SYNCED`. Re-uploading would be worse than
     *   useless: the server treats the original idempotency key as a replay and
     *   returns the meal unchanged, so the edit would be silently discarded.
     */
    private suspend fun persist(row: MealWithItems, meal: Meal): Outcome<Meal> {
        val alreadyOnServer = row.meal.remoteId != null
        val updated = meal.copy(
            updatedAt = timeProvider.now(),
            syncState = if (alreadyOnServer) SyncState.SYNCED else SyncState.PENDING,
            remoteId = row.meal.remoteId,
        )

        return try {
            mealDao.upsertMealWithItems(
                updated.toEntity(idempotencyKey = row.meal.idempotencyKey),
                updated.items.mapIndexed { index, item -> item.toEntity(updated.id, index) },
            )
            requestSync()
            Outcome.success(updated)
        } catch (e: Exception) {
            Outcome.failure(AppError.DeviceError(e.message))
        }
    }

    private fun requestSync() {
        SyncWorker.enqueueImmediate(workManager)
    }

    private companion object {
        const val CATALOG_DENSITY_SOURCE = "local-food-catalog-cache"
    }
}
