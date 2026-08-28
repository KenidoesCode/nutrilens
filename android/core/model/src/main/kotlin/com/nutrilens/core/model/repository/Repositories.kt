package com.nutrilens.core.model.repository

import com.nutrilens.core.model.AnalysisResult
import com.nutrilens.core.model.AppLanguage
import com.nutrilens.core.model.EatingPatternSummary
import com.nutrilens.core.model.EatingWindow
import com.nutrilens.core.model.FoodCatalogItem
import com.nutrilens.core.model.Meal
import com.nutrilens.core.model.Outcome
import com.nutrilens.core.model.SyncState
import com.nutrilens.core.model.UserProfile
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

/**
 * Repository contracts.
 *
 * They live in the pure domain module and are implemented in `core:data`, so
 * the direction of dependency runs inwards: features and view models depend on
 * these interfaces and never on Room, Retrofit or any other detail. That is
 * what makes the storage and transport replaceable, and the view models
 * testable with plain fakes.
 */

interface AuthRepository {
    /** The signed-in user, or `null`. Emits on sign-in, sign-out and expiry. */
    val currentUser: Flow<UserProfile?>

    /** Whether a session exists, without waiting for the profile to load. */
    val isAuthenticated: Flow<Boolean>

    suspend fun register(
        email: String,
        password: String,
        displayName: String?,
        language: AppLanguage,
    ): Outcome<UserProfile>

    suspend fun login(email: String, password: String): Outcome<UserProfile>

    suspend fun logout(): Outcome<Unit>

    /** Refresh the profile from the server; local data stays usable on failure. */
    suspend fun refreshProfile(): Outcome<UserProfile>
}

interface MealRepository {
    /** Every meal, newest first. Local-first: emits before any network call. */
    fun observeMeals(): Flow<List<Meal>>

    fun observeMealsBetween(start: Instant, end: Instant): Flow<List<Meal>>

    fun observeMeal(mealId: String): Flow<Meal?>

    /** Count of records still owing the server an upload, for the sync banner. */
    fun observePendingSyncCount(): Flow<Int>

    /**
     * Save a meal locally and queue it for upload.
     *
     * Returns as soon as the local write succeeds. A meal is never lost to a
     * missing network, so this does not fail when offline.
     */
    suspend fun logMeal(meal: Meal): Outcome<Meal>

    suspend fun updateItemPortion(
        mealId: String,
        itemId: String,
        correctedVolumeMl: Double,
    ): Outcome<Meal>

    suspend fun renameItem(mealId: String, itemId: String, displayName: String): Outcome<Meal>

    suspend fun removeItem(mealId: String, itemId: String): Outcome<Meal>

    suspend fun deleteMeal(mealId: String): Outcome<Unit>

    /** Pull server-side changes. Safe to call repeatedly. */
    suspend fun refresh(): Outcome<Unit>
}

interface AnalysisRepository {
    /**
     * Analyse a captured meal photograph.
     *
     * Requires connectivity: recognition runs server-side. Callers must handle
     * [com.nutrilens.core.model.AppError.Offline] by letting the user log the
     * meal manually rather than blocking them.
     */
    suspend fun analyzeMealImage(imagePath: String): Outcome<AnalysisResult>
}

interface AnalyticsRepository {
    /** Today's window, computed locally so it is correct offline. */
    fun observeTodayWindow(): Flow<EatingWindow>

    fun observePatternSummary(startDay: LocalDate, endDay: LocalDate): Flow<EatingPatternSummary>
}

interface FoodCatalogRepository {
    /** Search the catalog for the food-correction picker. */
    fun search(query: String): Flow<List<FoodCatalogItem>>

    suspend fun refresh(): Outcome<Unit>
}

interface SyncRepository {
    /** Aggregate sync state, for the status indicator. */
    fun observeSyncState(): Flow<SyncStatus>

    /** Ask for an immediate sync attempt, e.g. from pull-to-refresh. */
    suspend fun requestSync()
}

/** What the UI needs to tell the user about synchronisation. */
data class SyncStatus(
    val pendingCount: Int,
    val state: SyncState,
    val lastSyncedAt: Instant?,
    val lastError: String? = null,
) {
    val isFullySynced: Boolean get() = pendingCount == 0 && state == SyncState.SYNCED
}

interface SettingsRepository {
    val language: Flow<AppLanguage>

    /** Whether analysis may upload the photograph, or only its derived results. */
    val storeImagesRemotely: Flow<Boolean>

    suspend fun setLanguage(language: AppLanguage)

    suspend fun setStoreImagesRemotely(enabled: Boolean)

    /** Erase every local record. Used on sign-out and account deletion. */
    suspend fun clearLocalData()
}
