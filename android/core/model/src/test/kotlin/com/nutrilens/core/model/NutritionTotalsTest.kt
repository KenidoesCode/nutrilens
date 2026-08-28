package com.nutrilens.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Nutrition aggregation.
 *
 * The rule worth pinning down: a food the catalog does not know contributes
 * nothing to the macro totals but still counts towards mass. Without that,
 * either the mass would be wrong or an unknown food would silently be treated
 * as having zero calories.
 */
class NutritionTotalsTest {

    private fun item(
        id: String,
        mass: Double,
        energy: Double?,
        protein: Double? = null,
    ) = MealItem(
        id = id,
        displayName = "Food",
        category = FoodCategory.SOLID,
        estimatedVolumeMl = mass,
        estimatedMassGrams = mass,
        densityGramsPerMl = 1.0,
        densitySource = "catalog@1",
        recognitionConfidence = 0.6f,
        portionConfidence = 0.6f,
        energyKcal = energy,
        proteinGrams = protein,
    )

    private fun meal(vararg items: MealItem) = Meal(
        id = "meal-" + items.joinToString("-") { it.id },
        consumedAt = Instant.parse("2026-05-01T08:00:00Z"),
        timeZone = ZoneId.of("Asia/Kolkata"),
        mealType = MealType.BREAKFAST,
        items = items.toList(),
    )

    @Test
    fun `an empty range totals nothing`() {
        val totals = NutritionTotals.from(emptyList())

        assertEquals(0.0, totals.energyKcal, 1e-9)
        assertFalse(totals.hasAnyData)
    }

    @Test
    fun `energy sums across meals`() {
        val totals = NutritionTotals.from(
            listOf(meal(item("a", 100.0, 130.0)), meal(item("b", 100.0, 70.0))),
        )

        assertEquals(200.0, totals.energyKcal, 1e-9)
    }

    @Test
    fun `mass sums across every item, known or not`() {
        val totals = NutritionTotals.from(
            listOf(meal(item("a", 100.0, 130.0), item("b", 50.0, null))),
        )

        assertEquals(150.0, totals.massGrams, 1e-9)
    }

    @Test
    fun `an unknown food contributes no energy`() {
        val totals = NutritionTotals.from(
            listOf(meal(item("a", 100.0, 130.0), item("b", 50.0, null))),
        )

        // Not 130 + 0: the unknown item is absent from the macro total, not
        // counted as a zero-calorie food.
        assertEquals(130.0, totals.energyKcal, 1e-9)
    }

    @Test
    fun `incompleteness is reported rather than hidden`() {
        val totals = NutritionTotals.from(
            listOf(meal(item("a", 100.0, 130.0), item("b", 50.0, null))),
        )

        assertEquals(1, totals.itemsWithKnownNutrition)
        assertEquals(1, totals.itemsMissingNutrition)
        assertTrue(totals.isIncomplete)
    }

    @Test
    fun `a fully known range is not flagged incomplete`() {
        val totals = NutritionTotals.from(listOf(meal(item("a", 100.0, 130.0))))

        assertFalse(totals.isIncomplete)
        assertTrue(totals.hasAnyData)
    }

    @Test
    fun `a missing macro on a known food counts as zero, not as unknown`() {
        // The item has energy, so it is "known"; a null protein is genuinely
        // absent from that food's record rather than making the whole item
        // unusable.
        val totals = NutritionTotals.from(
            listOf(meal(item("a", 100.0, 130.0, protein = null))),
        )

        assertEquals(1, totals.itemsWithKnownNutrition)
        assertEquals(0.0, totals.proteinGrams, 1e-9)
    }
}
