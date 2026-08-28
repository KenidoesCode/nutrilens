package com.nutrilens.feature.analysis

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutrilens.core.common.time.TimeProvider
import com.nutrilens.core.model.AnalysisResult
import com.nutrilens.core.model.AppError
import com.nutrilens.core.model.Meal
import com.nutrilens.core.model.MealItem
import com.nutrilens.core.model.MealType
import com.nutrilens.core.model.Outcome
import com.nutrilens.core.model.FoodCatalogItem
import com.nutrilens.core.model.FoodCategory
import com.nutrilens.core.model.repository.AnalysisRepository
import com.nutrilens.core.model.repository.FoodCatalogRepository
import com.nutrilens.core.model.repository.MealRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject

/** Stages the user sees while a photograph is analysed. */
enum class AnalysisStage { DETECTING, ESTIMATING_PORTIONS, CALCULATING_NUTRITION }

data class AnalysisUiState(
    val imagePath: String = "",
    val isAnalysing: Boolean = true,
    val stage: AnalysisStage = AnalysisStage.DETECTING,
    val result: AnalysisResult? = null,
    val items: List<MealItem> = emptyList(),
    val mealType: MealType = MealType.OTHER,
    val error: AppError? = null,
    val isSaving: Boolean = false,
    val savedMealId: String? = null,
    val foodQuery: String = "",
    val foodResults: List<FoodCatalogItem> = emptyList(),
) {
    val detectedNothing: Boolean
        get() = result != null && items.isEmpty()

    val canSave: Boolean
        get() = items.isNotEmpty() && !isSaving
}

/**
 * Analysis results, and the corrections a user makes to them.
 *
 * Editing happens entirely in memory until the user saves. That is deliberate:
 * the analysis is a proposal, and nothing enters the meal history until a
 * person has agreed to it.
 */
