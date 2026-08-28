package com.nutrilens.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutrilens.core.designsystem.component.ErrorState
import com.nutrilens.core.designsystem.component.LabeledTextField
import com.nutrilens.core.designsystem.component.PrimaryButton
import com.nutrilens.core.designsystem.theme.Dimens
import com.nutrilens.core.designsystem.theme.NutriLensTheme
import com.nutrilens.core.model.AppError
import com.nutrilens.core.designsystem.R as UiR

/**
 * Sign-in and sign-up.
 *
 * The stateful route is separated from the stateless screen so the screen can
 * be previewed and tested with plain data and no Hilt graph.
 */
@Composable
fun AuthRoute(
    onAuthenticated: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.isAuthenticated) {
        if (uiState.isAuthenticated) onAuthenticated()
    }

    AuthScreen(
        uiState = uiState,
        onEmailChange = viewModel::onEmailChange,
        onPasswordChange = viewModel::onPasswordChange,
        onDisplayNameChange = viewModel::onDisplayNameChange,
        onModeChange = viewModel::onModeChange,
        onSubmit = viewModel::onSubmit,
        onErrorDismissed = viewModel::onErrorDismissed,
        modifier = modifier,
    )
}

@Composable
fun AuthScreen(
    uiState: AuthUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onModeChange: (AuthMode) -> Unit,
    onSubmit: () -> Unit,
    onErrorDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // The form scrolls and lifts above the keyboard so the submit
            // button stays reachable on a short screen at a large font size.
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(Dimens.spaceLarge),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceMedium),
    ) {
        Text(
            text = stringResource(
                if (uiState.mode == AuthMode.SIGN_IN) {
                    UiR.string.auth_sign_in_title
                } else {
                    UiR.string.auth_sign_up_title
                },
            ),
            style = MaterialTheme.typography.headlineMedium,
        )

        Spacer(modifier = Modifier.height(Dimens.spaceSmall))

        LabeledTextField(
            value = uiState.email,
            onValueChange = onEmailChange,
            label = stringResource(UiR.string.auth_email),
            error = uiState.emailError?.let { stringResource(it.messageRes()) },
            keyboardType = KeyboardType.Email,
            enabled = !uiState.isSubmitting,
        )

        if (uiState.mode == AuthMode.SIGN_UP) {
            LabeledTextField(
                value = uiState.displayName,
                onValueChange = onDisplayNameChange,
                label = stringResource(UiR.string.auth_display_name),
                enabled = !uiState.isSubmitting,
            )
        }

        LabeledTextField(
            value = uiState.password,
            onValueChange = onPasswordChange,
            label = stringResource(UiR.string.auth_password),
            error = uiState.passwordError?.let {
                if (it == FieldError.PASSWORD_TOO_SHORT) {
                    stringResource(it.messageRes(), AuthViewModel.MIN_PASSWORD_LENGTH)
                } else {
                    stringResource(it.messageRes())
                }
            },
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
            isPassword = true,
            enabled = !uiState.isSubmitting,
        )

        uiState.submitError?.let { error ->
            ErrorState(
                message = stringResource(error.messageRes()),
                retryLabel = stringResource(UiR.string.error_generic_retry),
                onRetry = onErrorDismissed,
            )
        }

        PrimaryButton(
            text = stringResource(
                if (uiState.mode == AuthMode.SIGN_IN) {
                    UiR.string.auth_sign_in
                } else {
                    UiR.string.auth_sign_up
                },
            ),
            onClick = onSubmit,
            enabled = uiState.canSubmit,
            loading = uiState.isSubmitting,
        )

        TextButton(
            onClick = {
                onModeChange(
                    if (uiState.mode == AuthMode.SIGN_IN) AuthMode.SIGN_UP else AuthMode.SIGN_IN,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(
                    if (uiState.mode == AuthMode.SIGN_IN) {
                        UiR.string.auth_switch_to_sign_up
                    } else {
                        UiR.string.auth_switch_to_sign_in
                    },
                ),
            )
        }
    }
}

/** Field errors resolve to their own message, localised at display time. */
internal fun FieldError.messageRes(): Int = when (this) {
    FieldError.EMAIL_REQUIRED -> UiR.string.auth_error_email_required
    FieldError.EMAIL_INVALID -> UiR.string.auth_error_email_invalid
    FieldError.PASSWORD_REQUIRED -> UiR.string.auth_error_password_required
    FieldError.PASSWORD_TOO_SHORT -> UiR.string.auth_error_password_too_short
}

/**
 * Domain errors resolve to a message the user can act on.
 *
 * Every case is named. A catch-all "something went wrong" would collapse
 * "you are offline" and "your password is wrong" into the same dead end.
 */
internal fun AppError.messageRes(): Int = when (this) {
    AppError.Offline -> UiR.string.error_offline
    AppError.Timeout -> UiR.string.error_timeout
    AppError.InvalidCredentials -> UiR.string.auth_error_invalid_credentials
    AppError.SessionExpired -> UiR.string.error_session_expired
    AppError.EmailAlreadyRegistered -> UiR.string.auth_error_email_taken
    is AppError.WeakPassword -> UiR.string.auth_error_weak_password
    is AppError.InvalidImage -> UiR.string.error_invalid_image
    AppError.ImageTooLarge -> UiR.string.error_image_too_large
    is AppError.AnalysisFailed -> UiR.string.analysis_failed
    is AppError.RateLimited -> UiR.string.error_rate_limited
    AppError.NotFound -> UiR.string.error_not_found
    is AppError.ServerError -> UiR.string.error_server
    is AppError.DeviceError -> UiR.string.error_device
}

@Preview(showBackground = true)
@Composable
private fun AuthScreenPreview() {
    NutriLensTheme {
        AuthScreen(
            uiState = AuthUiState(mode = AuthMode.SIGN_UP, email = "person@example.com"),
            onEmailChange = {},
            onPasswordChange = {},
            onDisplayNameChange = {},
            onModeChange = {},
            onSubmit = {},
            onErrorDismissed = {},
        )
    }
}
