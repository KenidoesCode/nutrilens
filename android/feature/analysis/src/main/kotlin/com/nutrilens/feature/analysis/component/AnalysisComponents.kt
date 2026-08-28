package com.nutrilens.feature.analysis.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.nutrilens.core.designsystem.R as UiR
import com.nutrilens.core.designsystem.component.ConfidenceIndicator
import com.nutrilens.core.designsystem.component.NutriLensCard
import com.nutrilens.core.designsystem.theme.Dimens
import com.nutrilens.core.designsystem.theme.NutriLensTheme
import com.nutrilens.core.designsystem.format.asWholeGrams
import com.nutrilens.core.designsystem.format.label
import com.nutrilens.core.designsystem.format.toUiLevel
import com.nutrilens.core.model.FoodCategory
import com.nutrilens.core.model.MealItem
import kotlin.math.roundToInt

/**
 * One detected food.
 *
 * Shows the mass, the confidence behind it, and -- when the density came from a
 * category default rather than the food itself -- says so. A user deciding
 * whether to trust "155 g" deserves to know the estimate rests on a generic
 * assumption.
 */
@Composable
fun AnalyzedItemCard(
    item: MealItem,
    onAdjustPortion: () -> Unit,
    onChangeFood: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val confidencePercent = (item.recognitionConfidence * 100).roundToInt()
    val bandLabel = item.recognitionBand.label()

    NutriLensCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.padding(end = Dimens.spaceSmall)) {
                Text(text = item.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = item.category.label(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(
                    UiR.string.item_estimated_mass,
                    item.estimatedMassGrams.asWholeGrams(),
                ),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        ConfidenceIndicator(
            level = item.recognitionBand.toUiLevel(),
            score = item.recognitionConfidence,
            label = "$bandLabel  $confidencePercent%",
            contentDescription = stringResource(
                UiR.string.item_confidence_content_description,
                bandLabel,
                confidencePercent,
            ),
            modifier = Modifier.padding(top = Dimens.spaceSmall),
        )

        if (item.isFallbackDensity) {
            Text(
                text = stringResource(UiR.string.item_fallback_density_notice),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = Dimens.spaceExtraSmall),
            )
        }

        if (item.wasUserCorrected) {
            Text(
                text = stringResource(UiR.string.item_corrected_by_you),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = Dimens.spaceExtraSmall),
            )
        }

        // Horizontally scrollable so three actions still fit at a large font
        // scale rather than wrapping into an unreadable stack.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = onAdjustPortion,
                modifier = Modifier.defaultMinSize(minHeight = Dimens.minimumTouchTarget),
            ) {
                Text(stringResource(UiR.string.item_adjust_portion))
            }
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

/**
 * Portion correction.
 *
 * The resulting mass updates live as the slider moves, so the user is
 * correcting the quantity they actually care about rather than guessing at
 * millilitres in the abstract.
 */
@Composable
fun PortionAdjustDialog(
    item: MealItem,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var volumeMl by remember(item.id) {
        mutableFloatStateOf(item.estimatedVolumeMl.toFloat())
    }
    val resultingMass = (volumeMl * item.densityGramsPerMl).roundToInt()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(UiR.string.portion_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceSmall)) {
                // The original estimate stays on screen so the user can see
                // what they are changing it from, and change their mind.
                Text(
                    text = stringResource(UiR.string.portion_estimated_volume) + ": " +
                        stringResource(
                            UiR.string.item_volume_millilitres,
                            item.estimatedVolumeMl.roundToInt().toString(),
                        ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        UiR.string.item_volume_millilitres,
                        volumeMl.roundToInt().toString(),
                    ),
                    style = MaterialTheme.typography.titleLarge,
                )
                Slider(
                    value = volumeMl,
                    onValueChange = { volumeMl = it },
                    valueRange = MIN_VOLUME_ML..MAX_VOLUME_ML,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "${volumeMl.roundToInt()} millilitres"
                        },
                )
                Text(
                    text = stringResource(
                        UiR.string.portion_resulting_mass,
                        resultingMass.toString(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(volumeMl.toDouble()) }) {
                Text(stringResource(UiR.string.portion_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(UiR.string.portion_cancel))
            }
        },
    )
}

private const val MIN_VOLUME_ML = 5f
private const val MAX_VOLUME_ML = 1500f

@Preview(showBackground = true)
@Composable
private fun AnalyzedItemCardPreview() {
    NutriLensTheme {
        Column(
            modifier = Modifier.padding(Dimens.spaceMedium),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceMedium),
        ) {
            AnalyzedItemCard(
                item = MealItem(
                    id = "1",
                    displayName = "Rice",
                    category = FoodCategory.SOLID,
                    estimatedVolumeMl = 180.0,
                    estimatedMassGrams = 153.0,
                    densityGramsPerMl = 0.85,
                    densitySource = "nutrilens-food-catalog@2024.1",
                    recognitionConfidence = 0.62f,
                    portionConfidence = 0.65f,
                ),
                onAdjustPortion = {},
                onChangeFood = {},
                onRemove = {},
            )
            AnalyzedItemCard(
                item = MealItem(
                    id = "2",
                    displayName = "Vegetable curry",
                    category = FoodCategory.SEMISOLID,
                    estimatedVolumeMl = 95.0,
                    estimatedMassGrams = 93.1,
                    densityGramsPerMl = 0.98,
                    densitySource = "category-default@2024.1",
                    isFallbackDensity = true,
                    recognitionConfidence = 0.41f,
                    portionConfidence = 0.42f,
                ),
                onAdjustPortion = {},
                onChangeFood = {},
                onRemove = {},
            )
        }
    }
}
