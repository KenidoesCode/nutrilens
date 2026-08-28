package com.nutrilens.core.designsystem.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nutrilens.core.designsystem.R
import com.nutrilens.core.designsystem.component.ConfidenceLevel
import com.nutrilens.core.model.ConfidenceBand
import com.nutrilens.core.model.FoodCategory
import com.nutrilens.core.model.MealType
import com.nutrilens.core.model.repository.SyncStatus
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Shared label resolution.
 *
 * These mappings were duplicated across four feature modules, which meant a
 * meal type could be spelled one way on the timeline and another on the home
 * screen. One place, one answer.
 */

@Composable
fun MealType.label(): String = stringResource(
    when (this) {
        MealType.BREAKFAST -> R.string.meal_type_breakfast
        MealType.LUNCH -> R.string.meal_type_lunch
        MealType.DINNER -> R.string.meal_type_dinner
        MealType.SNACK -> R.string.meal_type_snack
        MealType.BEVERAGE -> R.string.meal_type_beverage
        MealType.OTHER -> R.string.meal_type_other
    },
)

@Composable
fun FoodCategory.label(): String = stringResource(
    when (this) {
        FoodCategory.SOLID -> R.string.item_category_solid
        FoodCategory.SEMISOLID -> R.string.item_category_semisolid
        FoodCategory.LIQUID -> R.string.item_category_liquid
    },
)

@Composable
fun ConfidenceBand.label(): String = stringResource(
    when (this) {
        ConfidenceBand.LOW -> R.string.item_confidence_low
        ConfidenceBand.MEDIUM -> R.string.item_confidence_medium
        ConfidenceBand.HIGH -> R.string.item_confidence_high
    },
)

fun ConfidenceBand.toUiLevel(): ConfidenceLevel = when (this) {
    ConfidenceBand.LOW -> ConfidenceLevel.LOW
    ConfidenceBand.MEDIUM -> ConfidenceLevel.MEDIUM
    ConfidenceBand.HIGH -> ConfidenceLevel.HIGH
}

/** A duration as "10h 34m", localised. */
@Composable
fun Duration.asHoursAndMinutes(): String = stringResource(
    R.string.window_duration_hours_minutes,
    toHours().toInt(),
    (toMinutes() % 60).toInt(),
)

/**
 * Round an estimate to whole grams.
 *
 * Decimal places on a figure carrying tens of percent of uncertainty imply a
 * precision that is not there.
 */
fun Double.asWholeGrams(): String = roundToInt().toString()

/** What the sync banner should say, or `null` when there is nothing to say. */
data class SyncMessage(
    val message: String,
    val detail: String?,
    val retryLabel: String?,
    val isProblem: Boolean,
)

/**
 * Resolve a [SyncStatus] into wording.
 *
 * Ordered by what the user most needs to know: a failure first, then being
 * offline (which explains a queue without alarming anyone), then a plain
 * backlog. Retry is offered only when it could work.
 */
@Composable
fun SyncStatus.toMessage(lastSyncedLabel: (Instant) -> String): SyncMessage? = when {
    isFullySynced -> null

    failedCount > 0 -> SyncMessage(
        message = stringResource(R.string.sync_failed),
        detail = lastSyncedAt?.let { stringResource(R.string.sync_last_synced, lastSyncedLabel(it)) },
        retryLabel = if (isOnline) stringResource(R.string.sync_retry_now) else null,
        isProblem = true,
    )

    !isOnline -> SyncMessage(
        message = stringResource(R.string.sync_offline),
        detail = lastSyncedAt?.let { stringResource(R.string.sync_last_synced, lastSyncedLabel(it)) },
        // Offering a retry with no connection is a button that cannot work.
        retryLabel = null,
        isProblem = false,
    )

    else -> SyncMessage(
        message = if (pendingCount == 1) {
            stringResource(R.string.sync_pending, pendingCount)
        } else {
            stringResource(R.string.sync_pending_plural, pendingCount)
        },
        detail = null,
        retryLabel = stringResource(R.string.sync_retry_now),
        isProblem = false,
    )
}
