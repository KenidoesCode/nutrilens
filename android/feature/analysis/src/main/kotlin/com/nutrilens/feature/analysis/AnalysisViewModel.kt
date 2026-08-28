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
import com.nutrilens.core.model.repository.AnalysisRepository
import com.nutrilens.core.model.repository.MealRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val imagePath: String = savedStateHandle
        .get<String>(ARG_IMAGE_PATH)
        ?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) }
        .orEmpty()

    private val _uiState = MutableStateFlow(AnalysisUiState(imagePath = imagePath))
    val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()

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
    }
}
