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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.nutrilens.core.designsystem.R as UiR
import com.nutrilens.core.designsystem.component.EatingWindowBar
import com.nutrilens.core.designsystem.component.EmptyEatingWindowBar
import com.nutrilens.core.designsystem.component.NutriLensCard
import com.nutrilens.core.designsystem.format.asHoursAndMinutes
import com.nutrilens.core.designsystem.format.asWholeGrams
import com.nutrilens.core.designsystem.format.label
import com.nutrilens.core.designsystem.theme.Dimens
import com.nutrilens.core.designsystem.theme.NutriLensTheme
import com.nutrilens.core.model.EatingWindow
import com.nutrilens.core.model.Meal
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
                        eating.asHoursAndMinutes(),
                        fasting.asHoursAndMinutes(),
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
                    value = eating.asHoursAndMinutes(),
                )
                MetricRow(
                    label = stringResource(UiR.string.window_fasting_period),
                    value = fasting.asHoursAndMinutes(),
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
    val mealTypeLabel = meal.mealType.label()
    val time = meal.localDateTime.toLocalTime().format(timeFormatter)
    val mass = meal.totalMassGrams.asWholeGrams()

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

// --- formatting ----------------------------------------------------------

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

private val dayFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private fun LocalTime.toFractionOfDay(): Float =
    (hour * 60 + minute) / (24f * 60f)

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
