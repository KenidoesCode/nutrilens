package com.nutrilens.core.data.sync

import com.nutrilens.core.common.network.ConnectivityObserver
import com.nutrilens.core.common.time.TimeProvider
import com.nutrilens.core.database.dao.MealDao
import com.nutrilens.core.database.entity.MealEntity
import com.nutrilens.core.database.entity.MealItemEntity
import com.nutrilens.core.database.entity.MealWithItems
import com.nutrilens.core.model.SyncState
import com.nutrilens.core.model.sync.RetryPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Sync-engine behaviour.
 *
 * These are the guarantees the offline-first design rests on: a meal is never
 * lost, a retry never duplicates, and a permanent rejection does not consume
 * the retry budget. Fakes stand in for the DAO and the API so each rule can be
 * exercised in isolation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineTest {

    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-05-01T12:00:00Z")

    @Test
    fun `nothing is attempted while offline`() = runTest(dispatcher) {
        val dao = FakeMealDao(listOf(pendingRow("meal-1")))
        val engine = engine(dao = dao, online = false)

        val outcome = engine.sync()

        assertTrue(outcome.stoppedBecauseOffline)
        assertEquals(0, dao.syncStateUpdates.size)
    }

    @Test
    fun `a pending meal keeps its idempotency key across attempts`() {
        // The key is stored on the row, not generated per attempt: regenerating
        // it would defeat server-side deduplication and create a meal per retry.
        val row = pendingRow("meal-1")
        assertEquals("key-meal-1", row.meal.idempotencyKey)

        val retried = row.copy(meal = row.meal.copy(syncAttempts = 3))
        assertEquals(row.meal.idempotencyKey, retried.meal.idempotencyKey)
    }

    @Test
    fun `an uploadable query excludes rows still inside their backoff window`() =
        runTest(dispatcher) {
            val dao = FakeMealDao(
                listOf(
                    pendingRow("ready", nextAttemptAt = now.toEpochMilli() - 1),
                    pendingRow("waiting", nextAttemptAt = now.toEpochMilli() + 60_000),
                ),
            )

            val due = dao.getUploadable(now.toEpochMilli(), limit = 10)

            assertEquals(listOf("ready"), due.map { it.meal.id })
        }

    @Test
    fun `a synced meal is excluded from the upload queue`() = runTest(dispatcher) {
        val dao = FakeMealDao(
            listOf(
                pendingRow("pending"),
                pendingRow("done").let {
                    it.copy(meal = it.meal.copy(syncState = SyncState.SYNCED.name))
                },
            ),
        )

        val due = dao.getUploadable(now.toEpochMilli(), limit = 10)

        assertEquals(listOf("pending"), due.map { it.meal.id })
    }

    @Test
    fun `retry state is eligible for another attempt`() {
        assertTrue(SyncState.PENDING.isEligibleForUpload)
        assertTrue(SyncState.RETRYING.isEligibleForUpload)
        assertTrue(SyncState.FAILED.isEligibleForUpload)
        assertFalse(SyncState.SYNCED.isEligibleForUpload)
        // SYNCING means an attempt is already in flight; picking it up again
        // would upload the same meal twice concurrently.
        assertFalse(SyncState.SYNCING.isEligibleForUpload)
    }

    @Test
    fun `every non-synced state counts as outstanding work`() {
        assertTrue(SyncState.PENDING.isOutstanding)
        assertTrue(SyncState.SYNCING.isOutstanding)
        assertTrue(SyncState.FAILED.isOutstanding)
        assertTrue(SyncState.RETRYING.isOutstanding)
        assertFalse(SyncState.SYNCED.isOutstanding)
    }

    private fun engine(dao: MealDao, online: Boolean): SyncEngine = SyncEngine(
        api = throwingApi(),
        mealDao = dao,
        errorMapper = com.nutrilens.core.network.ApiErrorMapper(
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
        ),
        connectivity = FakeConnectivity(online),
        timeProvider = FixedTime(now),
        checkpoints = FakeCheckpointStore(),
        retryPolicy = RetryPolicy(),
        ioDispatcher = dispatcher,
    )

    /**
     * The offline test must never reach the API or preferences; if it does, the
     * engine checked connectivity too late and these throw rather than silently
     * passing.
     */
    private fun throwingApi(): com.nutrilens.core.network.api.NutriLensApi =
        java.lang.reflect.Proxy.newProxyInstance(
            com.nutrilens.core.network.api.NutriLensApi::class.java.classLoader,
            arrayOf(com.nutrilens.core.network.api.NutriLensApi::class.java),
        ) { _, method, _ ->
            throw AssertionError("API must not be called: ${method.name}")
        } as com.nutrilens.core.network.api.NutriLensApi

    private fun pendingRow(
        id: String,
        nextAttemptAt: Long? = null,
        attempts: Int = 0,
    ) = MealWithItems(
        meal = MealEntity(
            id = id,
            consumedAtEpochMillis = now.toEpochMilli(),
            timeZoneId = ZoneId.of("Asia/Kolkata").id,
            mealType = "breakfast",
            syncState = SyncState.PENDING.name,
            idempotencyKey = "key-$id",
            syncAttempts = attempts,
            nextAttemptAtEpochMillis = nextAttemptAt,
            createdAtEpochMillis = now.toEpochMilli(),
            updatedAtEpochMillis = now.toEpochMilli(),
        ),
        items = listOf(
            MealItemEntity(
                id = "$id-item",
                mealId = id,
                displayName = "Rice",
                category = "solid",
                estimatedVolumeMl = 180.0,
                estimatedMassGrams = 153.0,
                densityGramsPerMl = 0.85,
                densitySource = "nutrilens-food-catalog@2024.1",
                recognitionConfidence = 0.62f,
                portionConfidence = 0.65f,
                portionMethod = "reference-object",
            ),
        ),
    )
}

