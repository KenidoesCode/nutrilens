package com.nutrilens.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutrilens.core.model.EatingWindow
import com.nutrilens.core.model.Meal
import com.nutrilens.core.model.UserProfile
import com.nutrilens.core.model.repository.AnalyticsRepository
import com.nutrilens.core.model.repository.AuthRepository
import com.nutrilens.core.model.repository.MealRepository
import com.nutrilens.core.model.repository.SyncRepository
import com.nutrilens.core.model.repository.SyncStatus
import com.nutrilens.core.model.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = true,
    val profile: UserProfile? = null,
    val todayWindow: EatingWindow = EatingWindow.empty(LocalDate.now()),
    val recentMeals: List<Meal> = emptyList(),
    val syncStatus: SyncStatus = SyncStatus(
        pendingCount = 0,
        state = SyncState.SYNCED,
        lastSyncedAt = null,
    ),
) {
    val hasMealsToday: Boolean get() = todayWindow.hasMeals
}

/**
 * The home screen.
 *
 * Every source is a local flow, so the screen renders from the database with no
 * network round trip and stays correct while offline. Nothing here blocks on a
 * request.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    authRepository: AuthRepository,
    analyticsRepository: AnalyticsRepository,
    private val mealRepository: MealRepository,
    private val syncRepository: SyncRepository,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        authRepository.currentUser,
        analyticsRepository.observeTodayWindow(),
        mealRepository.observeMeals().map { it.take(RECENT_MEAL_COUNT) },
        syncRepository.observeSyncState(),
    ) { profile, window, recent, syncStatus ->
        HomeUiState(
            isLoading = false,
            profile = profile,
            todayWindow = window,
            recentMeals = recent,
            syncStatus = syncStatus,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = HomeUiState(),
    )

    /** Pull-to-refresh: ask for a sync, but never block the screen on it. */
    fun onRefresh() {
        viewModelScope.launch {
            syncRepository.requestSync()
            mealRepository.refresh()
        }
    }

    private companion object {
        const val RECENT_MEAL_COUNT = 5
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
