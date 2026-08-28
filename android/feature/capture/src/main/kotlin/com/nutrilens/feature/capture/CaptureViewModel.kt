package com.nutrilens.feature.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutrilens.core.data.image.MealImageStore
import com.nutrilens.core.model.AppError
import com.nutrilens.core.model.Outcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class CaptureUiState(
    val isProcessing: Boolean = false,
    val capturedImagePath: String? = null,
    val error: AppError? = null,
)

/**
 * Owns the capture-to-prepared-image step.
 *
 * The camera itself is driven by the composable that holds the lifecycle; this
 * takes the raw file it produces and runs it through the image pipeline
 * (downscale, orientation, metadata stripping) before anything else sees it.
 */
@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val imageStore: MealImageStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    /** A cache file for CameraX to write the raw capture into. */
    fun newCaptureFile(): File = imageStore.newCaptureFile()

    fun onImageCaptured(source: File) {
        _uiState.update { it.copy(isProcessing = true, error = null) }

        viewModelScope.launch {
            when (val outcome = imageStore.prepare(source)) {
                is Outcome.Success -> {
                    // The raw capture has served its purpose. Deleting it now
                    // means the unprocessed frame -- the one still carrying GPS
                    // EXIF -- does not linger in the cache.
                    source.delete()
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            capturedImagePath = outcome.data.file.absolutePath,
                        )
                    }
                }

                is Outcome.Failure -> {
                    source.delete()
                    _uiState.update {
                        it.copy(isProcessing = false, error = outcome.error)
                    }
                }
            }
        }
    }

    fun onCaptureFailed() {
        _uiState.update {
            it.copy(
                isProcessing = false,
                error = AppError.DeviceError("The camera could not take a photograph."),
            )
        }
    }

    fun onErrorDismissed() {
        _uiState.update { it.copy(error = null) }
    }

    /** Clear the handled path so re-entering the screen does not re-navigate. */
    fun onNavigationHandled() {
        _uiState.update { it.copy(capturedImagePath = null) }
    }
}
