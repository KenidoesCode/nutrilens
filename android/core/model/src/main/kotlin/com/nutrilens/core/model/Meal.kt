package com.nutrilens.core.model

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * One eating occasion.
 *
 * [consumedAt] is an absolute instant and [timeZone] is the zone the user was
 * in when they ate. Both are needed: the instant alone cannot answer "when in
 * your day did you eat", and a local time alone breaks across travel and DST.
 */
data class Meal(
    val id: String,
    val consumedAt: Instant,
    val timeZone: ZoneId,
    val mealType: MealType,
    val items: List<MealItem>,
    val notes: String? = null,
    val imagePath: String? = null,
    val syncState: SyncState = SyncState.PENDING,
    val remoteId: String? = null,
    val createdAt: Instant = consumedAt,
    val updatedAt: Instant = consumedAt,
) {
    /** Wall-clock time the user actually experienced. */
    val localDateTime: LocalDateTime
        get() = LocalDateTime.ofInstant(consumedAt, timeZone)

    /** Sum of the item masses. Derived, never stored, so an edit cannot desync it. */
    val totalMassGrams: Double
        get() = items.sumOf { it.estimatedMassGrams }

    /**
     * Estimated energy, or `null` when nothing is known.
     *
     * Items with unknown nutrition contribute nothing rather than zero, and a
     * meal made entirely of unknown foods reports `null` rather than `0.0` --
     * "we do not know" and "no calories" must not look the same.
     */
    val totalEnergyKcal: Double?
        get() = items.mapNotNull { it.energyKcal }.takeIf { it.isNotEmpty() }?.sum()

    /** True when any item's figures rest on a generic assumption. */
    val hasFallbackEstimates: Boolean
        get() = items.any { it.isFallbackDensity }

    /** The weakest link across items, for a single honest headline confidence. */
    val lowestConfidence: Float?
        get() = items.minOfOrNull { it.overallConfidence }
}
