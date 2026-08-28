package com.nutrilens.feature.home.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.nutrilens.core.designsystem.R as UiR
import com.nutrilens.core.designsystem.component.EatingWindowBar
import com.nutrilens.core.designsystem.component.EmptyEatingWindowBar
import com.nutrilens.core.designsystem.component.NutriLensCard
import com.nutrilens.core.designsystem.theme.Dimens
import com.nutrilens.core.designsystem.theme.NutriLensTheme
import com.nutrilens.core.model.EatingWindow
import com.nutrilens.core.model.Meal
import com.nutrilens.core.model.repository.SyncStatus
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Today's eating window.
 *
 * The card distinguishes three states rather than collapsing them: no meals,
 * one meal (a window cannot be computed), and two or more. Showing "0h 0m" for
 * a single meal would state something the data does not support.
 */
@Composable
fun EatingWindowCard(
    window: EatingWindow,
    modifier: Modifier = Modifier,
) {
    NutriLensCard(modifier = modifier) {
        Text(
            text = stringResource(UiR.string.window_title),
            style = MaterialTheme.typography.titleMedium,
        )

        when {
            !window.hasMeals -> {
                EmptyEatingWindowBar(
                    label = stringResource(UiR.string.window_no_meals),
                    contentDescription = stringResource(
                        UiR.string.window_empty_content_description,
                        window.day.format(dayFormatter),
                    ),
                    modifier = Modifier.padding(top = Dimens.spaceMedium),
                )
            }

            window.isSingleMealDay -> {
                Text(
                    text = stringResource(UiR.string.window_not_enough_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Dimens.spaceSmall),
                )
                MetricRow(
                    label = stringResource(UiR.string.window_meals),
                    value = window.mealCount.toString(),
                )
            }

            else -> {
                val first = window.firstMealLocalTime ?: LocalTime.MIDNIGHT
                val last = window.lastMealLocalTime ?: LocalTime.MIDNIGHT
                val eating = window.eatingWindow ?: Duration.ZERO
                val fasting = window.fastingPeriod ?: Duration.ZERO

                EatingWindowBar(
                    startFraction = first.toFractionOfDay(),
                    endFraction = last.toFractionOfDay(),
                    startLabel = first.format(timeFormatter),
                    endLabel = last.format(timeFormatter),
                    contentDescription = stringResource(
                        UiR.string.window_content_description,
                        first.format(timeFormatter),
                        last.format(timeFormatter),
                        eating.format(),
                        fasting.format(),
                    ),
                    modifier = Modifier.padding(vertical = Dimens.spaceMedium),
                )

                MetricRow(
                    label = stringResource(UiR.string.window_first_meal),
                    value = first.format(timeFormatter),
                )
                MetricRow(
                    label = stringResource(UiR.string.window_last_meal),
                    value = last.format(timeFormatter),
                )
                MetricRow(
                    label = stringResource(UiR.string.window_eating_window),
                    value = eating.format(),
                )
                MetricRow(
                    label = stringResource(UiR.string.window_fasting_period),
                    value = fasting.format(),
                )
                MetricRow(
                    label = stringResource(UiR.string.window_meals),
                    value = window.mealCount.toString(),
                )
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.spaceExtraSmall)
            // One node per row so a screen reader reads "Eating window,
            // 10 hours 34 minutes" rather than the label and value separately.
            .semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** One meal in a list. */
@Composable
fun MealSummaryRow(
    meal: Meal,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mealTypeLabel = stringResource(meal.mealType.labelRes())
    val time = meal.localDateTime.toLocalTime().format(timeFormatter)
    val mass = formatGrams(meal.totalMassGrams)

    NutriLensCard(
        modifier = modifier
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = Dimens.minimumTouchTarget)
            .semantics(mergeDescendants = true) {
                contentDescription = "$mealTypeLabel, $time, ${mass}g"
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(text = mealTypeLabel, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = meal.items.joinToString(", ") { it.displayName },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(text = time, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(UiR.string.item_mass_grams, mass),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (meal.syncState.isOutstanding) {
            Text(
                text = stringResource(UiR.string.sync_pending_plural, 1),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = Dimens.spaceExtraSmall),
            )
        }
    }
}

/**
 * The sync state, in plain language.
 *
 * "3 meals waiting to upload" answers the user's real question -- is my data
 * safe -- in a way an indeterminate spinner does not.
 */
@Composable
fun SyncStatusRow(
    status: SyncStatus,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NutriLensCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (status.pendingCount == 1) {
                    stringResource(UiR.string.sync_pending, status.pendingCount)
                } else {
                    stringResource(UiR.string.sync_pending_plural, status.pendingCount)
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Start,
            )
            TextButton(
                onClick = onRetry,
                modifier = Modifier.defaultMinSize(minHeight = Dimens.minimumTouchTarget),
            ) {
                Text(stringResource(UiR.string.sync_retry_now))
            }
        }
    }
}

// --- formatting ----------------------------------------------------------

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

private val dayFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private fun LocalTime.toFractionOfDay(): Float =
    (hour * 60 + minute) / (24f * 60f)

@Composable
private fun Duration.format(): String = stringResource(
    UiR.string.window_duration_hours_minutes,
    toHours().toInt(),
    (toMinutes() % 60).toInt(),
)

/** Round to whole grams: an estimate does not warrant decimal places. */
private fun formatGrams(value: Double?): String =
    value?.let { Math.round(it).toString() } ?: "0"

private fun com.nutrilens.core.model.MealType.labelRes(): Int = when (this) {
    com.nutrilens.core.model.MealType.BREAKFAST -> UiR.string.meal_type_breakfast
    com.nutrilens.core.model.MealType.LUNCH -> UiR.string.meal_type_lunch
    com.nutrilens.core.model.MealType.DINNER -> UiR.string.meal_type_dinner
    com.nutrilens.core.model.MealType.SNACK -> UiR.string.meal_type_snack
    com.nutrilens.core.model.MealType.BEVERAGE -> UiR.string.meal_type_beverage
    com.nutrilens.core.model.MealType.OTHER -> UiR.string.meal_type_other
}

@Preview(showBackground = true)
@Composable
private fun EatingWindowCardPreview() {
    NutriLensTheme {
        Column(
            modifier = Modifier.padding(Dimens.spaceMedium),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceMedium),
        ) {
            EatingWindowCard(
                window = EatingWindow(
                    day = LocalDate.of(2026, 5, 1),
                    mealCount = 3,
                    firstMealLocalTime = LocalTime.of(8, 42),
                    lastMealLocalTime = LocalTime.of(19, 16),
                    eatingWindow = Duration.ofMinutes(634),
                    fastingPeriod = Duration.ofMinutes(806),
                ),
            )
            EatingWindowCard(window = EatingWindow.empty(LocalDate.of(2026, 5, 2)))
        }
    }
}
