package com.nutrilens.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MealItemCreateDto(
    @SerialName("display_name") val displayName: String,
    val category: String,
    @SerialName("estimated_volume_ml") val estimatedVolumeMl: Double,
    @SerialName("recognition_confidence") val recognitionConfidence: Float,
    @SerialName("portion_confidence") val portionConfidence: Float,
    @SerialName("portion_method") val portionMethod: String = "reference-object",
    @SerialName("food_key") val foodKey: String? = null,
    @SerialName("was_user_corrected") val wasUserCorrected: Boolean = false,
    @SerialName("original_display_name") val originalDisplayName: String? = null,
)

@Serializable
data class MealCreateDto(
    @SerialName("consumed_at") val consumedAt: String,
    val timezone: String,
    @SerialName("meal_type") val mealType: String,
    val items: List<MealItemCreateDto>,
    val notes: String? = null,
    @SerialName("idempotency_key") val idempotencyKey: String? = null,
    @SerialName("client_recorded_at") val clientRecordedAt: String? = null,
    @SerialName("prediction_id") val predictionId: String? = null,
)

@Serializable
data class MealItemDto(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("food_key") val foodKey: String? = null,
    val category: String,
    @SerialName("estimated_volume_ml") val estimatedVolumeMl: Double,
    @SerialName("estimated_mass_g") val estimatedMassGrams: Double,
    @SerialName("density_g_per_ml") val densityGramsPerMl: Double,
    @SerialName("density_source") val densitySource: String,
    @SerialName("recognition_confidence") val recognitionConfidence: Float,
    @SerialName("portion_confidence") val portionConfidence: Float,
    @SerialName("energy_kcal") val energyKcal: Double? = null,
    @SerialName("protein_g") val proteinGrams: Double? = null,
    @SerialName("carbohydrate_g") val carbohydrateGrams: Double? = null,
    @SerialName("fat_g") val fatGrams: Double? = null,
    @SerialName("was_user_corrected") val wasUserCorrected: Boolean = false,
    @SerialName("original_mass_g") val originalMassGrams: Double? = null,
    @SerialName("original_display_name") val originalDisplayName: String? = null,
)

@Serializable
data class MealImageDto(
    val id: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("byte_size") val byteSize: Int,
    @SerialName("width_px") val widthPx: Int? = null,
    @SerialName("height_px") val heightPx: Int? = null,
)

@Serializable
data class MealDto(
    val id: String,
    @SerialName("consumed_at") val consumedAt: String,
    val timezone: String,
    @SerialName("meal_type") val mealType: String,
    val notes: String? = null,
    @SerialName("total_mass_g") val totalMassGrams: Double? = null,
    @SerialName("total_energy_kcal") val totalEnergyKcal: Double? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val items: List<MealItemDto> = emptyList(),
    val images: List<MealImageDto> = emptyList(),
)

@Serializable
data class PageMetaDto(
    val total: Int,
    val limit: Int,
    val offset: Int,
    @SerialName("has_more") val hasMore: Boolean,
)

@Serializable
data class MealPageDto(
    val items: List<MealDto>,
    val meta: PageMetaDto,
)

@Serializable
data class PortionCorrectionDto(
    @SerialName("corrected_volume_ml") val correctedVolumeMl: Double,
)

@Serializable
data class RenameItemDto(
    @SerialName("display_name") val displayName: String,
)
