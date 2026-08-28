package com.nutrilens.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutrilens.core.model.AppLanguage
import com.nutrilens.core.model.UserProfile
import com.nutrilens.core.model.repository.AuthRepository
import com.nutrilens.core.model.repository.SettingsRepository
import com.nutrilens.core.model.repository.SyncRepository
import com.nutrilens.core.model.repository.SyncStatus
import com.nutrilens.core.model.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val profile: UserProfile? = null,
    val language: AppLanguage = AppLanguage.ENGLISH,
    val storeImagesRemotely: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus(0, SyncState.SYNCED, null),
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

    val uiState: StateFlow<SettingsUiState> = combine(
        authRepository.currentUser,
        settingsRepository.language,
        settingsRepository.storeImagesRemotely,
        syncRepository.observeSyncState(),
    ) { profile, language, storeImages, syncStatus ->
        SettingsUiState(
            profile = profile,
            language = language,
            storeImagesRemotely = storeImages,
            syncStatus = syncStatus,
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
    fun onSignOut() {
        viewModelScope.launch {
            authRepository.logout()
            _signedOut.value = true
        }
    }

    fun onDeleteAccount() {
        viewModelScope.launch {
            settingsRepository.clearLocalData()
            authRepository.logout()
            _signedOut.value = true
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
