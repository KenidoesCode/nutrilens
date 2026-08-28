package com.nutrilens.core.data.repository

import com.nutrilens.core.common.di.IoDispatcher
import com.nutrilens.core.common.time.TimeProvider
import com.nutrilens.core.data.mapper.toDomain
import com.nutrilens.core.database.dao.FoodCatalogDao
import com.nutrilens.core.database.dao.MealDao
import com.nutrilens.core.database.dao.SyncOperationDao
import com.nutrilens.core.data.image.MealImageStore
import com.nutrilens.core.datastore.AuthTokenStore
import com.nutrilens.core.datastore.AuthTokens
import com.nutrilens.core.datastore.UserPreferencesStore
import com.nutrilens.core.model.AppError
import com.nutrilens.core.model.AppLanguage
import com.nutrilens.core.model.Outcome
import com.nutrilens.core.model.onSuccess
import com.nutrilens.core.model.UserProfile
import com.nutrilens.core.model.repository.AuthRepository
import com.nutrilens.core.network.ApiErrorMapper
import com.nutrilens.core.network.api.NutriLensApi
import com.nutrilens.core.network.dto.LoginRequestDto
import com.nutrilens.core.network.dto.LogoutRequestDto
import com.nutrilens.core.network.dto.RegisterRequestDto
import com.nutrilens.core.network.dto.UserUpdateRequestDto
import com.nutrilens.core.network.dto.TokenResponseDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Authentication.
 *
 * The stored token pair is what defines "signed in": the profile is a cache on
 * top of it. That ordering means a cold start with a valid session shows the
 * app immediately rather than a spinner waiting on a profile fetch.
 */
@Singleton
class DefaultAuthRepository @Inject constructor(
    private val api: NutriLensApi,
    private val tokenStore: AuthTokenStore,
    private val errorMapper: ApiErrorMapper,
    private val preferences: UserPreferencesStore,
    private val mealDao: MealDao,
    private val foodCatalogDao: FoodCatalogDao,
    private val syncOperationDao: SyncOperationDao,
    private val imageStore: MealImageStore,
    private val timeProvider: TimeProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AuthRepository {

    private val cachedProfile = MutableStateFlow<UserProfile?>(null)

    /**
     * The signed-in user.
     *
     * The stored token pair is what defines "signed in"; the profile is a cache
     * on top of it. On a cold start that cache is empty, so the first collector
     * triggers a fetch -- without it, a user who restarted the app would see a
     * blank name and an empty profile screen despite having a valid session.
     *
     * The fetch is best-effort: it happens off the critical path, and a failure
     * leaves the app usable with the profile simply absent rather than blocking
     * on the network.
     */
    override val currentUser: Flow<UserProfile?> = cachedProfile
        .onStart { ensureProfileLoaded() }

    override val isAuthenticated: Flow<Boolean> = tokenStore.tokens.map { it != null }

    private suspend fun ensureProfileLoaded() {
        if (cachedProfile.value != null) return
        if (tokenStore.read() == null) return
        loadProfile()
    }

    override suspend fun register(
        email: String,
        password: String,
        displayName: String?,
        language: AppLanguage,
    ): Outcome<UserProfile> = withContext(ioDispatcher) {
        val request = RegisterRequestDto(
            email = email.trim(),
            password = password,
            displayName = displayName?.trim()?.takeIf { it.isNotEmpty() },
            timezone = timeProvider.currentZone().id,
            locale = language.tag,
        )

        errorMapper.execute { api.register(request) }
            .flatMap { tokens -> storeTokens(tokens) }
            .flatMap { loadProfile() }
    }

    override suspend fun login(email: String, password: String): Outcome<UserProfile> =
        withContext(ioDispatcher) {
            errorMapper.execute { api.login(LoginRequestDto(email.trim(), password)) }
                .flatMap { tokens -> storeTokens(tokens) }
                .flatMap { loadProfile() }
        }

    override suspend fun logout(): Outcome<Unit> = withContext(ioDispatcher) {
        val refreshToken = tokenStore.read()?.refreshToken

        // Tell the server first so the session is revoked rather than merely
        // forgotten, but do not let a network failure trap the user in a
        // signed-in state they asked to leave.
        if (refreshToken != null) {
            errorMapper.executeUnit { api.logout(LogoutRequestDto(refreshToken)) }
        }

        clearLocalState()
        Outcome.success(Unit)
    }

    /**
     * Delete the account.
     *
     * The server is told first. If that fails the local data is kept and the
     * error is reported, because wiping the device while the account still
     * exists would leave the person signed out of data they did not delete and
     * unable to reach it.
     */
    override suspend fun deleteAccount(): Outcome<Unit> = withContext(ioDispatcher) {
        val outcome = errorMapper.executeUnit { api.deleteAccount() }
        when {
            outcome is Outcome.Success -> {
                clearLocalState()
                Outcome.success(Unit)
            }

            // The account is already gone server-side; finishing locally is
            // what the user asked for.
            outcome is Outcome.Failure && outcome.error == AppError.SessionExpired -> {
                clearLocalState()
                Outcome.success(Unit)
            }

            else -> outcome
        }
    }

    override suspend fun pushProfileUpdate(
        displayName: String? = null,
        timezone: String? = null,
        locale: String? = null,
    ) = withContext(ioDispatcher) {
        errorMapper.execute {
            api.updateProfile(
                UserUpdateRequestDto(
                    displayName = displayName,
                    timezone = timezone,
                    locale = locale,
                ),
            )
        }.onSuccess { cachedProfile.value = it.toDomain() }
        Unit
    }

    private suspend fun storeTokens(response: TokenResponseDto): Outcome<Unit> = try {
        tokenStore.save(
            AuthTokens(
                accessToken = response.accessToken,
                refreshToken = response.refreshToken,
                accessExpiresAtEpochSeconds = Instant.parse(response.accessExpiresAt).epochSecond,
            ),
        )
        Outcome.success(Unit)
    } catch (e: Exception) {
        // A token we cannot persist is a session that dies on the next launch;
        // failing loudly beats a mysterious sign-out later.
        Outcome.failure(AppError.DeviceError("The session could not be saved securely."))
    }

    private suspend fun loadProfile(): Outcome<UserProfile> =
        errorMapper.execute { api.getProfile() }
            .map { it.toDomain() }
            .onSuccess { profile ->
                cachedProfile.value = profile
                preferences.setLanguage(profile.language)
            }

    /**
     * Erase everything belonging to the signed-out account.
     *
     * Meals, cached foods, queued operations and stored photographs all go. A
     * shared device must not leave one person's dietary history readable by the
     * next person to sign in.
     */
    private suspend fun clearLocalState() {
        tokenStore.clear()
        cachedProfile.value = null
        mealDao.clear()
        foodCatalogDao.clear()
        syncOperationDao.clear()
        imageStore.clear()
        preferences.clearSessionScopedPreferences()
    }
}
