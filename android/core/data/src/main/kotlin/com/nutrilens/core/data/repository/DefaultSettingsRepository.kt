package com.nutrilens.core.data.repository

import com.nutrilens.core.common.di.IoDispatcher
import com.nutrilens.core.data.image.MealImageStore
import com.nutrilens.core.database.dao.FoodCatalogDao
import com.nutrilens.core.database.dao.MealDao
import com.nutrilens.core.database.dao.SyncOperationDao
import com.nutrilens.core.datastore.UserPreferencesStore
import com.nutrilens.core.model.AppLanguage
import com.nutrilens.core.model.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultSettingsRepository @Inject constructor(
    private val preferences: UserPreferencesStore,
    private val mealDao: MealDao,
    private val foodCatalogDao: FoodCatalogDao,
    private val syncOperationDao: SyncOperationDao,
    private val imageStore: MealImageStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SettingsRepository {

    override val language: Flow<AppLanguage> = preferences.language

    override val storeImagesRemotely: Flow<Boolean> = preferences.storeImagesRemotely

    override suspend fun setLanguage(language: AppLanguage) {
        preferences.setLanguage(language)
    }

    override suspend fun setStoreImagesRemotely(enabled: Boolean) {
        preferences.setStoreImagesRemotely(enabled)
    }

    override suspend fun clearLocalData() = withContext(ioDispatcher) {
        // Photographs go too. Clearing meal rows while leaving the images on
        // disk would keep the most sensitive part of the record.
        mealDao.clear()
        foodCatalogDao.clear()
        syncOperationDao.clear()
        imageStore.clear()
    }
}
