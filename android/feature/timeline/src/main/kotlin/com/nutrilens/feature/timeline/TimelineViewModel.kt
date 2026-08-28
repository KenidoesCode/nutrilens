package com.nutrilens.feature.timeline

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutrilens.core.common.time.TimeProvider
import com.nutrilens.core.model.ChrononutritionCalculator
import com.nutrilens.core.model.Meal
import com.nutrilens.core.model.repository.MealRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** Meals grouped under the logical day they belong to. */
data class TimelineDay(
    val day: LocalDate,
    val meals: List<Meal>,
)

data class TimelineUiState(
    val isLoading: Boolean = true,
    val days: List<TimelineDay> = emptyList(),
) {
    val isEmpty: Boolean get() = !isLoading && days.isEmpty()
}

@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val mealRepository: MealRepository,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    val uiState: StateFlow<TimelineUiState> = mealRepository.observeMeals()
        .map { meals ->
            val zone = timeProvider.currentZone()
            TimelineUiState(
                isLoading = false,
                // Grouped by logical day, so a meal at 00:30 appears under the
                // evening it belonged to rather than opening a new day.
                days = ChrononutritionCalculator.groupByDay(meals, zone)
                    .toSortedMap(compareByDescending { it })
                    .map { (day, dayMeals) ->
                        TimelineDay(day = day, meals = dayMeals.sortedByDescending { it.consumedAt })
                    },
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = TimelineUiState(),
        )

    fun onRefresh() {
        viewModelScope.launch { mealRepository.refresh() }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

data class MealDetailUiState(
    val isLoading: Boolean = true,
    val meal: Meal? = null,
    val deleted: Boolean = false,
)

/** One stored meal, with the corrections a user can still make to it. */
@HiltViewModel
class MealDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val mealRepository: MealRepository,
) : ViewModel() {

    private val mealId: String = savedStateHandle.get<String>(ARG_MEAL_ID).orEmpty()

    val uiState: StateFlow<MealDetailUiState> = mealRepository.observeMeal(mealId)
        .map { meal -> MealDetailUiState(isLoading = false, meal = meal) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = MealDetailUiState(),
        )

    fun onPortionCorrected(itemId: String, volumeMl: Double) {
        viewModelScope.launch {
            mealRepository.updateItemPortion(mealId, itemId, volumeMl)
        }
    }

    fun onItemRenamed(itemId: String, displayName: String) {
        viewModelScope.launch { mealRepository.renameItem(mealId, itemId, displayName) }
    }

    fun onItemRemoved(itemId: String) {
        viewModelScope.launch { mealRepository.removeItem(mealId, itemId) }
    }

    fun onDeleteMeal() {
        viewModelScope.launch { mealRepository.deleteMeal(mealId) }
    }

    companion object {
        const val ARG_MEAL_ID = "mealId"
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
