package com.nutrilens.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The on-device chrononutrition rules.
 *
 * These mirror the backend's tests deliberately: the two implementations must
 * agree on the same data, and the shared worked example (08:42 to 19:16) is
 * asserted in both suites.
 */
class ChrononutritionCalculatorTest {

    private val kolkata: ZoneId = ZoneId.of("Asia/Kolkata")
    private val newYork: ZoneId = ZoneId.of("America/New_York")

    /** A meal at a given local time on 2026-05-<day>. */
    private fun meal(
        hour: Int,
        minute: Int = 0,
        day: Int = 1,
        zone: ZoneId = kolkata,
        id: String = "meal-$day-$hour-$minute",
    ) = Meal(
        id = id,
        consumedAt = LocalDateTime.of(2026, 5, day, hour, minute).atZone(zone).toInstant(),
        timeZone = zone,
        mealType = MealType.OTHER,
        items = emptyList(),
    )

    @Test
    fun `reproduces the documented eating window example`() {
        val meals = listOf(meal(8, 42), meal(14, 0), meal(19, 16))

        val window = ChrononutritionCalculator.windowFor(LocalDate.of(2026, 5, 1), meals, kolkata)

        assertEquals(3, window.mealCount)
        assertEquals("08:42", window.firstMealLocalTime.toString())
        assertEquals("19:16", window.lastMealLocalTime.toString())
        assertEquals(634L, window.eatingWindow?.toMinutes())
        assertEquals(806L, window.fastingPeriod?.toMinutes())
    }

    @Test
    fun `window and fast together span a full day`() {
        val window = ChrononutritionCalculator.windowFor(
            LocalDate.of(2026, 5, 1),
            listOf(meal(8), meal(20)),
            kolkata,
        )

        val total = window.eatingWindow!!.toMinutes() + window.fastingPeriod!!.toMinutes()
        assertEquals(24L * 60, total)
    }

    @Test
    fun `a single meal has no window rather than a zero one`() {
        val window = ChrononutritionCalculator.windowFor(
            LocalDate.of(2026, 5, 1),
            listOf(meal(12)),
            kolkata,
        )

        assertNull(window.eatingWindow)
        assertNull(window.fastingPeriod)
        assertTrue(window.isSingleMealDay)
    }

    @Test
    fun `an empty day reports no meals`() {
        val window = ChrononutritionCalculator.windowFor(
            LocalDate.of(2026, 5, 1),
            emptyList(),
            kolkata,
        )

        assertEquals(0, window.mealCount)
        assertNull(window.firstMealLocalTime)
    }

    @Test
    fun `meals given out of order are sorted before measuring`() {
        val window = ChrononutritionCalculator.windowFor(
            LocalDate.of(2026, 5, 1),
            listOf(meal(19, 16), meal(8, 42), meal(14)),
            kolkata,
        )

        assertEquals("08:42", window.firstMealLocalTime.toString())
        assertEquals("19:16", window.lastMealLocalTime.toString())
    }

    @Test
    fun `a late night meal belongs to the previous evening`() {
        assertEquals(
            LocalDate.of(2026, 5, 1),
            ChrononutritionCalculator.logicalDate(meal(1, 30, day = 2), kolkata),
        )
    }

    @Test
    fun `the boundary hour starts a new day`() {
        assertEquals(
            LocalDate.of(2026, 5, 2),
            ChrononutritionCalculator.logicalDate(meal(4, 0, day = 2), kolkata),
        )
    }

    @Test
    fun `a normal daytime meal belongs to its own date`() {
        assertEquals(
            LocalDate.of(2026, 5, 1),
            ChrononutritionCalculator.logicalDate(meal(13, 0), kolkata),
        )
    }

    @Test
    fun `grouping keeps a late meal with the evening it followed`() {
        val grouped = ChrononutritionCalculator.groupByDay(
            listOf(meal(9, day = 1), meal(1, day = 2), meal(9, day = 2)),
            kolkata,
        )

        assertEquals(2, grouped.keys.size)
        assertEquals(2, grouped[LocalDate.of(2026, 5, 1)]?.size)
    }

    @Test
    fun `a range includes days with no meals`() {
        val windows = ChrononutritionCalculator.windowsInRange(
            listOf(meal(9, day = 1)),
            kolkata,
            LocalDate.of(2026, 5, 1),
            LocalDate.of(2026, 5, 3),
        )

        assertEquals(3, windows.size)
        assertEquals(listOf(1, 0, 0), windows.map { it.mealCount })
    }

