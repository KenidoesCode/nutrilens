package com.nutrilens.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nutrilens.core.model.AppLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "nutrilens_preferences",
)

/** Non-sensitive user preferences. Credentials live in [AuthTokenStore]. */
@Singleton
class UserPreferencesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val preferences: Flow<Preferences> = context.dataStore.data
        // A corrupt preferences file must not brick the app; falling back to
        // defaults loses a setting, which is recoverable, rather than access.
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }

    val language: Flow<AppLanguage> = preferences.map { prefs ->
        AppLanguage.fromTag(prefs[KEY_LANGUAGE])
    }

    /**
     * Whether meal photographs may be uploaded.
     *
     * Defaults to `false`. Analysis works either way -- the image is sent for
     * inference regardless -- and this controls only whether the server keeps
     * it. Retaining a person's meal photographs is opt-in, not opt-out.
     */
    val storeImagesRemotely: Flow<Boolean> = preferences.map { prefs ->
        prefs[KEY_STORE_IMAGES_REMOTELY] ?: false
    }

    val onboardingCompleted: Flow<Boolean> = preferences.map { prefs ->
        prefs[KEY_ONBOARDING_COMPLETED] ?: false
    }

    val lastSyncedAtEpochMillis: Flow<Long?> = preferences.map { prefs ->
        prefs[KEY_LAST_SYNCED_AT]
    }

    suspend fun currentLanguage(): AppLanguage = language.first()

    suspend fun setLanguage(language: AppLanguage) {
        context.dataStore.edit { it[KEY_LANGUAGE] = language.tag }
    }

    suspend fun setStoreImagesRemotely(enabled: Boolean) {
        context.dataStore.edit { it[KEY_STORE_IMAGES_REMOTELY] = enabled }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[KEY_ONBOARDING_COMPLETED] = completed }
    }

    suspend fun setLastSyncedAt(epochMillis: Long) {
        context.dataStore.edit { it[KEY_LAST_SYNCED_AT] = epochMillis }
    }

    /**
     * Reset preferences, keeping the chosen language.
     *
     * Sign-out should not switch a Telugu speaker's interface back to English:
     * the language is a property of the device's user, not of the session.
     */
    suspend fun clearSessionScopedPreferences() {
        context.dataStore.edit { prefs ->
            val language = prefs[KEY_LANGUAGE]
            prefs.clear()
            if (language != null) prefs[KEY_LANGUAGE] = language
        }
    }

    private companion object {
        val KEY_LANGUAGE = stringPreferencesKey("language")
        val KEY_STORE_IMAGES_REMOTELY = booleanPreferencesKey("store_images_remotely")
        val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val KEY_LAST_SYNCED_AT = longPreferencesKey("last_synced_at")
    }
}
