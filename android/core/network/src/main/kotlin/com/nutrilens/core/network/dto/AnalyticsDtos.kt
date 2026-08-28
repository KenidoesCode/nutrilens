package com.nutrilens.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DailyPatternDto(
    val day: String,
    val timezone: String,
    @SerialName("meal_count") val mealCount: Int,
    @SerialName("first_meal_local") val firstMealLocal: String? = null,
    @SerialName("last_meal_local") val lastMealLocal: String? = null,
    @SerialName("eating_window_minutes") val eatingWindowMinutes: Int? = null,
    @SerialName("eating_window_hours") val eatingWindowHours: Double? = null,
    @SerialName("fasting_minutes") val fastingMinutes: Int? = null,
    @SerialName("fasting_hours") val fastingHours: Double? = null,
)

@Serializable
data class RangePatternDto(
    @SerialName("start_day") val startDay: String,
    @SerialName("end_day") val endDay: String,
    val timezone: String,
    val days: List<DailyPatternDto>,
    @SerialName("days_with_meals") val daysWithMeals: Int,
    @SerialName("total_meals") val totalMeals: Int,
    @SerialName("mean_eating_window_minutes") val meanEatingWindowMinutes: Double? = null,
    @SerialName("median_eating_window_minutes") val medianEatingWindowMinutes: Double? = null,
    @SerialName("mean_meals_per_active_day") val meanMealsPerActiveDay: Double? = null,
    @SerialName("eating_window_consistency") val eatingWindowConsistency: Float? = null,
)

@Serializable
data class NutritionTotalsDto(
    @SerialName("start_day") val startDay: String,
    @SerialName("end_day") val endDay: String,
    @SerialName("energy_kcal") val energyKcal: Double,
    @SerialName("protein_g") val proteinGrams: Double,
    @SerialName("carbohydrate_g") val carbohydrateGrams: Double,
    @SerialName("fat_g") val fatGrams: Double,
    @SerialName("mass_g") val massGrams: Double,
)

@Serializable
data class FoodDensityDto(
    @SerialName("density_g_per_ml") val densityGramsPerMl: Double,
    val source: String,
    @SerialName("source_version") val sourceVersion: String,
    val confidence: Float,
)

@Serializable
data class FoodDto(
    val id: String,
    @SerialName("food_key") val foodKey: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("display_name_te") val displayNameTelugu: String? = null,
    val category: String,
    @SerialName("energy_kcal_per_100g") val energyKcalPer100g: Double? = null,
    @SerialName("protein_g_per_100g") val proteinGramsPer100g: Double? = null,
    @SerialName("carbohydrate_g_per_100g") val carbohydrateGramsPer100g: Double? = null,
    @SerialName("fat_g_per_100g") val fatGramsPer100g: Double? = null,
    val densities: List<FoodDensityDto> = emptyList(),
)
