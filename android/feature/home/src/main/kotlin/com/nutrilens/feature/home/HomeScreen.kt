package com.nutrilens.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutrilens.core.designsystem.R as UiR
import com.nutrilens.core.designsystem.component.EmptyState
import com.nutrilens.core.designsystem.component.LoadingState
import com.nutrilens.core.designsystem.component.PrimaryButton
import com.nutrilens.core.designsystem.component.SectionHeader
import com.nutrilens.core.designsystem.theme.Dimens
import com.nutrilens.core.designsystem.theme.NutriLensTheme
import com.nutrilens.core.model.EatingWindow
import com.nutrilens.core.model.Meal
import com.nutrilens.core.designsystem.component.SyncStatusBanner
import com.nutrilens.core.designsystem.format.toMessage
import com.nutrilens.feature.home.component.EatingWindowCard
import com.nutrilens.feature.home.component.MealSummaryRow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun HomeRoute(
    onCaptureMeal: () -> Unit,
    onOpenMeal: (String) -> Unit,
    onViewTimeline: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HomeScreen(
        uiState = uiState,
        onCaptureMeal = onCaptureMeal,
        onOpenMeal = onOpenMeal,
        onViewTimeline = onViewTimeline,
        onRefresh = viewModel::onRefresh,
        modifier = modifier,
    )
}

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onCaptureMeal: () -> Unit,
    onOpenMeal: (String) -> Unit,
    onViewTimeline: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (uiState.isLoading) {
        LoadingState(
            contentDescription = stringResource(UiR.string.home_title),
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
                text = uiState.profile?.displayName?.let {
                    stringResource(UiR.string.home_greeting, it)
                } ?: stringResource(UiR.string.home_greeting_anonymous),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = Dimens.spaceMedium),
            )
        }

        // The banner appears only when there is something to say. A permanent
        // "all synced" badge is noise the user learns to ignore.
        uiState.syncStatus.toMessage(::relativeTimeLabel)?.let { sync ->
            item {
                SyncStatusBanner(
                    message = sync.message,
                    detail = sync.detail,
                    retryLabel = sync.retryLabel,
                    onRetry = onRefresh.takeIf { sync.retryLabel != null },
                    isProblem = sync.isProblem,
                )
            }
        }

        item {
            EatingWindowCard(window = uiState.todayWindow)
        }

        if (uiState.recentMeals.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(UiR.string.home_no_meals_title),
                    description = stringResource(UiR.string.home_no_meals_body),
                    action = {
                        PrimaryButton(
                            text = stringResource(UiR.string.home_capture_meal),
                            onClick = onCaptureMeal,
                        )
                    },
                )
            }
        } else {
            item {
                SectionHeader(
                    title = stringResource(UiR.string.home_recent_meals),
                    action = {
                        TextButton(onClick = onViewTimeline) {
                            Text(stringResource(UiR.string.home_view_all))
                        }
                    },
                )
            }
            items(items = uiState.recentMeals, key = { it.id }) { meal ->
                MealSummaryRow(meal = meal, onClick = { onOpenMeal(meal.id) })
            }
        }

        item {
            // Bottom padding so the floating action button never covers the
            // last row at any font scale.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Dimens.spaceExtraLarge * 2),
                verticalAlignment = Alignment.CenterVertically,
            ) { }
        }
    }
}

/**
 * "2 hours ago", or a date once that stops being useful.
 *
 * Relative wording is what a person actually wants from a last-synced line;
 * an absolute timestamp makes them do the arithmetic.
 */
private fun relativeTimeLabel(instant: Instant): String {
    val elapsed = Duration.between(instant, Instant.now())
    return when {
        elapsed.toMinutes() < 1 -> "just now"
        elapsed.toHours() < 1 -> "${elapsed.toMinutes()}m ago"
        elapsed.toDays() < 1 -> "${elapsed.toHours()}h ago"
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    NutriLensTheme {
        HomeScreen(
            uiState = HomeUiState(
                isLoading = false,
                todayWindow = EatingWindow.empty(LocalDate.of(2026, 5, 1)),
                recentMeals = emptyList<Meal>(),
            ),
            onCaptureMeal = {},
            onOpenMeal = {},
            onViewTimeline = {},
            onRefresh = {},
        )
    }
}