@HiltViewModel
class AnalysisViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val analysisRepository: AnalysisRepository,
    private val mealRepository: MealRepository,
    private val foodCatalogRepository: FoodCatalogRepository,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val imagePath: String = savedStateHandle
        .get<String>(ARG_IMAGE_PATH)
        ?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) }
        .orEmpty()

    private val _uiState = MutableStateFlow(AnalysisUiState(imagePath = imagePath))
    val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()

    /** Cancelled on each keystroke so a stale search cannot overwrite a newer one. */
    private var searchJob: Job? = null

    init {
        analyze()
    }

    fun analyze() {
        if (imagePath.isEmpty()) {
            _uiState.update {
                it.copy(
                    isAnalysing = false,
                    error = AppError.InvalidImage("No photograph was provided."),
                )
            }
            return
        }

        _uiState.update {
            it.copy(isAnalysing = true, error = null, stage = AnalysisStage.DETECTING)
        }

        viewModelScope.launch {
            when (val outcome = analysisRepository.analyzeMealImage(imagePath)) {
                is Outcome.Success -> {
                    val localTime = LocalDateTime.ofInstant(
                        timeProvider.now(),
                        timeProvider.currentZone(),
                    ).toLocalTime()

                    _uiState.update {
                        it.copy(
                            isAnalysing = false,
                            stage = AnalysisStage.CALCULATING_NUTRITION,
                            result = outcome.data,
                            items = outcome.data.items.map { item ->
                                item.toMealItem(id = UUID.randomUUID().toString())
                            },
                            mealType = MealType.suggestFor(localTime),
                        )
                    }
                }

                is Outcome.Failure -> _uiState.update {
                    it.copy(isAnalysing = false, error = outcome.error)
                }
            }
        }
    }

    fun onPortionCorrected(itemId: String, volumeMl: Double) {
        if (volumeMl <= 0 || volumeMl > MAX_VOLUME_ML) return
        _uiState.update { state ->
            state.copy(
                items = state.items.map { item ->
                    if (item.id == itemId) item.withCorrectedVolume(volumeMl) else item
                },
            )
        }
    }

    fun onItemRemoved(itemId: String) {
        _uiState.update { state ->
            state.copy(items = state.items.filterNot { it.id == itemId })
        }
    }

    fun onMealTypeChanged(mealType: MealType) {
        _uiState.update { it.copy(mealType = mealType) }
    }

    // --- food picker -----------------------------------------------------

    fun onFoodQueryChanged(query: String) {
        _uiState.update { it.copy(foodQuery = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            foodCatalogRepository.search(query).collect { results ->
                _uiState.update { it.copy(foodResults = results) }
            }
        }
    }

    fun onFoodPickerDismissed() {
        searchJob?.cancel()
        _uiState.update { it.copy(foodQuery = "", foodResults = emptyList()) }
    }

    /**
     * Replace a detected food with one the user chose.
     *
     * The density comes with the catalog entry, so the mass is recomputed here
     * rather than carried over: the previous food's density describes a food
     * the user has just said this is not.
     */
    fun onFoodSelected(itemId: String, food: FoodCatalogItem) {
        _uiState.update { state ->
            state.copy(
                items = state.items.map { item ->
                    if (item.id != itemId) {
                        item
                    } else {
                        val mass = item.estimatedVolumeMl * food.densityGramsPerMl
                        item.copy(
                            displayName = food.displayName,
                            foodKey = food.foodKey,
                            category = food.category,
                            densityGramsPerMl = food.densityGramsPerMl,
                            densitySource = CATALOG_SOURCE,
                            isFallbackDensity = false,
                            estimatedMassGrams = mass,
                            energyKcal = food.energyKcalPer100g?.let { it * mass / 100.0 },
                            // The previous food's macros describe a different
                            // food; carrying them over would be worse than
                            // admitting they are unknown.
                            proteinGrams = null,
                            carbohydrateGrams = null,
                            fatGrams = null,
                            originalDisplayName = item.originalDisplayName ?: item.displayName,
                            originalMassGrams = item.originalMassGrams ?: item.estimatedMassGrams,
                            wasUserCorrected = true,
                        )
                    }
                },
            )
        }
        onFoodPickerDismissed()
    }

    /** Add a food the recognition missed entirely. */
    fun onFoodAdded(food: FoodCatalogItem) {
        _uiState.update { state ->
            state.copy(
                items = state.items + MealItem.userEntered(
                    id = UUID.randomUUID().toString(),
                    displayName = food.displayName,
                    category = food.category,
                    volumeMl = DEFAULT_ADDED_VOLUME_ML,
                    densityGramsPerMl = food.densityGramsPerMl,
                    densitySource = CATALOG_SOURCE,
                    foodKey = food.foodKey,
                    energyKcalPer100g = food.energyKcalPer100g,
                ),
            )
        }
        onFoodPickerDismissed()
    }

    /**
     * Add a food that is not in the catalog.
     *
     * The catalog will never cover every food a person eats, so a free-text
     * entry is accepted rather than refusing the meal. It is flagged as resting
     * on a category default density, which the UI surfaces.
     */
    fun onFreeTextFoodAdded(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        _uiState.update { state ->
            state.copy(
                items = state.items + MealItem.userEntered(
                    id = UUID.randomUUID().toString(),
                    displayName = trimmed,
                    category = FoodCategory.SOLID,
                    volumeMl = DEFAULT_ADDED_VOLUME_ML,
                    densityGramsPerMl = FALLBACK_SOLID_DENSITY,
                    densitySource = CATEGORY_DEFAULT_SOURCE,
                    isFallbackDensity = true,
                ),
            )
        }
        onFoodPickerDismissed()
    }

    fun onSave() {
        val state = _uiState.value
        if (!state.canSave) return

        _uiState.update { it.copy(isSaving = true, error = null) }

        viewModelScope.launch {
            val meal = Meal(
                id = UUID.randomUUID().toString(),
                consumedAt = timeProvider.now(),
                timeZone = timeProvider.currentZone(),
                mealType = state.mealType,
                items = state.items,
                imagePath = state.imagePath,
            )

            when (val outcome = mealRepository.logMeal(meal)) {
                is Outcome.Success -> _uiState.update {
                    it.copy(isSaving = false, savedMealId = outcome.data.id)
                }

                is Outcome.Failure -> _uiState.update {
                    it.copy(isSaving = false, error = outcome.error)
                }
            }
        }
    }

    fun onErrorDismissed() {
        _uiState.update { it.copy(error = null) }
    }

    companion object {
        const val ARG_IMAGE_PATH = "imagePath"
        const val MAX_VOLUME_ML = 3000.0

        /** A middling serving, so an added food starts somewhere adjustable. */
        const val DEFAULT_ADDED_VOLUME_ML = 150.0

        private const val CATALOG_SOURCE = "local-food-catalog-cache"
        private const val CATEGORY_DEFAULT_SOURCE = "category-default"

        /** Matches the ML package's solid-category default. */
        private const val FALLBACK_SOLID_DENSITY = 0.85
    }
}
