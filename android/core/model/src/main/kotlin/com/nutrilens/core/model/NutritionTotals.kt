package com.nutrilens.core.model

/**
 * Summed nutrition estimates over a span of days.
 *
 * Every field inherits the uncertainty of the mass estimates that produced it,
 * so this is an approximation of an approximation and the UI labels it as such.
 *
 * [itemsWithKnownNutrition] and [itemsMissingNutrition] are carried explicitly
 * because a food the catalog does not know contributes nothing to the macro
 * totals while still contributing to [massGrams]. Without the counts, the two
 * figures would appear to disagree for no visible reason.
 */
data class NutritionTotals(
    val energyKcal: Double,
    val proteinGrams: Double,
    val carbohydrateGrams: Double,
    val fatGrams: Double,
    val massGrams: Double,
    val itemsWithKnownNutrition: Int,
    val itemsMissingNutrition: Int,
) {
    val hasAnyData: Boolean get() = itemsWithKnownNutrition + itemsMissingNutrition > 0

    /** True when some of the mass is unaccounted for in the macro totals. */
    val isIncomplete: Boolean get() = itemsMissingNutrition > 0

    companion object {
        val EMPTY = NutritionTotals(
            energyKcal = 0.0,
            proteinGrams = 0.0,
            carbohydrateGrams = 0.0,
            fatGrams = 0.0,
            massGrams = 0.0,
            itemsWithKnownNutrition = 0,
            itemsMissingNutrition = 0,
        )

        /**
         * Sum the live items of a set of meals.
         *
         * Pure, so the aggregation rule -- unknown foods contribute nothing to
         * macros but still count towards mass -- is testable without a database.
         */
        fun from(meals: List<Meal>): NutritionTotals {
            var energy = 0.0
            var protein = 0.0
            var carbohydrate = 0.0
            var fat = 0.0
            var mass = 0.0
            var known = 0
            var missing = 0

            for (meal in meals) {
                for (item in meal.items) {
                    mass += item.estimatedMassGrams
                    if (item.energyKcal == null) {
                        missing++
                        continue
                    }
                    known++
                    energy += item.energyKcal
                    protein += item.proteinGrams ?: 0.0
                    carbohydrate += item.carbohydrateGrams ?: 0.0
                    fat += item.fatGrams ?: 0.0
                }
            }

            return NutritionTotals(
                energyKcal = energy,
                proteinGrams = protein,
                carbohydrateGrams = carbohydrate,
                fatGrams = fat,
                massGrams = mass,
                itemsWithKnownNutrition = known,
                itemsMissingNutrition = missing,
            )
        }
    }
}
