package com.nutrilens.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MealTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    private fun item(
        id: String = "item",
        name: String = "Rice",
        volumeMl: Double = 180.0,
        density: Double = 0.85,
        energy: Double? = 198.9,
        fallbackDensity: Boolean = false,
    ) = MealItem(
        id = id,
        displayName = name,
        category = FoodCategory.SOLID,
        estimatedVolumeMl = volumeMl,
        estimatedMassGrams = volumeMl * density,
        densityGramsPerMl = density,
        densitySource = "nutrilens-food-catalog@2024.1",
        isFallbackDensity = fallbackDensity,
        recognitionConfidence = 0.62f,
        portionConfidence = 0.65f,
        energyKcal = energy,
    )

    private fun meal(items: List<MealItem>) = Meal(
        id = "meal",
        consumedAt = Instant.parse("2026-05-01T03:12:00Z"),
        timeZone = zone,
        mealType = MealType.BREAKFAST,
        items = items,
    )

    @Test
    fun `total mass sums the items`() {
        assertEquals(306.0, meal(listOf(item("a"), item("b"))).totalMassGrams, 1e-9)
    }

    @Test
    fun `total energy sums the items`() {
        assertEquals(397.8, meal(listOf(item("a"), item("b"))).totalEnergyKcal!!, 1e-9)
    }

    @Test
    fun `unknown energy is null rather than zero`() {
        // "We do not know" and "no calories" must not be confusable.
        assertNull(meal(listOf(item(energy = null))).totalEnergyKcal)
    }

    @Test
    fun `known energy still counts when a sibling item is unknown`() {
        val total = meal(listOf(item("a", energy = 100.0), item("b", energy = null)))
        assertEquals(100.0, total.totalEnergyKcal!!, 1e-9)
    }

    @Test
    fun `local time reflects the stored zone`() {
        assertEquals("08:42", meal(listOf(item())).localDateTime.toLocalTime().toString())
    }

    @Test
    fun `fallback estimates are surfaced on the meal`() {
        assertTrue(meal(listOf(item(fallbackDensity = true))).hasFallbackEstimates)
        assertFalse(meal(listOf(item())).hasFallbackEstimates)
    }

    @Test
    fun `a new meal starts pending so it is never lost offline`() {
        assertEquals(SyncState.PENDING, meal(listOf(item())).syncState)
        assertTrue(SyncState.PENDING.isOutstanding)
        assertTrue(SyncState.PENDING.isEligibleForUpload)
    }
}

class MealItemTest {

    private fun rice() = MealItem(
        id = "item",
        displayName = "Rice",
        category = FoodCategory.SOLID,
        estimatedVolumeMl = 180.0,
        estimatedMassGrams = 153.0,
        densityGramsPerMl = 0.85,
        densitySource = "nutrilens-food-catalog@2024.1",
        recognitionConfidence = 0.9f,
        portionConfidence = 0.8f,
        energyKcal = 198.9,
        proteinGrams = 4.13,
    )

    @Test
    fun `correcting the volume recomputes mass from the same density`() {
        val corrected = rice().withCorrectedVolume(100.0)

        assertEquals(100.0, corrected.estimatedVolumeMl, 1e-9)
        assertEquals(85.0, corrected.estimatedMassGrams, 1e-9)
    }

    @Test
    fun `correcting the volume scales the nutrition with it`() {
        val corrected = rice().withCorrectedVolume(90.0)

        assertEquals(99.45, corrected.energyKcal!!, 1e-9)
        assertEquals(2.065, corrected.proteinGrams!!, 1e-9)
    }

    @Test
    fun `a correction preserves the original estimate for later evaluation`() {
        val corrected = rice().withCorrectedVolume(100.0)

        assertTrue(corrected.wasUserCorrected)
        assertEquals(153.0, corrected.originalMassGrams!!, 1e-9)
    }

    @Test
    fun `correcting twice keeps the first original, not the second`() {
        val corrected = rice().withCorrectedVolume(100.0).withCorrectedVolume(50.0)

        assertEquals(153.0, corrected.originalMassGrams!!, 1e-9)
    }

