package com.nutrilens.feature.analysis

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutrilens.core.designsystem.R as UiR
import com.nutrilens.core.designsystem.component.EmptyState
import com.nutrilens.core.designsystem.component.ErrorState
import com.nutrilens.core.designsystem.component.EstimateDisclaimer
import com.nutrilens.core.designsystem.component.FoodOption
import com.nutrilens.core.designsystem.component.FoodPickerDialog
import com.nutrilens.core.designsystem.component.PrimaryButton
import com.nutrilens.core.designsystem.component.SecondaryButton
import com.nutrilens.core.designsystem.component.StepProgress
import com.nutrilens.core.designsystem.theme.Dimens
import com.nutrilens.core.designsystem.theme.NutriLensTheme
import com.nutrilens.core.designsystem.format.label
import com.nutrilens.core.model.AppError
import com.nutrilens.core.model.FoodCatalogItem
import com.nutrilens.core.model.MealType
import com.nutrilens.feature.analysis.component.AnalyzedItemCard
import com.nutrilens.feature.analysis.component.PortionAdjustDialog

@Composable
fun AnalysisRoute(
    onMealSaved: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AnalysisViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.savedMealId) {
        if (uiState.savedMealId != null) onMealSaved()
    }

    AnalysisScreen(
        uiState = uiState,
        onRetry = viewModel::analyze,
        onPortionCorrected = viewModel::onPortionCorrected,
        onItemRemoved = viewModel::onItemRemoved,
        onMealTypeChanged = viewModel::onMealTypeChanged,
        onFoodQueryChanged = viewModel::onFoodQueryChanged,
        onFoodSelected = viewModel::onFoodSelected,
        onFoodAdded = viewModel::onFoodAdded,
        onFreeTextFoodAdded = viewModel::onFreeTextFoodAdded,
        onFoodPickerDismissed = viewModel::onFoodPickerDismissed,
        onSave = viewModel::onSave,
        onCancel = onCancel,
        onErrorDismissed = viewModel::onErrorDismissed,
        modifier = modifier,
    )
}

@Composable
fun AnalysisScreen(
    uiState: AnalysisUiState,
    onRetry: () -> Unit,
    onPortionCorrected: (String, Double) -> Unit,
    onItemRemoved: (String) -> Unit,
    onMealTypeChanged: (MealType) -> Unit,
    onFoodQueryChanged: (String) -> Unit,
    onFoodSelected: (String, FoodCatalogItem) -> Unit,
    onFoodAdded: (FoodCatalogItem) -> Unit,
    onFreeTextFoodAdded: (String) -> Unit,
    onFoodPickerDismissed: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onErrorDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var adjustingItemId by remember { mutableStateOf<String?>(null) }

    // null = closed; a food key = replacing that item; ADD_NEW = adding one.
    var pickerTarget by remember { mutableStateOf<String?>(null) }

    when {
        uiState.isAnalysing -> AnalysingScreen(uiState.stage, modifier)

        uiState.error != null -> AnalysisErrorScreen(
            error = uiState.error,
            onRetry = onRetry,
            onLogManually = onCancel,
            onDismiss = onErrorDismissed,
            modifier = modifier,
        )

        uiState.detectedNothing -> EmptyState(
            title = stringResource(UiR.string.analysis_no_food_title),
            description = stringResource(UiR.string.analysis_no_food_body),
            modifier = modifier,
            action = {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceSmall)) {
                    PrimaryButton(
                        text = stringResource(UiR.string.analysis_retry),
                        onClick = onRetry,
                    )
                    SecondaryButton(
                        text = stringResource(UiR.string.capture_log_manually),
                        onClick = onCancel,
                    )
                }
            },
        )

        else -> {
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(horizontal = Dimens.spaceMedium),
                verticalArrangement = Arrangement.spacedBy(Dimens.spaceMedium),
            ) {
                item {
                    Text(
                        text = stringResource(UiR.string.analysis_results_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(top = Dimens.spaceMedium),
                    )
                }
                item {
                    // Shown above the results, not buried under them: the user
                    // reads this before they read a single number.
                    EstimateDisclaimer(
                        text = stringResource(UiR.string.analysis_estimate_notice),
                    )
                }
                item {
                    MealTypeSelector(
                        selected = uiState.mealType,
                        onSelected = onMealTypeChanged,
                    )
                }
                items(items = uiState.items, key = { it.id }) { item ->
                    AnalyzedItemCard(
                        item = item,
                        onAdjustPortion = { adjustingItemId = item.id },
                        onChangeFood = {
                            onFoodQueryChanged("")
                            pickerTarget = item.id
                        },
                        onRemove = { onItemRemoved(item.id) },
                    )
                }
                item {
                    SecondaryButton(
                        text = stringResource(UiR.string.analysis_add_food),
                        onClick = {
                            onFoodQueryChanged("")
                            pickerTarget = ADD_NEW_ITEM
                        },
                    )
                }
                item {
                    PrimaryButton(
                        text = stringResource(UiR.string.analysis_save_meal),
                        onClick = onSave,
                        enabled = uiState.canSave,
                        loading = uiState.isSaving,
                        modifier = Modifier.padding(top = Dimens.spaceMedium),
                    )
                }
                item {
                    SecondaryButton(
                        text = stringResource(UiR.string.action_cancel),
                        onClick = onCancel,
                        modifier = Modifier.padding(bottom = Dimens.spaceExtraLarge),
                    )
                }
            }

            adjustingItemId?.let { itemId ->
                uiState.items.firstOrNull { it.id == itemId }?.let { item ->
                    PortionAdjustDialog(
                        item = item,
                        onConfirm = { volume ->
                            onPortionCorrected(itemId, volume)
                            adjustingItemId = null
                        },
                        onDismiss = { adjustingItemId = null },
                    )
                }
            }

            pickerTarget?.let { target ->
                FoodPickerDialog(
                    query = uiState.foodQuery,
                    results = uiState.foodResults.map { it.toOption() },
                    onQueryChange = onFoodQueryChanged,
                    onSelect = { option ->
                        val food = uiState.foodResults.first { it.foodKey == option.key }
                        if (target == ADD_NEW_ITEM) onFoodAdded(food) else {
                            onFoodSelected(target, food)
                        }
                        pickerTarget = null
                    },
                    onUseFreeText = { name ->
                        // Only offered when adding: silently turning a detected
                        // food into unstructured text would lose its density.
                        if (target == ADD_NEW_ITEM) onFreeTextFoodAdded(name)
                        pickerTarget = null
                    },
                    onDismiss = {
                        onFoodPickerDismissed()
                        pickerTarget = null
                    },
                )
            }
        }
    }
}