    @Test
    fun `overnight fasting spans two days`() {
        val windows = ChrononutritionCalculator.windowsInRange(
            listOf(meal(20, day = 1), meal(8, day = 2)),
            kolkata,
            LocalDate.of(2026, 5, 1),
            LocalDate.of(2026, 5, 2),
        )

        assertEquals(
            Duration.ofHours(12),
            ChrononutritionCalculator.fastingBetween(windows[0], windows[1]),
        )
    }

    @Test
    fun `overnight fasting is unknown when a day has no meals`() {
        val windows = ChrononutritionCalculator.windowsInRange(
            listOf(meal(20, day = 1)),
            kolkata,
            LocalDate.of(2026, 5, 1),
            LocalDate.of(2026, 5, 2),
        )

        assertNull(ChrononutritionCalculator.fastingBetween(windows[0], windows[1]))
    }

    @Test
    fun `identical days are perfectly consistent`() {
        assertEquals(1.0f, ChrononutritionCalculator.consistency(listOf(600L, 600L, 600L)))
    }

    @Test
    fun `variation as large as the mean scores zero`() {
        assertEquals(0.0f, ChrononutritionCalculator.consistency(listOf(0L, 1200L)))
    }

    @Test
    fun `consistency needs at least two measurable days`() {
        assertNull(ChrononutritionCalculator.consistency(listOf(600L)))
        assertNull(ChrononutritionCalculator.consistency(emptyList()))
    }

    @Test
    fun `a summary aggregates across days`() {
        val meals = (1..3).flatMap { day -> listOf(meal(8, day = day), meal(20, day = day)) }

        val summary = ChrononutritionCalculator.summarise(
            meals,
            kolkata,
            LocalDate.of(2026, 5, 1),
            LocalDate.of(2026, 5, 3),
        )

        assertEquals(3, summary.daysWithMeals)
        assertEquals(6, summary.totalMeals)
        assertEquals(720L, summary.meanEatingWindow?.toMinutes())
        assertEquals(2.0f, summary.meanMealsPerActiveDay)
        assertEquals(1.0f, summary.windowConsistency)
    }

    @Test
    fun `an empty range reports unknown rather than zero`() {
        val summary = ChrononutritionCalculator.summarise(
            emptyList(),
            kolkata,
            LocalDate.of(2026, 5, 1),
            LocalDate.of(2026, 5, 7),
        )

        assertEquals(0, summary.totalMeals)
        assertNull(summary.meanEatingWindow)
        assertNull(summary.meanMealsPerActiveDay)
    }

    @Test
    fun `the same instant falls on different local days in different zones`() {
        // Why a meal stores its zone: the instant alone cannot place it in a day.
        val instant = LocalDateTime.of(2026, 5, 2, 2, 0).atZone(ZoneId.of("UTC")).toInstant()
        val record = Meal(
            id = "m",
            consumedAt = instant,
            timeZone = kolkata,
            mealType = MealType.OTHER,
            items = emptyList(),
        )

        assertEquals(
            LocalDate.of(2026, 5, 2),
            ChrononutritionCalculator.logicalDate(record, kolkata),
        )
        assertEquals(
            LocalDate.of(2026, 5, 1),
            ChrononutritionCalculator.logicalDate(record, newYork),
        )
    }

    @Test
    fun `a window spanning a dst shift measures elapsed time`() {
        // US clocks jump 02:00 to 03:00 on 2026-03-08. The clock says six hours
        // passed between 01:00 and 07:00; only five actually did.
        val before = LocalDateTime.of(2026, 3, 8, 1, 0).atZone(newYork).toInstant()
        val after = LocalDateTime.of(2026, 3, 8, 7, 0).atZone(newYork).toInstant()
        val meals = listOf(
            Meal("a", before, newYork, MealType.SNACK, emptyList()),
            Meal("b", after, newYork, MealType.BREAKFAST, emptyList()),
        )

        val window = ChrononutritionCalculator.windowFor(LocalDate.of(2026, 3, 8), meals, newYork)

        assertEquals(300L, window.eatingWindow?.toMinutes())
    }

    @Test
    fun `today uses the logical day boundary`() {
        // 02:00 local still belongs to the previous day.
        val now = LocalDateTime.of(2026, 5, 2, 2, 0).atZone(kolkata).toInstant()

        val window = ChrononutritionCalculator.today(listOf(meal(20, day = 1)), kolkata, now)

        assertEquals(LocalDate.of(2026, 5, 1), window.day)
        assertEquals(1, window.mealCount)
    }
}
