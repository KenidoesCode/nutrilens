package com.nutrilens.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nutrilens.core.designsystem.theme.Dimens
import com.nutrilens.core.designsystem.theme.NutriLensTheme

/**
 * A day drawn as a 24-hour bar, with the eating window highlighted.
 *
 * The point of the product in one graphic: where in the day the eating
 * happened, and how much of it was fasting. The whole thing is one
 * accessibility node reading a sentence, because a screen reader announcing a
 * canvas would say nothing useful.
 *
 * @param startFraction where the first meal falls in the day, in `[0, 1]`
 * @param endFraction where the last meal falls, in `[0, 1]`
 */
@Composable
fun EatingWindowBar(
    startFraction: Float,
    endFraction: Float,
    startLabel: String,
    endLabel: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val windowColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { this.contentDescription = contentDescription },
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceSmall),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(BAR_HEIGHT_DP.dp),
        ) {
            val radius = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(color = trackColor, cornerRadius = radius)

            val start = startFraction.coerceIn(0f, 1f)
            val end = endFraction.coerceIn(start, 1f)
            val width = (end - start) * size.width

            // A zero-width window would be invisible; give a single-instant
            // window a minimum mark so the reader sees that something is there.
            val drawnWidth = width.coerceAtLeast(size.height)

            drawRoundRect(
                color = windowColor,
                topLeft = Offset(start * size.width, 0f),
                size = Size(drawnWidth.coerceAtMost(size.width - start * size.width), size.height),
                cornerRadius = radius,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = startLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = endLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** An empty-day variant, so the timeline does not silently omit fasted days. */
@Composable
fun EmptyEatingWindowBar(
    label: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { this.contentDescription = contentDescription },
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceSmall),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(BAR_HEIGHT_DP.dp),
        ) {
            drawRoundRect(
                color = trackColor,
                cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Dimens.spaceExtraSmall),
        )
    }
}

private const val BAR_HEIGHT_DP = 14

@Preview(showBackground = true)
@Composable
private fun EatingWindowBarPreview() {
    NutriLensTheme {
        Column(
            modifier = Modifier.padding(Dimens.spaceMedium),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceLarge),
        ) {
            EatingWindowBar(
                startFraction = 8.7f / 24f,
                endFraction = 19.27f / 24f,
                startLabel = "08:42",
                endLabel = "19:16",
                contentDescription = "Eating window from 08:42 to 19:16, 10 hours 34 minutes",
            )
            EmptyEatingWindowBar(
                label = "No meals logged",
                contentDescription = "No meals logged on this day",
            )
        }
    }
}
