package com.nutrilens.feature.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutrilens.core.common.time.TimeProvider
import com.nutrilens.core.model.EatingPatternSummary
import com.nutrilens.core.model.repository.AnalyticsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

/** How far back the patterns view looks. */
enum class AnalyticsRange(val days: Long) {
    WEEK(7),
    FORTNIGHT(14),
    MONTH(30),
}

data class AnalyticsUiState(
    val isLoading: Boolean = true,
    val range: AnalyticsRange = AnalyticsRange.WEEK,
    val summary: EatingPatternSummary? = null,
) {
    val hasData: Boolean get() = (summary?.daysWithMeals ?: 0) > 0
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val analyticsRepository: AnalyticsRepository,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val selectedRange = MutableStateFlow(AnalyticsRange.WEEK)

    val uiState: StateFlow<AnalyticsUiState> = selectedRange
        // flatMapLatest so changing the range cancels the previous query rather
        // than leaving two collectors racing to set the same state.
        .flatMapLatest { range ->
            val today = LocalDate.now(timeProvider.currentZone())
            analyticsRepository
                .observePatternSummary(today.minusDays(range.days - 1), today)
                .map { summary ->
                    AnalyticsUiState(isLoading = false, range = range, summary = summary)
                }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = AnalyticsUiState(),
        )

    fun onRangeSelected(range: AnalyticsRange) {
        selectedRange.value = range
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
