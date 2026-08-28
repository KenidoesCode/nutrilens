package com.nutrilens.core.data.repository

import com.nutrilens.core.common.time.TimeProvider
import com.nutrilens.core.data.mapper.toDomain
import com.nutrilens.core.database.dao.MealDao
import com.nutrilens.core.model.ChrononutritionCalculator
import com.nutrilens.core.model.EatingPatternSummary
import com.nutrilens.core.model.EatingWindow
import com.nutrilens.core.model.NutritionTotals
import com.nutrilens.core.model.repository.AnalyticsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Eating-pattern analytics, computed on the device.
 *
 * The server exposes the same figures, but they are derived here from the local
 * database so the numbers are correct the instant a meal is logged and remain
 * correct with no network. The calculator is shared, pure code whose rules
 * match the backend's exactly, so the two cannot drift.
 */
@Singleton
class DefaultAnalyticsRepository @Inject constructor(
    private val mealDao: MealDao,
    private val timeProvider: TimeProvider,
) : AnalyticsRepository {

    override fun observeTodayWindow(): Flow<EatingWindow> {
        val zone = timeProvider.currentZone()
        val now = timeProvider.now()
        val today = ChrononutritionCalculator.today(emptyList(), zone, now).day

        // Query a window wide enough to contain the logical day in any zone,
        // then let the calculator decide which meals actually belong to it.
        val start = ChrononutritionCalculator.startOfLogicalDay(today, zone)
        val end = ChrononutritionCalculator.startOfLogicalDay(today.plusDays(1), zone)

        return mealDao.observeBetween(start.toEpochMilli(), end.toEpochMilli())
            .map { rows ->
                ChrononutritionCalculator.windowFor(today, rows.map { it.toDomain() }, zone)
            }
    }

    override fun observePatternSummary(
        startDay: LocalDate,
        endDay: LocalDate,
    ): Flow<EatingPatternSummary> {
        val zone = timeProvider.currentZone()
        return observeRange(startDay, endDay).map { meals ->
            ChrononutritionCalculator.summarise(meals, zone, startDay, endDay)
        }
    }

    override fun observeNutritionTotals(
        startDay: LocalDate,
        endDay: LocalDate,
    ): Flow<NutritionTotals> =
        observeRange(startDay, endDay).map(NutritionTotals::from)

    /**
     * Meals in the logical days `[startDay, endDay]`.
     *
     * The window is bounded by the 04:00 day boundary rather than midnight, so
     * a late-night meal is counted on the day the user experienced it.
     */
    private fun observeRange(startDay: LocalDate, endDay: LocalDate) = run {
        val zone = timeProvider.currentZone()
        val start = ChrononutritionCalculator.startOfLogicalDay(startDay, zone)
        val end = ChrononutritionCalculator.startOfLogicalDay(endDay.plusDays(1), zone)
        mealDao.observeBetween(start.toEpochMilli(), end.toEpochMilli())
            .map { rows -> rows.map { it.toDomain() } }
    }
}
