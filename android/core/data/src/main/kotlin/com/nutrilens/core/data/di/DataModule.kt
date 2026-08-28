package com.nutrilens.core.data.di

import android.content.Context
import androidx.work.WorkManager
import com.nutrilens.core.data.repository.DefaultAnalysisRepository
import com.nutrilens.core.data.repository.DefaultAnalyticsRepository
import com.nutrilens.core.data.repository.DefaultAuthRepository
import com.nutrilens.core.data.repository.DefaultFoodCatalogRepository
import com.nutrilens.core.data.repository.DefaultSettingsRepository
import com.nutrilens.core.data.repository.DefaultSyncRepository
import com.nutrilens.core.data.repository.OfflineFirstMealRepository
import com.nutrilens.core.model.sync.RetryPolicy
import com.nutrilens.core.model.repository.AnalysisRepository
import com.nutrilens.core.model.repository.AnalyticsRepository
import com.nutrilens.core.model.repository.AuthRepository
import com.nutrilens.core.model.repository.FoodCatalogRepository
import com.nutrilens.core.model.repository.MealRepository
import com.nutrilens.core.model.repository.SettingsRepository
import com.nutrilens.core.model.repository.SyncRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds every repository interface to its implementation.
 *
 * Features depend on the interfaces from `core:model`, so this module is the
 * only place that names a concrete implementation.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(implementation: DefaultAuthRepository): AuthRepository

    @Binds
    @Singleton
    abstract fun bindMealRepository(implementation: OfflineFirstMealRepository): MealRepository

    @Binds
    @Singleton
    abstract fun bindAnalysisRepository(
        implementation: DefaultAnalysisRepository,
    ): AnalysisRepository

    @Binds
    @Singleton
    abstract fun bindAnalyticsRepository(
        implementation: DefaultAnalyticsRepository,
    ): AnalyticsRepository

    @Binds
    @Singleton
    abstract fun bindFoodCatalogRepository(
        implementation: DefaultFoodCatalogRepository,
    ): FoodCatalogRepository

    @Binds
    @Singleton
    abstract fun bindSyncRepository(implementation: DefaultSyncRepository): SyncRepository

    @Binds
    @Singleton
    abstract fun bindSyncCheckpointStore(
        implementation: PreferencesSyncCheckpointStore,
    ): SyncCheckpointStore

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(
        implementation: DefaultSettingsRepository,
    ): SettingsRepository

    companion object {

        @Provides
        @Singleton
        fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
            WorkManager.getInstance(context)

        @Provides
        @Singleton
        fun provideRetryPolicy(): RetryPolicy = RetryPolicy()
    }
}
