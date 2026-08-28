package com.nutrilens.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutrilens.core.model.AppError
import com.nutrilens.core.model.AppLanguage
import com.nutrilens.core.model.Outcome
import com.nutrilens.core.model.UserProfile
import com.nutrilens.core.model.repository.AuthRepository
import com.nutrilens.core.model.repository.SettingsRepository
import com.nutrilens.core.model.repository.SyncRepository
import com.nutrilens.core.model.repository.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class SettingsUiState(
    val profile: UserProfile? = null,
    val language: AppLanguage = AppLanguage.ENGLISH,
    val storeImagesRemotely: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus(
        pendingCount = 0,
        failedCount = 0,
        lastSyncedAt = null,
        isOnline = true,
    ),
    val isExporting: Boolean = false,
    val exportError: AppError? = null,
)

/** A prepared export, handed to the system document picker to be written. */
data class PendingExport(val fileName: String, val json: String)

/** Transient export state, which no repository owns. */
private data class ExportState(
    val isExporting: Boolean = false,
    val error: AppError? = null,
)

/** Emitted once, for effects that must not replay on recomposition. */
sealed interface SettingsEvent {
    data object SignedOut : SettingsEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
    syncRepository: SyncRepository,
) : ViewModel() {

    private val _signedOut = MutableStateFlow(false)
    val signedOut: StateFlow<Boolean> = _signedOut.asStateFlow()

    private val _pendingExport = MutableStateFlow<PendingExport?>(null)
    val pendingExport: StateFlow<PendingExport?> = _pendingExport.asStateFlow()

    private val exportState = MutableStateFlow(ExportState())

    val uiState: StateFlow<SettingsUiState> = combine(
        authRepository.currentUser,
        settingsRepository.language,
        settingsRepository.storeImagesRemotely,
        syncRepository.observeSyncState(),
    ) { profile, language, storeImages, syncStatus ->
        Triple(profile, language, storeImages) to syncStatus
    }.combine(exportState) { (base, syncStatus), export ->
        val (profile, language, storeImages) = base
        SettingsUiState(
            profile = profile,
            language = language,
            storeImagesRemotely = storeImages,
            syncStatus = syncStatus,
            isExporting = export.isExporting,
            exportError = export.error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = SettingsUiState(),
    )

    fun onLanguageSelected(language: AppLanguage) {
        viewModelScope.launch { settingsRepository.setLanguage(language) }
    }

    fun onStoreImagesRemotelyChanged(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setStoreImagesRemotely(enabled) }
    }

    /**
     * Sign out.
     *
     * The repository revokes the session and erases local records; the screen
     * navigates only after that completes, so the timeline is never briefly
     * visible to whoever signs in next.
     */
    /**
     * Prepare an export.
     *
     * The JSON is produced here and handed to the caller, which passes it to
     * the system document picker. Writing the file ourselves would mean a
     * `FileProvider` and a share intent; letting the person choose where it
     * lands is simpler and gives them a file they own.
     */
    fun onExportRequested() {
        if (exportState.value.isExporting) return
        exportState.update { it.copy(isExporting = true, error = null) }

        viewModelScope.launch {
            when (val outcome = settingsRepository.exportDataAsJson()) {
                is Outcome.Success -> {
                    _pendingExport.value = PendingExport(
                        fileName = "nutrilens-export-${LocalDate.now()}.json",
                        json = outcome.data,
                    )
                    exportState.update { it.copy(isExporting = false) }
                }

                is Outcome.Failure -> exportState.update {
                    it.copy(isExporting = false, error = outcome.error)
                }
            }
        }
    }

    /** Clear the prepared export once the picker has consumed or cancelled it. */
    fun onExportHandled() {
        _pendingExport.value = null
    }

    fun onExportErrorDismissed() {
        exportState.update { it.copy(error = null) }
    }

    fun onSignOut() {
        viewModelScope.launch {
            authRepository.logout()
            _signedOut.value = true
        }
    }

    /**
     * Delete the account.
     *
     * The repository tells the server first and only then wipes the device, so
     * a failed request leaves the person signed in with their data intact
     * rather than locked out of an account that still exists.
     */
    fun onDeleteAccount() {
        viewModelScope.launch {
            when (authRepository.deleteAccount()) {
                is Outcome.Success -> _signedOut.value = true
                is Outcome.Failure -> exportState.update {
                    it.copy(error = AppError.ServerError("ACCOUNT_DELETE_FAILED"))
                }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
