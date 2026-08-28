package com.nutrilens.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.nutrilens.core.designsystem.component.PrimaryButton
import com.nutrilens.core.designsystem.component.SecondaryButton
import com.nutrilens.core.designsystem.theme.Dimens
import com.nutrilens.core.designsystem.theme.NutriLensTheme
import com.nutrilens.core.designsystem.R as UiR

/**
 * The first screen.
 *
 * It states plainly that the figures are estimates the user can correct, before
 * they have taken a single photograph. Setting that expectation up front is
 * part of the product being honest rather than impressive.
 */
@Composable
fun OnboardingRoute(
    onGetStarted: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.spaceLarge),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceMedium),
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = stringResource(UiR.string.onboarding_title),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(UiR.string.app_tagline),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(UiR.string.onboarding_subtitle),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(UiR.string.onboarding_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.weight(1f))

        PrimaryButton(
            text = stringResource(UiR.string.onboarding_get_started),
            onClick = onGetStarted,
        )
        SecondaryButton(
            text = stringResource(UiR.string.onboarding_sign_in),
            onClick = onSignIn,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingPreview() {
    NutriLensTheme {
        OnboardingRoute(onGetStarted = {}, onSignIn = {})
    }
}
