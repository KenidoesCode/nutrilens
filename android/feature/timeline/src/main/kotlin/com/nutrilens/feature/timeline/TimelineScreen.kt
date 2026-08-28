package com.nutrilens.feature.timeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutrilens.core.designsystem.R as UiR
import com.nutrilens.core.designsystem.component.EmptyState
import com.nutrilens.core.designsystem.component.LoadingState
import com.nutrilens.core.designsystem.component.NutriLensCard
import com.nutrilens.core.designsystem.component.SectionHeader
import com.nutrilens.core.designsystem.theme.Dimens
import com.nutrilens.core.model.Meal
import com.nutrilens.core.model.MealType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

@Composable
fun TimelineRoute(
    onOpenMeal: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TimelineViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when {
        uiState.isLoading -> LoadingState(
            contentDescription = stringResource(UiR.string.timeline_title),
            modifier = modifier,
        )

        uiState.isEmpty -> EmptyState(
            title = stringResource(UiR.string.timeline_empty_title),
            description = stringResource(UiR.string.timeline_empty_body),
            modifier = modifier,
        )

        else -> LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = Dimens.spaceMedium),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceSmall),
        ) {
            uiState.days.forEach { day ->
                item(key = "header-${day.day}") {
                    SectionHeader(title = day.day.relativeLabel())
                }
                items(items = day.meals, key = { it.id }) { meal ->
                    TimelineMealRow(meal = meal, onClick = { onOpenMeal(meal.id) })
                }
            }
            item { Column(modifier = Modifier.padding(bottom = Dimens.spaceExtraLarge * 2)) {} }
        }
    }
}

@Composable
private fun TimelineMealRow(meal: Meal, onClick: () -> Unit) {
    val label = stringResource(meal.mealType.labelRes())
    val time = meal.localDateTime.toLocalTime().format(timeFormatter)
    val mass = meal.totalMassGrams.roundToInt()

    NutriLensCard(
        modifier = Modifier
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = Dimens.minimumTouchTarget)
            .semantics(mergeDescendants = true) {
                contentDescription = "$label, $time, $mass grams"
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(text = label, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = meal.items.joinToString(", ") { it.displayName },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(text = time, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LocalDate.relativeLabel(): String {
    val today = LocalDate.now()
    return when (this) {
        today -> stringResource(UiR.string.timeline_today)
        today.minusDays(1) -> stringResource(UiR.string.timeline_yesterday)
        else -> format(dateFormatter)
    }
}

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

private val dateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

internal fun MealType.labelRes(): Int = when (this) {
    MealType.BREAKFAST -> UiR.string.meal_type_breakfast
    MealType.LUNCH -> UiR.string.meal_type_lunch
    MealType.DINNER -> UiR.string.meal_type_dinner
    MealType.SNACK -> UiR.string.meal_type_snack
    MealType.BEVERAGE -> UiR.string.meal_type_beverage
    MealType.OTHER -> UiR.string.meal_type_other
}
