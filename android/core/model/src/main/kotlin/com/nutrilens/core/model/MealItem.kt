package com.nutrilens.core.model

/**
 * One food within a meal.
 *
 * Every quantity here is an estimate and carries its own confidence.
 * [originalMassGrams] and [originalDisplayName] preserve what the model
 * originally said, so a user correction stays visible as a correction and the
 * estimator's accuracy remains measurable after the fact.
 */
data class MealItem(
    val id: String,
    val displayName: String,
    val foodKey: String? = null,
    val category: FoodCategory,
    val estimatedVolumeMl: Double,
    val estimatedMassGrams: Double,
    val densityGramsPerMl: Double,
    val densitySource: String,
    val isFallbackDensity: Boolean = false,
    val recognitionConfidence: Float,
    val portionConfidence: Float,
    val portionMethod: String = "reference-object",
    val energyKcal: Double? = null,
    val proteinGrams: Double? = null,
    val carbohydrateGrams: Double? = null,
    val fatGrams: Double? = null,
    val wasUserCorrected: Boolean = false,
    val originalMassGrams: Double? = null,
    val originalDisplayName: String? = null,
) {
    val recognitionBand: ConfidenceBand
        get() = ConfidenceBand.fromScore(recognitionConfidence)

    val portionBand: ConfidenceBand
        get() = ConfidenceBand.fromScore(portionConfidence)

    /**
     * Joint confidence over recognition, portion and density.
     *
     * Multiplicative because the stages are independently fallible: a perfect
     * food match on an unmeasurable portion is not a confident mass.
     */
    val overallConfidence: Float
        get() = recognitionConfidence * portionConfidence * densityConfidence

    private val densityConfidence: Float
        get() = if (isFallbackDensity) FALLBACK_DENSITY_CONFIDENCE else CATALOG_DENSITY_CONFIDENCE

    /** Recompute mass after the user adjusts the portion. Density is unchanged. */
    fun withCorrectedVolume(volumeMl: Double): MealItem {
        require(volumeMl > 0) { "Corrected volume must be positive" }
        val scale = volumeMl / estimatedVolumeMl
        return copy(
            estimatedVolumeMl = volumeMl,
            estimatedMassGrams = volumeMl * densityGramsPerMl,
            energyKcal = energyKcal?.times(scale),
            proteinGrams = proteinGrams?.times(scale),
            carbohydrateGrams = carbohydrateGrams?.times(scale),
            fatGrams = fatGrams?.times(scale),
            wasUserCorrected = true,
            originalMassGrams = originalMassGrams ?: estimatedMassGrams,
            // A person adjusting their own plate is the best signal available,
            // but they are estimating too, so this is not certainty.
            portionConfidence = USER_CORRECTION_CONFIDENCE,
        )
    }

    companion object {
        const val USER_CORRECTION_CONFIDENCE = 0.9f

        /**
         * Build an item the user entered by hand rather than one the model
         * proposed.
         *
         * Recognition confidence is 1.0 -- the person told us what the food is,
         * and that is the one thing here nobody is estimating. The portion is
         * still their estimate, so it carries the same 0.9 a correction does.
         */
        fun userEntered(
            id: String,
            displayName: String,
            category: FoodCategory,
            volumeMl: Double,
            densityGramsPerMl: Double,
            densitySource: String,
            foodKey: String? = null,
            isFallbackDensity: Boolean = false,
            energyKcalPer100g: Double? = null,
        ): MealItem {
            require(volumeMl > 0) { "Volume must be positive" }
            val mass = volumeMl * densityGramsPerMl
            return MealItem(
                id = id,
                displayName = displayName,
                foodKey = foodKey,
                category = category,
                estimatedVolumeMl = volumeMl,
                estimatedMassGrams = mass,
                densityGramsPerMl = densityGramsPerMl,
                densitySource = densitySource,
                isFallbackDensity = isFallbackDensity,
                recognitionConfidence = 1.0f,
                portionConfidence = USER_CORRECTION_CONFIDENCE,
                portionMethod = "user-entered",
                energyKcal = energyKcalPer100g?.let { it * mass / 100.0 },
                wasUserCorrected = true,
            )
        }

        /**
         * Density confidence used when reconstructing [overallConfidence] locally.
         *
         * The server sends a per-food density confidence; these two constants
         * are the coarse stand-in the client uses for records it has not yet
         * synced, and they bracket the server's range rather than flattering it.
         */
        const val CATALOG_DENSITY_CONFIDENCE = 0.7f
        const val FALLBACK_DENSITY_CONFIDENCE = 0.35f
    }
}
