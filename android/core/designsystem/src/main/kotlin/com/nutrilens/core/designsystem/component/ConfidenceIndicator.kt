package com.nutrilens.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import com.nutrilens.core.designsystem.theme.Dimens
import com.nutrilens.core.designsystem.theme.LocalConfidenceColors
import com.nutrilens.core.designsystem.theme.NutriLensTheme

/** The three confidence levels, mirrored from the domain model. */
enum class ConfidenceLevel { LOW, MEDIUM, HIGH }

/**
 * How sure the app is about a number.
 *
 * Colour is never the only signal: the level is always accompanied by its
 * label and a spoken description, so the meaning survives colour blindness and
 * a screen reader. The bar is decorative and is hidden from accessibility
 * services, which read [contentDescription] on the row instead of announcing a
 * meaningless graphic.
 */
@Composable
fun ConfidenceIndicator(
    level: ConfidenceLevel,
    score: Float,
    label: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalConfidenceColors.current
    val color = when (level) {
        ConfidenceLevel.HIGH -> colors.high
        ConfidenceLevel.MEDIUM -> colors.medium
        ConfidenceLevel.LOW -> colors.low
    }

    Column(
        modifier = modifier.clearAndSetSemantics {
            this.contentDescription = contentDescription
        },
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceExtraSmall),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = color,
            )
        }
        ConfidenceBar(fraction = score.coerceIn(0f, 1f), color = color)
    }
}

@Composable
private fun ConfidenceBar(fraction: Float, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.confidenceBarHeight)
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(Dimens.confidenceBarHeight)
                .clip(RoundedCornerShape(percent = 50))
                .background(color),
        )
    }
}

/**
 * The standing reminder that a figure is an estimate.
 *
 * Shown wherever a mass or volume appears. Overriding it is not offered: the
 * app must never let a person mistake a geometric estimate for a measurement.
 */
@Composable
fun EstimateDisclaimer(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = Dimens.spaceExtraSmall),
    )
}

@Preview(showBackground = true)
@Composable
private fun ConfidenceIndicatorPreview() {
    NutriLensTheme {
        Column(
            modifier = Modifier.padding(Dimens.spaceMedium),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceMedium),
        ) {
            ConfidenceIndicator(
                level = ConfidenceLevel.HIGH,
                score = 0.91f,
                label = "High confidence  91%",
                contentDescription = "High confidence, 91 percent",
            )
            ConfidenceIndicator(
                level = ConfidenceLevel.MEDIUM,
                score = 0.62f,
                label = "Medium confidence  62%",
                contentDescription = "Medium confidence, 62 percent",
            )
            ConfidenceIndicator(
                level = ConfidenceLevel.LOW,
                score = 0.31f,
                label = "Low confidence  31%",
                contentDescription = "Low confidence, 31 percent",
            )
        }
    }
}