/**
 * Which meal this is.
 *
 * Pre-selected from the time of day, because the guess is right most of the
 * time and a person logging breakfast should not have to say so. Always
 * changeable, because the guess is sometimes wrong and the meal type feeds the
 * timeline.
 */
@Composable
private fun MealTypeSelector(
    selected: MealType,
    onSelected: (MealType) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceSmall)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSmall),
        ) {
            MealType.entries.forEach { type ->
                FilterChip(
                    selected = type == selected,
                    onClick = { onSelected(type) },
                    label = { Text(type.label()) },
                )
            }
        }
    }
}

@Composable
private fun FoodCatalogItem.toOption(): FoodOption = FoodOption(
    key = foodKey,
    label = displayName,
    categoryLabel = category.label(),
)

/** Sentinel distinguishing "add a food" from "replace this item's food". */
private const val ADD_NEW_ITEM = "__add_new__"

@Composable
private fun AnalysingScreen(stage: AnalysisStage, modifier: Modifier = Modifier) {
    val steps = listOf(
        stringResource(UiR.string.analysis_step_detecting),
        stringResource(UiR.string.analysis_step_portions),
        stringResource(UiR.string.analysis_step_nutrition),
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Dimens.spaceLarge),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(UiR.string.analysis_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = Dimens.spaceLarge),
        )
        StepProgress(
            steps = steps,
            currentStep = stage.ordinal,
            contentDescription = stringResource(
                UiR.string.analysis_in_progress_content_description,
            ),
        )
    }
}

@Composable
private fun AnalysisErrorScreen(
    error: AppError,
    onRetry: () -> Unit,
    onLogManually: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Being offline is not a failure the user caused, and it has a different
    // way forward from a server error, so it gets its own screen.
    if (error == AppError.Offline) {
        EmptyState(
            title = stringResource(UiR.string.analysis_offline_title),
            description = stringResource(UiR.string.analysis_offline_body),
            modifier = modifier,
            action = {
                SecondaryButton(
                    text = stringResource(UiR.string.capture_log_manually),
                    onClick = onLogManually,
                )
            },
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Dimens.spaceLarge),
        verticalArrangement = Arrangement.Center,
    ) {
        ErrorState(
            message = stringResource(UiR.string.analysis_failed),
            retryLabel = stringResource(UiR.string.analysis_retry),
            onRetry = {
                onDismiss()
                onRetry()
            },
        )
        SecondaryButton(
            text = stringResource(UiR.string.capture_log_manually),
            onClick = onLogManually,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AnalysingPreview() {
    NutriLensTheme {
        AnalysingScreen(stage = AnalysisStage.ESTIMATING_PORTIONS)
    }
}