    @Test
    fun `a correction is confident but not certain`() {
        val corrected = rice().withCorrectedVolume(100.0)

        assertEquals(MealItem.USER_CORRECTION_CONFIDENCE, corrected.portionConfidence)
        assertTrue(corrected.portionConfidence < 1.0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive correction is rejected`() {
        rice().withCorrectedVolume(0.0)
    }

    @Test
    fun `overall confidence multiplies the stages`() {
        // 0.9 recognition x 0.8 portion x 0.7 catalog density.
        assertEquals(0.504f, rice().overallConfidence, 1e-6f)
    }

    @Test
    fun `a fallback density lowers the overall confidence`() {
        val fallback = rice().copy(isFallbackDensity = true)

        assertTrue(fallback.overallConfidence < rice().overallConfidence)
    }
}

class ConfidenceBandTest {

    @Test
    fun `bands split at the documented thresholds`() {
        assertEquals(ConfidenceBand.LOW, ConfidenceBand.fromScore(0.0f))
        assertEquals(ConfidenceBand.LOW, ConfidenceBand.fromScore(0.54f))
        assertEquals(ConfidenceBand.MEDIUM, ConfidenceBand.fromScore(0.55f))
        assertEquals(ConfidenceBand.MEDIUM, ConfidenceBand.fromScore(0.79f))
        assertEquals(ConfidenceBand.HIGH, ConfidenceBand.fromScore(0.80f))
        assertEquals(ConfidenceBand.HIGH, ConfidenceBand.fromScore(1.0f))
    }
}

class FoodCategoryTest {

    @Test
    fun `parses the wire vocabulary`() {
        assertEquals(FoodCategory.SEMISOLID, FoodCategory.fromWire("semisolid"))
        assertEquals(FoodCategory.LIQUID, FoodCategory.fromWire("  LIQUID "))
    }

    @Test
    fun `an unknown category is null, not a silent guess`() {
        assertNull(FoodCategory.fromWire("plasma"))
    }

    @Test
    fun `the tolerant parser never drops an item`() {
        assertEquals(FoodCategory.SOLID, FoodCategory.fromWireOrDefault("plasma"))
    }
}

class MealTypeTest {

    @Test
    fun `suggests a meal type from the time of day`() {
        assertEquals(MealType.BREAKFAST, MealType.suggestFor(java.time.LocalTime.of(8, 0)))
        assertEquals(MealType.LUNCH, MealType.suggestFor(java.time.LocalTime.of(13, 0)))
        assertEquals(MealType.DINNER, MealType.suggestFor(java.time.LocalTime.of(20, 0)))
        assertEquals(MealType.SNACK, MealType.suggestFor(java.time.LocalTime.of(2, 0)))
    }

    @Test
    fun `an unknown wire value degrades to OTHER`() {
        assertEquals(MealType.OTHER, MealType.fromWire("brunch"))
    }
}

class OutcomeTest {

    @Test
    fun `map transforms a success`() {
        val result = Outcome.success(2).map { it * 3 }
        assertEquals(6, result.getOrNull())
    }

    @Test
    fun `map leaves a failure untouched`() {
        val result: Outcome<Int> = Outcome.failure(AppError.Offline)
        assertEquals(AppError.Offline, result.map { it * 3 }.errorOrNull())
    }

    @Test
    fun `flatMap chains successes`() {
        val result = Outcome.success(2).flatMap { Outcome.success(it + 1) }
        assertEquals(3, result.getOrNull())
    }

    @Test
    fun `transient failures are retryable and permanent ones are not`() {
        assertTrue(AppError.Offline.isRetryable)
        assertTrue(AppError.Timeout.isRetryable)
        assertTrue(AppError.RateLimited(30).isRetryable)
        assertTrue(AppError.ServerError("INTERNAL_ERROR").isRetryable)

        // Retrying these would burn the budget on a request that cannot succeed.
        assertFalse(AppError.InvalidCredentials.isRetryable)
        assertFalse(AppError.SessionExpired.isRetryable)
        assertFalse(AppError.NotFound.isRetryable)
        assertFalse(AppError.WeakPassword(null).isRetryable)
    }
}