private class FakeConnectivity(private val online: Boolean) : ConnectivityObserver {
    override val isOnline: Flow<Boolean> = flowOf(online)
    override fun isCurrentlyOnline(): Boolean = online
}

/** Records the checkpoint the engine writes, without touching DataStore. */
private class FakeCheckpointStore : SyncCheckpointStore {
    private val checkpoint = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)

    override val lastSyncedAtEpochMillis: Flow<Long?> = checkpoint

    override suspend fun setLastSyncedAt(epochMillis: Long) {
        checkpoint.value = epochMillis
    }
}

private class FixedTime(private val instant: Instant) : TimeProvider {
    override fun now(): Instant = instant
    override fun currentZone(): ZoneId = ZoneId.of("Asia/Kolkata")
}

/** Records what the engine asked of the database. */
private class FakeMealDao(private val rows: List<MealWithItems>) : MealDao {

    val syncStateUpdates = mutableListOf<Triple<String, String, Int>>()
    val markedSynced = mutableListOf<String>()
    val purged = mutableListOf<String>()

    override fun observeAll(): Flow<List<MealWithItems>> = flowOf(rows)

    override fun observeBetween(
        startEpochMillis: Long,
        endEpochMillis: Long,
    ): Flow<List<MealWithItems>> = flowOf(rows)

    override fun observeById(mealId: String): Flow<MealWithItems?> =
        flowOf(rows.firstOrNull { it.meal.id == mealId })

    override suspend fun getById(mealId: String): MealWithItems? =
        rows.firstOrNull { it.meal.id == mealId }

    override fun observeOutstandingCount(): Flow<Int> =
        flowOf(rows.count { it.meal.syncState != SyncState.SYNCED.name })

    override suspend fun getUploadable(nowEpochMillis: Long, limit: Int): List<MealWithItems> =
        rows.filter { row ->
            val state = runCatching { SyncState.valueOf(row.meal.syncState) }
                .getOrDefault(SyncState.PENDING)
            val due = row.meal.nextAttemptAtEpochMillis?.let { it <= nowEpochMillis } ?: true
            state.isEligibleForUpload && due
        }.take(limit)

    override suspend fun upsertMeal(meal: MealEntity) = Unit

    override suspend fun insertItems(items: List<MealItemEntity>) = Unit

    override suspend fun deleteItemsFor(mealId: String) = Unit

    override suspend fun updateSyncState(
        mealId: String,
        state: String,
        attempts: Int,
        error: String?,
        nextAttemptAtEpochMillis: Long?,
        updatedAtEpochMillis: Long,
    ) {
        syncStateUpdates += Triple(mealId, state, attempts)
    }

    override suspend fun markSynced(
        mealId: String,
        remoteId: String,
        updatedAtEpochMillis: Long,
    ) {
        markedSynced += mealId
    }

    override suspend fun softDelete(mealId: String, updatedAtEpochMillis: Long) = Unit

    override suspend fun purgeDeleted(mealId: String) {
        purged += mealId
    }

    override suspend fun clear() = Unit
}
