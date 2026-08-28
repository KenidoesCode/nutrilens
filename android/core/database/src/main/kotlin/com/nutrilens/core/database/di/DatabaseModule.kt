package com.nutrilens.core.database.di

import android.content.Context
import androidx.room.Room
import com.nutrilens.core.database.NutriLensDatabase
import com.nutrilens.core.database.dao.FoodCatalogDao
import com.nutrilens.core.database.dao.MealDao
import com.nutrilens.core.database.dao.SyncOperationDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NutriLensDatabase =
        Room.databaseBuilder(context, NutriLensDatabase::class.java, NutriLensDatabase.NAME)
            // Foreign keys are declared on the entities; SQLite ignores them
            // unless they are switched on for the connection.
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    @Provides
    fun provideMealDao(database: NutriLensDatabase): MealDao = database.mealDao()

    @Provides
    fun provideFoodCatalogDao(database: NutriLensDatabase): FoodCatalogDao =
        database.foodCatalogDao()

    @Provides
    fun provideSyncOperationDao(database: NutriLensDatabase): SyncOperationDao =
        database.syncOperationDao()
}
