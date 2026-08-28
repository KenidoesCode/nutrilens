package com.nutrilens.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BoundingBoxDto(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

@Serializable
data class NutritionDto(
    @SerialName("energy_kcal") val energyKcal: Double,
    @SerialName("protein_g") val proteinGrams: Double,
    @SerialName("carbohydrate_g") val carbohydrateGrams: Double,
    @SerialName("fat_g") val fatGrams: Double,
    val source: String,
)

@Serializable
data class AnalysisItemDto(
    val name: String,
    val category: String,
    val confidence: Float,
    @SerialName("confidence_band") val confidenceBand: String,
    @SerialName("estimated_volume_ml") val estimatedVolumeMl: Double,
    @SerialName("estimated_mass_g") val estimatedMassGrams: Double,
    @SerialName("portion_confidence") val portionConfidence: Float,
    @SerialName("portion_method") val portionMethod: String,
    @SerialName("overall_confidence") val overallConfidence: Float,
    @SerialName("density_g_per_ml") val densityGramsPerMl: Double,
    @SerialName("density_source") val densitySource: String,
    @SerialName("is_fallback_density") val isFallbackDensity: Boolean,
    val bbox: BoundingBoxDto,
    val engine: String,
    val nutrition: NutritionDto? = null,
)

@Serializable
data class AnalysisResponseDto(
    @SerialName("prediction_id") val predictionId: String,
    val items: List<AnalysisItemDto>,
    val engine: String,
    @SerialName("model_version") val modelVersion: String,
    @SerialName("processing_ms") val processingMillis: Int,
    @SerialName("total_estimated_mass_g") val totalEstimatedMassGrams: Double,
    val warnings: List<String> = emptyList(),
    @SerialName("estimates_are_approximate") val estimatesAreApproximate: Boolean = true,
)
