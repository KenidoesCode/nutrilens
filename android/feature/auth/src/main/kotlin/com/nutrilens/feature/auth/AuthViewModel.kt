package com.nutrilens.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutrilens.core.model.AppError
import com.nutrilens.core.model.Outcome
import com.nutrilens.core.model.repository.AuthRepository
import com.nutrilens.core.model.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which form is showing. */
enum class AuthMode { SIGN_IN, SIGN_UP }

/**
 * Field-level validation problems.
 *
 * Modelled as data rather than pre-rendered strings so the view model stays
 * free of resource lookups and the messages localise at the point of display.
 */
enum class FieldError {
    EMAIL_REQUIRED,
    EMAIL_INVALID,
    PASSWORD_REQUIRED,
    PASSWORD_TOO_SHORT,
}

data class AuthUiState(
    val mode: AuthMode = AuthMode.SIGN_IN,
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val emailError: FieldError? = null,
    val passwordError: FieldError? = null,
    val submitError: AppError? = null,
    val isSubmitting: Boolean = false,
    val isAuthenticated: Boolean = false,
) {
    val canSubmit: Boolean
        get() = !isSubmitting && email.isNotBlank() && password.isNotBlank()
}

/**
 * Sign-in and sign-up.
 *
 * Validation happens locally before a request is made -- an empty field should
 * not cost a round trip -- but the server remains the authority on password
 * strength, so its rejection is surfaced rather than second-guessed.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun onEmailChange(value: String) {
        // Clearing the error as the user types avoids scolding them mid-fix.
        _uiState.update { it.copy(email = value, emailError = null, submitError = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, passwordError = null, submitError = null) }
    }

    fun onDisplayNameChange(value: String) {
        _uiState.update { it.copy(displayName = value) }
    }

    fun onModeChange(mode: AuthMode) {
        _uiState.update {
            it.copy(mode = mode, emailError = null, passwordError = null, submitError = null)
        }
    }

    fun onSubmit() {
        val state = _uiState.value
        val emailError = validateEmail(state.email)
        val passwordError = validatePassword(state.password, state.mode)

        if (emailError != null || passwordError != null) {
            _uiState.update {
                it.copy(emailError = emailError, passwordError = passwordError)
            }
            return
        }

        _uiState.update { it.copy(isSubmitting = true, submitError = null) }

        viewModelScope.launch {
            val language = settingsRepository.language.first()
            val outcome = when (state.mode) {
                AuthMode.SIGN_IN -> authRepository.login(state.email, state.password)
                AuthMode.SIGN_UP -> authRepository.register(
                    email = state.email,
                    password = state.password,
                    displayName = state.displayName.takeIf { it.isNotBlank() },
                    language = language,
                )
            }

            _uiState.update { current ->
                when (outcome) {
                    is Outcome.Success -> current.copy(
                        isSubmitting = false,
                        isAuthenticated = true,
                        // The password is dropped from state the moment it is
                        // no longer needed, so it cannot be captured in a state
                        // dump or survive in memory longer than necessary.
                        password = "",
                    )

                    is Outcome.Failure -> current.copy(
                        isSubmitting = false,
                        submitError = outcome.error,
                        password = "",
                    )
                }
            }
        }
    }

    fun onErrorDismissed() {
        _uiState.update { it.copy(submitError = null) }
    }

    private fun validateEmail(email: String): FieldError? = when {
        email.isBlank() -> FieldError.EMAIL_REQUIRED
        // Deliberately permissive: the server validates properly, and a strict
        // client-side pattern reliably rejects somebody's legitimate address.
        !email.contains('@') || !email.substringAfter('@').contains('.') ->
            FieldError.EMAIL_INVALID

        else -> null
    }

    private fun validatePassword(password: String, mode: AuthMode): FieldError? = when {
        password.isBlank() -> FieldError.PASSWORD_REQUIRED
        // Only enforced on sign-up: an existing account may predate the rule,
        // and refusing to even attempt their login would lock them out.
        mode == AuthMode.SIGN_UP && password.length < MIN_PASSWORD_LENGTH ->
            FieldError.PASSWORD_TOO_SHORT

        else -> null
    }

    companion object {
        const val MIN_PASSWORD_LENGTH = 10
    }
}
