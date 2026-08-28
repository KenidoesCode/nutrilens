package com.nutrilens.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Foods the user adds by hand rather than ones the model proposed. */
class UserEnteredItemTest {

    private fun rice(volumeMl: Double = 180.0) = MealItem.userEntered(
        id = "item",
        displayName = "Rice",
        category = FoodCategory.SOLID,
        volumeMl = volumeMl,
        densityGramsPerMl = 0.85,
        densitySource = "local-food-catalog-cache",
        foodKey = "rice",
        energyKcalPer100g = 130.0,
    )

    @Test
    fun `mass follows from the volume and density`() {
        assertEquals(153.0, rice().estimatedMassGrams, 1e-9)
    }

    @Test
    fun `recognition is certain because the person named the food`() {
        // The one thing in the pipeline nobody is estimating.
        assertEquals(1.0f, rice().recognitionConfidence)
    }

    @Test
    fun `the portion is still an estimate`() {
        val item = rice()

        assertEquals(MealItem.USER_CORRECTION_CONFIDENCE, item.portionConfidence)
        assertTrue(item.portionConfidence < 1.0f)
    }

    @Test
    fun `energy scales from the per-100g value`() {
        assertEquals(198.9, rice().energyKcal!!, 1e-9)
    }

    @Test
    fun `an unknown energy stays unknown`() {
        val item = MealItem.userEntered(
            id = "x",
            displayName = "Something",
            category = FoodCategory.SOLID,
            volumeMl = 100.0,
            densityGramsPerMl = 1.0,
            densitySource = "category-default",
            energyKcalPer100g = null,
        )

        assertEquals(null, item.energyKcal)
    }

    @Test
    fun `it is marked as user-supplied`() {
        assertTrue(rice().wasUserCorrected)
        assertEquals("user-entered", rice().portionMethod)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive volume is rejected`() {
        rice(volumeMl = 0.0)
    }
}
