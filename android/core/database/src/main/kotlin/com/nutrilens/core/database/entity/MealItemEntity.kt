package com.nutrilens.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One food within a meal.
 *
 * Cascade delete keeps items from outliving their meal. The `original*` columns
 * preserve the model's first answer so a user correction stays auditable.
 */
@Entity(
    tableName = "meal_items",
    foreignKeys = [
        ForeignKey(
            entity = MealEntity::class,
            parentColumns = ["id"],
            childColumns = ["mealId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["mealId"]), Index(value = ["foodKey"])],
)
data class MealItemEntity(
    @PrimaryKey val id: String,
    val mealId: String,
    val displayName: String,
    val foodKey: String? = null,
    val category: String,

    val estimatedVolumeMl: Double,
    val estimatedMassGrams: Double,
    val densityGramsPerMl: Double,
    val densitySource: String,
    val isFallbackDensity: Boolean = false,

    val recognitionConfidence: Float,
    val portionConfidence: Float,
    val portionMethod: String,

    val energyKcal: Double? = null,
    val proteinGrams: Double? = null,
    val carbohydrateGrams: Double? = null,
    val fatGrams: Double? = null,

    val wasUserCorrected: Boolean = false,
    val originalMassGrams: Double? = null,
    val originalDisplayName: String? = null,

    /** Preserves the order the analysis returned, which is confidence order. */
    val position: Int = 0,
)
