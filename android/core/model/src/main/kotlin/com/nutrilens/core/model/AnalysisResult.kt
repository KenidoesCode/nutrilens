package com.nutrilens.core.model

/**
 * The output of one meal-image analysis.
 *
 * [engine] and [modelVersion] travel with the result so a stored meal can
 * always be traced back to what produced it -- essential once the model is
 * replaced and older records must still be interpretable.
 */
data class AnalysisResult(
    val predictionId: String,
    val items: List<AnalyzedItem>,
    val engine: String,
    val modelVersion: String,
    val processingMillis: Int,
    val warnings: List<String> = emptyList(),
) {
    val hasItems: Boolean get() = items.isNotEmpty()

    val detectedNothing: Boolean
        get() = items.isEmpty() && warnings.contains(WARNING_NO_FOOD)

    companion object {
        const val WARNING_NO_FOOD = "no_food_detected"
    }
}

/** One detected food, before the user has confirmed or corrected anything. */
data class AnalyzedItem(
    val name: String,
    val foodKey: String? = null,
    val category: FoodCategory,
    val confidence: Float,
    val estimatedVolumeMl: Double,
    val estimatedMassGrams: Double,
    val portionConfidence: Float,
    val portionMethod: String,
    val overallConfidence: Float,
    val densityGramsPerMl: Double,
    val densitySource: String,
    val isFallbackDensity: Boolean,
    val energyKcal: Double? = null,
    val proteinGrams: Double? = null,
    val carbohydrateGrams: Double? = null,
    val fatGrams: Double? = null,
) {
    val confidenceBand: ConfidenceBand get() = ConfidenceBand.fromScore(confidence)

    /** Convert an accepted detection into a meal item ready to be stored. */
    fun toMealItem(id: String): MealItem = MealItem(
        id = id,
        displayName = name,
        foodKey = foodKey,
        category = category,
        estimatedVolumeMl = estimatedVolumeMl,
        estimatedMassGrams = estimatedMassGrams,
        densityGramsPerMl = densityGramsPerMl,
        densitySource = densitySource,
        isFallbackDensity = isFallbackDensity,
        recognitionConfidence = confidence,
        portionConfidence = portionConfidence,
        portionMethod = portionMethod,
        energyKcal = energyKcal,
        proteinGrams = proteinGrams,
        carbohydrateGrams = carbohydrateGrams,
        fatGrams = fatGrams,
    )
}
