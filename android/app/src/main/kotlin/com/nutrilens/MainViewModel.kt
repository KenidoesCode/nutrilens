package com.nutrilens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutrilens.core.model.repository.AuthRepository
import com.nutrilens.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Where the app should open, given whether a session exists. */
data class MainUiState(
    val startDestination: String = Destination.Onboarding.route,
)

/**
 * Decides the first screen.
 *
 * Reads the stored session rather than waiting on a profile fetch, so a signed-in
 * user with no network still lands on their timeline instead of a sign-in form.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    authRepository: AuthRepository,
) : ViewModel() {

    val uiState: StateFlow<MainUiState> = authRepository.isAuthenticated
        .map { authenticated ->
            MainUiState(
                startDestination = if (authenticated) {
                    Destination.Home.route
                } else {
                    Destination.Onboarding.route
                },
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = MainUiState(),
        )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
