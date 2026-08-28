package com.nutrilens.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.nutrilens.core.database.dao.FoodCatalogDao
import com.nutrilens.core.database.dao.MealDao
import com.nutrilens.core.database.dao.SyncOperationDao
import com.nutrilens.core.database.entity.FoodCatalogEntity
import com.nutrilens.core.database.entity.MealEntity
import com.nutrilens.core.database.entity.MealItemEntity
import com.nutrilens.core.database.entity.SyncOperationEntity

/**
 * The on-device database.
 *
 * `exportSchema = true` writes the schema JSON into `schemas/`, which is
 * committed. That file is what makes a future migration reviewable and what
 * Room's migration tests run against; without it, a schema change can only be
 * verified by hand.
 *
 * No destructive-migration fallback is configured anywhere: wiping a user's
 * meal history to avoid writing a migration is not an acceptable trade.
 */
@Database(
    entities = [
        MealEntity::class,
        MealItemEntity::class,
        FoodCatalogEntity::class,
        SyncOperationEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class NutriLensDatabase : RoomDatabase() {

    abstract fun mealDao(): MealDao

    abstract fun foodCatalogDao(): FoodCatalogDao

    abstract fun syncOperationDao(): SyncOperationDao

    companion object {
        const val NAME = "nutrilens.db"
    }
}
