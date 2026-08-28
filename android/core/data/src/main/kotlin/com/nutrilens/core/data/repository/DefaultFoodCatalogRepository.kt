package com.nutrilens.core.data.repository

import com.nutrilens.core.common.di.IoDispatcher
import com.nutrilens.core.common.time.TimeProvider
import com.nutrilens.core.data.mapper.toDomain
import com.nutrilens.core.data.mapper.toEntity
import com.nutrilens.core.database.dao.FoodCatalogDao
import com.nutrilens.core.model.FoodCatalogItem
import com.nutrilens.core.model.Outcome
import com.nutrilens.core.model.repository.FoodCatalogRepository
import com.nutrilens.core.network.ApiErrorMapper
import com.nutrilens.core.network.api.NutriLensApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The food catalog, served from a local cache.
 *
 * Search always reads the cache. Correcting a misidentified food is exactly
 * when a user is least willing to be told to go online, so the picker must work
 * offline; [refresh] tops the cache up opportunistically.
 */
@Singleton
class DefaultFoodCatalogRepository @Inject constructor(
    private val api: NutriLensApi,
    private val foodCatalogDao: FoodCatalogDao,
    private val errorMapper: ApiErrorMapper,
    private val timeProvider: TimeProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : FoodCatalogRepository {

    override fun search(query: String): Flow<List<FoodCatalogItem>> =
        foodCatalogDao.search(query.trim())
            .map { entries -> entries.map { it.toDomain() } }

    override suspend fun refresh(): Outcome<Unit> = withContext(ioDispatcher) {
        errorMapper.execute { api.searchFoods() }
            .map { foods ->
                val cachedAt = timeProvider.now().toEpochMilli()
                foodCatalogDao.upsertAll(foods.map { it.toEntity(cachedAt) })
            }
    }

    /** Whether the cache has ever been populated, so callers can prime it. */
    suspend fun isEmpty(): Boolean = withContext(ioDispatcher) { foodCatalogDao.count() == 0 }
}
