package com.nutrilens.core.model

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/**
 * Timing statistics for one logical day.
 *
 * A `null` window means "not enough information", which is distinct from a
 * zero-length one. A day with a single meal has no window at all.
 */
data class EatingWindow(
    val day: LocalDate,
    val mealCount: Int,
    val firstMealLocalTime: LocalTime?,
    val lastMealLocalTime: LocalTime?,
    val eatingWindow: Duration?,
    val fastingPeriod: Duration?,
) {
    val hasMeals: Boolean get() = mealCount > 0

    /** True when there is exactly one meal, so no window can be computed. */
    val isSingleMealDay: Boolean get() = mealCount == 1

    companion object {
        /**
         * A logical day starts at 04:00 local time, not midnight.
         *
         * A meal at 00:30 belongs to the evening that preceded it. Using
         * midnight would split a single evening across two days and report a
         * fast that the person did not take.
         */
        const val DAY_BOUNDARY_HOUR = 4

        fun empty(day: LocalDate): EatingWindow = EatingWindow(
            day = day,
            mealCount = 0,
            firstMealLocalTime = null,
            lastMealLocalTime = null,
            eatingWindow = null,
            fastingPeriod = null,
        )
    }
}

/**
 * Aggregate timing statistics across a span of days.
 *
 * [windowConsistency] is `1 - min(1, stdev/mean)` over the daily windows: 1.0
 * means the window barely varies, 0.0 means it varies as much as its own
 * average. It is `null` below two measurable days, because one day says nothing
 * about consistency.
 */
data class EatingPatternSummary(
    val startDay: LocalDate,
    val endDay: LocalDate,
    val days: List<EatingWindow>,
    val daysWithMeals: Int,
    val totalMeals: Int,
    val meanEatingWindow: Duration?,
    val windowConsistency: Float?,
) {
    val meanMealsPerActiveDay: Float?
        get() = if (daysWithMeals == 0) null else totalMeals.toFloat() / daysWithMeals
}
