package com.nutrilens.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A cached copy of the server's food catalog.
 *
 * Cached so the food-correction picker and density lookups keep working with
 * no network -- correcting a misidentified food is exactly the moment a user is
 * least willing to be told to go online.
 */
@Entity(
    tableName = "food_catalog",
    indices = [Index(value = ["displayName"]), Index(value = ["category"])],
)
data class FoodCatalogEntity(
    @PrimaryKey val foodKey: String,
    val displayName: String,
    val displayNameTelugu: String? = null,
    val category: String,
    val densityGramsPerMl: Double,
    val energyKcalPer100g: Double? = null,
    val proteinGramsPer100g: Double? = null,
    val carbohydrateGramsPer100g: Double? = null,
    val fatGramsPer100g: Double? = null,
    val cachedAtEpochMillis: Long,
)
