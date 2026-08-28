package com.nutrilens.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.nutrilens.core.designsystem.R
import com.nutrilens.core.designsystem.theme.Dimens

/** One selectable food, as the picker needs it. */
data class FoodOption(
    val key: String,
    val label: String,
    val categoryLabel: String,
)

/**
 * Choose a food from the catalog.
 *
 * Stateless: the caller owns the query and the results, so the dialog carries
 * no repository dependency and can be previewed and tested with plain data.
 *
 * The catalog is small and will not contain every food a person eats, so a
 * free-text escape hatch is always offered rather than trapping the user in a
 * list that does not include their meal. A food chosen from the catalog brings
 * its density with it; a free-text one falls back to a category default and is
 * labelled as such downstream.
 */
@Composable
fun FoodPickerDialog(
    query: String,
    results: List<FoodOption>,
    onQueryChange: (String) -> Unit,
    onSelect: (FoodOption) -> Unit,
    onUseFreeText: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.food_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceSmall)) {
                LabeledTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = stringResource(R.string.food_picker_search),
                    imeAction = ImeAction.Search,
                )

                if (results.isEmpty()) {
                    Text(
                        text = stringResource(R.string.food_picker_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        // Bounded so the dialog cannot grow past the screen at
                        // a large font scale.
                        modifier = Modifier.heightIn(max = MAX_LIST_HEIGHT_DP.dp),
                    ) {
                        items(items = results, key = { it.key }) { option ->
                            FoodRow(option = option, onClick = { onSelect(option) })
                        }
                    }
                }

                val trimmed = query.trim()
                if (trimmed.isNotEmpty() && results.none { it.label.equals(trimmed, true) }) {
                    TextButton(
                        onClick = { onUseFreeText(trimmed) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = Dimens.minimumTouchTarget),
                    ) {
                        Text(stringResource(R.string.food_picker_custom, trimmed))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun FoodRow(option: FoodOption, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Dimens.minimumTouchTarget)
            .clickable(onClick = onClick)
            .padding(vertical = Dimens.spaceExtraSmall)
            .semantics(mergeDescendants = true) { role = Role.Button },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = option.label, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = option.categoryLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val MAX_LIST_HEIGHT_DP = 240
