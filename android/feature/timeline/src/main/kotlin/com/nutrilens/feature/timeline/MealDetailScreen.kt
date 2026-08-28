package com.nutrilens.feature.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutrilens.core.designsystem.R as UiR
import com.nutrilens.core.designsystem.component.EstimateDisclaimer
import com.nutrilens.core.designsystem.component.FoodOption
import com.nutrilens.core.designsystem.component.FoodPickerDialog
import com.nutrilens.core.designsystem.component.LoadingState
import com.nutrilens.core.designsystem.component.NutriLensCard
import com.nutrilens.core.designsystem.component.SecondaryButton
import com.nutrilens.core.designsystem.theme.Dimens
import com.nutrilens.core.designsystem.format.asWholeGrams
import com.nutrilens.core.designsystem.format.label
import com.nutrilens.core.model.MealItem

/**
 * One stored meal.
 *
 * Corrections remain available after the fact: a person often realises they
 * mis-estimated a portion later, and an unchangeable record would push them to
 * either live with a wrong number or delete the meal entirely.
 */
@Composable
fun MealDetailRoute(
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MealDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmingDelete by remember { mutableStateOf(false) }
    var pickerItemId by remember { mutableStateOf<String?>(null) }

    // A deleted meal has no detail to show, so leave rather than render a blank.
    LaunchedEffect(uiState.isLoading, uiState.meal) {
        if (!uiState.isLoading && uiState.meal == null) onBack()
    }

    if (uiState.isLoading) {
        LoadingState(contentDescription = "", modifier = modifier)
        return
    }

    val meal = uiState.meal ?: return

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.spaceMedium),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceMedium),
    ) {
        item {
            Text(
                text = meal.mealType.label(),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = Dimens.spaceMedium),
            )
        }
        item {
            EstimateDisclaimer(text = stringResource(UiR.string.analysis_estimate_notice))
        }
        items(items = meal.items, key = { it.id }) { item ->
            MealItemRow(
                item = item,
                onChangeFood = {
                    viewModel.onFoodQueryChanged("")
                    pickerItemId = item.id
                },
                onRemove = { viewModel.onItemRemoved(item.id) },
            )
        }
        item {
            NutriLensCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(UiR.string.analytics_energy),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = meal.totalEnergyKcal?.let {
                            stringResource(
                                UiR.string.analytics_energy_value,
                                it.asWholeGrams(),
                            )
                        } ?: "—",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item {
            SecondaryButton(
                text = stringResource(UiR.string.action_delete),
                onClick = { confirmingDelete = true },
                modifier = Modifier.padding(bottom = Dimens.spaceExtraLarge),
            )
        }
    }

    pickerItemId?.let { itemId ->
        FoodPickerDialog(
            query = uiState.foodQuery,
            results = uiState.foodResults.map {
                FoodOption(key = it.foodKey, label = it.displayName, categoryLabel = it.category.label())
            },
            onQueryChange = viewModel::onFoodQueryChanged,
            onSelect = { option ->
                viewModel.onItemRenamed(itemId, option.label)
                pickerItemId = null
            },
            // Renaming a stored item to free text would leave it without a
            // density from the catalog, so only catalog foods are offered here.
            onUseFreeText = { pickerItemId = null },
            onDismiss = {
                viewModel.onFoodPickerDismissed()
                pickerItemId = null
            },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(UiR.string.action_delete)) },
            text = { Text(stringResource(UiR.string.settings_delete_account_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        viewModel.onDeleteMeal()
                        onDeleted()
                    },
                ) {
                    Text(stringResource(UiR.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(UiR.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun MealItemRow(
    item: MealItem,
    onChangeFood: () -> Unit,
    onRemove: () -> Unit,
) {
    NutriLensCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(text = item.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(
                        UiR.string.item_estimated_mass,
                        item.estimatedMassGrams.asWholeGrams(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                )
                if (item.wasUserCorrected) {
                    Text(
                        text = stringResource(UiR.string.item_corrected_by_you),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Row {
                TextButton(
                    onClick = onChangeFood,
                    modifier = Modifier.defaultMinSize(minHeight = Dimens.minimumTouchTarget),
                ) {
                    Text(stringResource(UiR.string.item_rename))
                }
                TextButton(
                    onClick = onRemove,
                    modifier = Modifier.defaultMinSize(minHeight = Dimens.minimumTouchTarget),
                ) {
                    Text(stringResource(UiR.string.item_remove))
                }
            }
        }
    }
}
