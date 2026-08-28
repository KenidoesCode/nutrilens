package com.nutrilens.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.nutrilens.core.designsystem.theme.Dimens
import com.nutrilens.core.designsystem.theme.NutriLensTheme

/**
 * What the app is doing with the user's meals, in plain language.
 *
 * Stateless by design: the caller resolves the wording, so this component holds
 * no opinion about which of the several sync states applies and the strings
 * stay localisable at the point of use.
 *
 * The retry action appears only when retrying could plausibly help. Offering
 * "Sync now" to a user with no connection is an invitation to press a button
 * that cannot work.
 */
@Composable
fun SyncStatusBanner(
    message: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
    isProblem: Boolean = false,
) {
    NutriLensCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(Dimens.spaceExtraSmall),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isProblem) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                detail?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (retryLabel != null && onRetry != null) {
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.defaultMinSize(minHeight = Dimens.minimumTouchTarget),
                ) {
                    Text(retryLabel)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SyncStatusBannerPreview() {
    NutriLensTheme {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceSmall),
        ) {
            SyncStatusBanner(
                message = "3 meals waiting to upload",
                retryLabel = "Sync now",
                onRetry = {},
            )
            SyncStatusBanner(
                message = "Offline. Your meals are saved on this device.",
                detail = "Last synced 2 hours ago",
            )
            SyncStatusBanner(
                message = "Some meals could not upload yet.",
                retryLabel = "Sync now",
                onRetry = {},
                isProblem = true,
            )
        }
    }
}
