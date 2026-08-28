package com.nutrilens.core.model

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Computes eating-window statistics on the device.
 *
 * The server computes the same figures, but the app must show today's window
 * the moment a meal is logged -- including with no network -- so the rules live
 * here too. This is a pure object with no Android or I/O dependency; the
 * boundary rule and the consistency formula match the backend engine exactly,
 * so the two never disagree about the same data.
 *
 * This reports observations only. It does not diagnose or advise.
 */
object ChrononutritionCalculator {

    private const val MINUTES_PER_DAY = 24 * 60

    /**
     * The logical day a meal belongs to.
     *
     * Meals before [EatingWindow.DAY_BOUNDARY_HOUR] count towards the previous
     * day, so a late supper is not recorded as the start of a new one.
     */
    fun logicalDate(meal: Meal, zone: ZoneId = meal.timeZone): LocalDate {
        val local = LocalDateTime.ofInstant(meal.consumedAt, zone)
        return if (local.hour < EatingWindow.DAY_BOUNDARY_HOUR) {
            local.toLocalDate().minusDays(1)
        } else {
            local.toLocalDate()
        }
    }

    /** Group meals into logical days, each day's meals ordered by time. */
    fun groupByDay(meals: List<Meal>, zone: ZoneId): Map<LocalDate, List<Meal>> =
        meals.groupBy { logicalDate(it, zone) }
            .mapValues { (_, dayMeals) -> dayMeals.sortedBy { it.consumedAt } }

    /**
     * Statistics for one day.
     *
     * The fasting period is the complement of the eating window within 24
     * hours: the overnight fast implied by this day's own first and last meal.
     * It is not a measured gap to the neighbouring day, which needs that day's
     * data and is computed by [fastingBetween].
     */
    fun windowFor(day: LocalDate, meals: List<Meal>, zone: ZoneId): EatingWindow {
        if (meals.isEmpty()) return EatingWindow.empty(day)

        val ordered = meals.sortedBy { it.consumedAt }
        val first = LocalDateTime.ofInstant(ordered.first().consumedAt, zone)
        val last = LocalDateTime.ofInstant(ordered.last().consumedAt, zone)

        val window = if (ordered.size > 1) {
            Duration.between(ordered.first().consumedAt, ordered.last().consumedAt)
        } else {
            null
        }
        val fasting = window?.let {
            Duration.ofMinutes((MINUTES_PER_DAY - it.toMinutes()).coerceAtLeast(0))
        }

        return EatingWindow(
            day = day,
            mealCount = ordered.size,
            firstMealLocalTime = first.toLocalTime().withSecond(0).withNano(0),
            lastMealLocalTime = last.toLocalTime().withSecond(0).withNano(0),
            eatingWindow = window,
            fastingPeriod = fasting,
        )
    }

    /** Today's window, given the current instant. */
    fun today(meals: List<Meal>, zone: ZoneId, now: java.time.Instant): EatingWindow {
        val local = LocalDateTime.ofInstant(now, zone)
        val day = if (local.hour < EatingWindow.DAY_BOUNDARY_HOUR) {
            local.toLocalDate().minusDays(1)
        } else {
            local.toLocalDate()
        }
        return windowFor(day, groupByDay(meals, zone)[day].orEmpty(), zone)
    }

    /**
     * One entry per day in the range, including days with no meals.
     *
     * Empty days are represented rather than skipped: a gap is information, and
     * the UI should not have to infer it from missing keys.
     */
    fun windowsInRange(
        meals: List<Meal>,
        zone: ZoneId,
        startDay: LocalDate,
        endDay: LocalDate,
    ): List<EatingWindow> {
        require(!endDay.isBefore(startDay)) { "endDay must not precede startDay" }
        val grouped = groupByDay(meals, zone)
        return generateSequence(startDay) { current ->
            if (current.isBefore(endDay)) current.plusDays(1) else null
        }.map { day -> windowFor(day, grouped[day].orEmpty(), zone) }.toList()
    }

    /** Minutes between one day's last meal and the next day's first. */
    fun fastingBetween(earlier: EatingWindow, later: EatingWindow): Duration? {
        val from = earlier.lastMealLocalTime ?: return null
        val to = later.firstMealLocalTime ?: return null
        val fromDateTime = LocalDateTime.of(earlier.day, from)
        val toDateTime = LocalDateTime.of(later.day, to)
        val duration = Duration.between(fromDateTime, toDateTime)
        return duration.takeIf { !it.isNegative }
    }

    /** Aggregate a range of days into one summary. */
    fun summarise(
        meals: List<Meal>,
        zone: ZoneId,
        startDay: LocalDate,
        endDay: LocalDate,
    ): EatingPatternSummary {
        val days = windowsInRange(meals, zone, startDay, endDay)
        val windows = days.mapNotNull { it.eatingWindow?.toMinutes() }
        return EatingPatternSummary(
            startDay = startDay,
            endDay = endDay,
            days = days,
            daysWithMeals = days.count { it.hasMeals },
            totalMeals = days.sumOf { it.mealCount },
            meanEatingWindow = windows
                .takeIf { it.isNotEmpty() }
                ?.let { Duration.ofMinutes(it.average().toLong()) },
            windowConsistency = consistency(windows),
        )
    }

    /**
     * How stable the eating window is across days, in `[0, 1]`.
     *
     * `1 - min(1, stdev/mean)`. Returns `null` below two measurable days rather
     * than a flattering 1.0, because one day cannot evidence consistency.
     */
    fun consistency(windowMinutes: List<Long>): Float? {
        if (windowMinutes.size < 2) return null
        val mean = windowMinutes.average()
        if (mean <= 0.0) return null
        // Sample standard deviation, matching the backend's statistics.stdev.
        val variance = windowMinutes.sumOf { (it - mean) * (it - mean) } / (windowMinutes.size - 1)
        val coefficientOfVariation = sqrt(variance) / mean
        return (1.0 - min(1.0, coefficientOfVariation)).coerceAtLeast(0.0).toFloat()
    }

    /** Start of the current logical day, for querying a local meal range. */
    fun startOfLogicalDay(day: LocalDate, zone: ZoneId): java.time.Instant =
        LocalDateTime.of(day, LocalTime.of(EatingWindow.DAY_BOUNDARY_HOUR, 0))
            .atZone(zone)
            .toInstant()
}
