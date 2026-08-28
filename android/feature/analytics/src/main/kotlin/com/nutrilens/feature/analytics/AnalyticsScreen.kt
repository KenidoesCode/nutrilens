package com.nutrilens.feature.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutrilens.core.designsystem.R as UiR
import com.nutrilens.core.designsystem.component.EmptyState
import com.nutrilens.core.designsystem.component.EatingWindowBar
import com.nutrilens.core.designsystem.component.EmptyEatingWindowBar
import com.nutrilens.core.designsystem.component.EstimateDisclaimer
import com.nutrilens.core.designsystem.component.LoadingState
import com.nutrilens.core.designsystem.component.NutriLensCard
import com.nutrilens.core.designsystem.component.SectionHeader
import com.nutrilens.core.designsystem.theme.Dimens
import com.nutrilens.core.designsystem.theme.NutriLensTheme
import com.nutrilens.core.model.EatingWindow
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/**
 * Eating patterns over time.
 *
 * Reports observations, never advice. There is no "target window" and no
 * judgement about whether a pattern is good: NutriLens is a tracking tool, and
 * telling someone their eating is wrong would be dietary guidance it is not
 * qualified to give.
 */
@Composable
fun AnalyticsRoute(
    modifier: Modifier = Modifier,
    viewModel: AnalyticsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AnalyticsScreen(
        uiState = uiState,
        onRangeSelected = viewModel::onRangeSelected,
        modifier = modifier,
    )
}

@Composable
fun AnalyticsScreen(
    uiState: AnalyticsUiState,
    onRangeSelected: (AnalyticsRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (uiState.isLoading) {
        LoadingState(
            contentDescription = stringResource(UiR.string.analytics_title),
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.spaceMedium),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceMedium),
    ) {
        item {
            Text(
                text = stringResource(UiR.string.analytics_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = Dimens.spaceMedium),
            )
        }

        item {
            RangeSelector(selected = uiState.range, onSelected = onRangeSelected)
        }

        if (!uiState.hasData) {
            item {
                EmptyState(
                    title = stringResource(UiR.string.analytics_title),
                    description = stringResource(UiR.string.analytics_empty),
                )
            }
            return@LazyColumn
        }

        val summary = uiState.summary ?: return@LazyColumn

        item {
            NutriLensCard {
                MetricRow(
                    label = stringResource(UiR.string.analytics_mean_window),
                    value = summary.meanEatingWindow?.formatted() ?: PLACEHOLDER,
                )
                MetricRow(
                    label = stringResource(UiR.string.analytics_mean_meals),
                    value = summary.meanMealsPerActiveDay
                        ?.let { "%.1f".format(it) } ?: PLACEHOLDER,
                )
                MetricRow(
                    label = stringResource(UiR.string.analytics_consistency),
                    // Null means "not enough days", which is different from a
                    // consistency of zero and must not be shown as 0%.
                    value = summary.windowConsistency
                        ?.let { "${(it * 100).roundToInt()}%" }
                        ?: stringResource(UiR.string.analytics_consistency_unavailable),
                )
                Text(
                    text = stringResource(UiR.string.analytics_consistency_explainer),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Dimens.spaceSmall),
                )
            }
        }

        item { SectionHeader(title = stringResource(UiR.string.timeline_title)) }

        items(items = summary.days, key = { it.day.toString() }) { day ->
            DayRow(day = day)
        }

        item {
            EstimateDisclaimer(text = stringResource(UiR.string.analytics_estimate_notice))
        }
        item { Column(modifier = Modifier.padding(bottom = Dimens.spaceExtraLarge * 2)) {} }
    }
}

@Composable
private fun RangeSelector(
    selected: AnalyticsRange,
    onSelected: (AnalyticsRange) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSmall),
    ) {
        AnalyticsRange.entries.forEach { range ->
            FilterChip(
                selected = range == selected,
                onClick = { onSelected(range) },
                label = { Text(stringResource(range.labelRes())) },
            )
        }
    }
}

@Composable
private fun DayRow(day: EatingWindow) {
    NutriLensCard {
        Text(
            text = day.day.format(dateFormatter),
            style = MaterialTheme.typography.titleMedium,
        )

        val first = day.firstMealLocalTime
        val last = day.lastMealLocalTime

        if (first == null || last == null || day.eatingWindow == null) {
            // A day with no window still appears: a gap in the record is itself
            // information the user should see, not something to hide.
            EmptyEatingWindowBar(
                label = if (day.hasMeals) {
                    stringResource(UiR.string.window_not_enough_data)
                } else {
                    stringResource(UiR.string.window_no_meals)
                },
                contentDescription = stringResource(
                    UiR.string.window_empty_content_description,
                    day.day.format(dateFormatter),
                ),
                modifier = Modifier.padding(top = Dimens.spaceSmall),
            )
        } else {
            EatingWindowBar(
                startFraction = first.fractionOfDay(),
                endFraction = last.fractionOfDay(),
                startLabel = first.format(timeFormatter),
                endLabel = last.format(timeFormatter),
                contentDescription = stringResource(
                    UiR.string.window_content_description,
                    first.format(timeFormatter),
                    last.format(timeFormatter),
                    day.eatingWindow.formatted(),
                    day.fastingPeriod?.formatted().orEmpty(),
                ),
                modifier = Modifier.padding(top = Dimens.spaceSmall),
            )
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.spaceExtraSmall)
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

private const val PLACEHOLDER = "—"

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

private val dateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private fun LocalTime.fractionOfDay(): Float = (hour * 60 + minute) / (24f * 60f)

@Composable
private fun Duration.formatted(): String = stringResource(
    UiR.string.window_duration_hours_minutes,
    toHours().toInt(),
    (toMinutes() % 60).toInt(),
)

private fun AnalyticsRange.labelRes(): Int = when (this) {
    AnalyticsRange.WEEK -> UiR.string.analytics_range_week
    AnalyticsRange.FORTNIGHT -> UiR.string.analytics_range_fortnight
    AnalyticsRange.MONTH -> UiR.string.analytics_range_month
}

@Preview(showBackground = true)
@Composable
private fun AnalyticsEmptyPreview() {
    NutriLensTheme {
        AnalyticsScreen(
            uiState = AnalyticsUiState(isLoading = false, summary = null),
            onRangeSelected = {},
        )
    }
}
