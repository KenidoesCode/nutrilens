package com.nutrilens.feature.timeline

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutrilens.core.common.time.TimeProvider
import com.nutrilens.core.model.ChrononutritionCalculator
import com.nutrilens.core.model.FoodCatalogItem
import com.nutrilens.core.model.Meal
import com.nutrilens.core.model.repository.FoodCatalogRepository
import com.nutrilens.core.model.repository.MealRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
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
    val foodQuery: String = "",
    val foodResults: List<FoodCatalogItem> = emptyList(),
)

/** One stored meal, with the corrections a user can still make to it. */
@HiltViewModel
class MealDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val mealRepository: MealRepository,
    private val foodCatalogRepository: FoodCatalogRepository,
) : ViewModel() {

    private val mealId: String = savedStateHandle.get<String>(ARG_MEAL_ID).orEmpty()

    private val foodSearch = MutableStateFlow(FoodSearchState())

    val uiState: StateFlow<MealDetailUiState> = combine(
        mealRepository.observeMeal(mealId),
        foodSearch,
    ) { meal, search ->
        MealDetailUiState(
            isLoading = false,
            meal = meal,
            foodQuery = search.query,
            foodResults = search.results,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = MealDetailUiState(),
    )

    /** Cancelled per keystroke so a stale search cannot overwrite a newer one. */
    private var searchJob: Job? = null

    fun onFoodQueryChanged(query: String) {
        foodSearch.update { it.copy(query = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            foodCatalogRepository.search(query).collect { results ->
                foodSearch.update { it.copy(results = results) }
            }
        }
    }

    fun onFoodPickerDismissed() {
        searchJob?.cancel()
        foodSearch.value = FoodSearchState()
    }

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

/** Transient picker state, which no repository owns. */
private data class FoodSearchState(
    val query: String = "",
    val results: List<FoodCatalogItem> = emptyList(),
)
