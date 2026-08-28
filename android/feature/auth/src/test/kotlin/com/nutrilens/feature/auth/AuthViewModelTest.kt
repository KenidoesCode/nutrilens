package com.nutrilens.feature.auth

import app.cash.turbine.test
import com.nutrilens.core.model.AppError
import com.nutrilens.core.model.AppLanguage
import com.nutrilens.core.model.Outcome
import com.nutrilens.core.model.UserProfile
import com.nutrilens.core.model.repository.AuthRepository
import com.nutrilens.core.model.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId

/**
 * Sign-in and sign-up behaviour.
 *
 * Uses hand-written fakes rather than a mocking framework: the repository
 * interfaces are small, and a fake that records its calls makes the test read
 * as a description of behaviour rather than of interactions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var authRepository: FakeAuthRepository
    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var viewModel: AuthViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        authRepository = FakeAuthRepository()
        settingsRepository = FakeSettingsRepository()
        viewModel = AuthViewModel(authRepository, settingsRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- validation ------------------------------------------------------

    @Test
    fun `an empty email is rejected before any request`() = runTest(dispatcher) {
        viewModel.onPasswordChange("correct-horse-1")
        viewModel.onSubmit()

        assertEquals(FieldError.EMAIL_REQUIRED, viewModel.uiState.value.emailError)
        assertEquals(0, authRepository.loginCalls)
    }

    @Test
    fun `a malformed email is rejected before any request`() = runTest(dispatcher) {
        viewModel.onEmailChange("not-an-email")
        viewModel.onPasswordChange("correct-horse-1")
        viewModel.onSubmit()

        assertEquals(FieldError.EMAIL_INVALID, viewModel.uiState.value.emailError)
        assertEquals(0, authRepository.loginCalls)
    }

    @Test
    fun `an empty password is rejected`() = runTest(dispatcher) {
        viewModel.onEmailChange("person@example.com")
        viewModel.onSubmit()

        assertEquals(FieldError.PASSWORD_REQUIRED, viewModel.uiState.value.passwordError)
    }

    @Test
    fun `a short password is rejected on sign up`() = runTest(dispatcher) {
        viewModel.onModeChange(AuthMode.SIGN_UP)
        viewModel.onEmailChange("person@example.com")
        viewModel.onPasswordChange("short")
        viewModel.onSubmit()

        assertEquals(FieldError.PASSWORD_TOO_SHORT, viewModel.uiState.value.passwordError)
    }

    @Test
    fun `a short password is allowed through on sign in`() = runTest(dispatcher) {
        // An existing account may predate the current rule; refusing to even
        // attempt their login would lock them out of their own data.
        viewModel.onEmailChange("person@example.com")
        viewModel.onPasswordChange("old")
        viewModel.onSubmit()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.passwordError)
        assertEquals(1, authRepository.loginCalls)
    }

    @Test
    fun `typing clears the previous error`() = runTest(dispatcher) {
        viewModel.onSubmit()
        assertTrue(viewModel.uiState.value.emailError != null)

        viewModel.onEmailChange("p")
        assertNull(viewModel.uiState.value.emailError)
    }

    // --- submission ------------------------------------------------------

    @Test
    fun `a successful sign in marks the session authenticated`() = runTest(dispatcher) {
        authRepository.loginResult = Outcome.success(profile())

        viewModel.onEmailChange("person@example.com")
        viewModel.onPasswordChange("correct-horse-1")
        viewModel.onSubmit()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isAuthenticated)
        assertFalse(viewModel.uiState.value.isSubmitting)
    }

    @Test
    fun `the password is cleared from state after submission`() = runTest(dispatcher) {
        // It must not linger in memory or in a state dump once it is no longer
        // needed, whether the attempt succeeded or failed.
        authRepository.loginResult = Outcome.success(profile())

        viewModel.onEmailChange("person@example.com")
        viewModel.onPasswordChange("correct-horse-1")
        viewModel.onSubmit()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.password)
    }

    @Test
    fun `the password is cleared even when sign in fails`() = runTest(dispatcher) {
        authRepository.loginResult = Outcome.failure(AppError.InvalidCredentials)

        viewModel.onEmailChange("person@example.com")
        viewModel.onPasswordChange("wrong-password-1")
        viewModel.onSubmit()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.password)
    }

    @Test
    fun `a rejected sign in surfaces the specific error`() = runTest(dispatcher) {
        authRepository.loginResult = Outcome.failure(AppError.InvalidCredentials)

        viewModel.onEmailChange("person@example.com")
        viewModel.onPasswordChange("wrong-password-1")
        viewModel.onSubmit()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(AppError.InvalidCredentials, viewModel.uiState.value.submitError)
        assertFalse(viewModel.uiState.value.isAuthenticated)
    }

    @Test
    fun `being offline is reported as offline, not as bad credentials`() =
        runTest(dispatcher) {
            authRepository.loginResult = Outcome.failure(AppError.Offline)

            viewModel.onEmailChange("person@example.com")
            viewModel.onPasswordChange("correct-horse-1")
            viewModel.onSubmit()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(AppError.Offline, viewModel.uiState.value.submitError)
        }

    @Test
    fun `sign up passes the chosen language through`() = runTest(dispatcher) {
        settingsRepository.currentLanguage.value = AppLanguage.TELUGU
        authRepository.registerResult = Outcome.success(profile())

        viewModel.onModeChange(AuthMode.SIGN_UP)
        viewModel.onEmailChange("person@example.com")
        viewModel.onPasswordChange("correct-horse-1")
        viewModel.onSubmit()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(AppLanguage.TELUGU, authRepository.registeredLanguage)
    }

    @Test
    fun `the submit button is disabled while a request is in flight`() =
        runTest(dispatcher) {
            authRepository.loginResult = Outcome.success(profile())

            viewModel.onEmailChange("person@example.com")
            viewModel.onPasswordChange("correct-horse-1")

            viewModel.uiState.test {
                assertTrue(awaitItem().canSubmit)
                viewModel.onSubmit()
                // A second tap while submitting would fire a duplicate request.
                assertFalse(awaitItem().canSubmit)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `switching mode clears errors from the other form`() = runTest(dispatcher) {
        viewModel.onSubmit()
        assertTrue(viewModel.uiState.value.emailError != null)

        viewModel.onModeChange(AuthMode.SIGN_UP)

        assertNull(viewModel.uiState.value.emailError)
        assertNull(viewModel.uiState.value.passwordError)
    }

    private fun profile() = UserProfile(
        id = "user-1",
        email = "person@example.com",
        displayName = "Test Person",
        timeZone = ZoneId.of("Asia/Kolkata"),
        language = AppLanguage.ENGLISH,
    )
}

private class FakeAuthRepository : AuthRepository {
    var loginResult: Outcome<UserProfile> = Outcome.failure(AppError.InvalidCredentials)
    var registerResult: Outcome<UserProfile> = Outcome.failure(AppError.EmailAlreadyRegistered)
    var loginCalls = 0
    var registeredLanguage: AppLanguage? = null

    override val currentUser: Flow<UserProfile?> = flowOf(null)
    override val isAuthenticated: Flow<Boolean> = flowOf(false)

    override suspend fun register(
        email: String,
        password: String,
        displayName: String?,
        language: AppLanguage,
    ): Outcome<UserProfile> {
        registeredLanguage = language
        return registerResult
    }

    override suspend fun login(email: String, password: String): Outcome<UserProfile> {
        loginCalls++
        return loginResult
    }

    override suspend fun logout(): Outcome<Unit> = Outcome.success(Unit)

    override suspend fun deleteAccount(): Outcome<Unit> = Outcome.success(Unit)

    override suspend fun pushProfileUpdate(
        displayName: String?,
        timezone: String?,
        locale: String?,
    ) = Unit
}

private class FakeSettingsRepository : SettingsRepository {
    val currentLanguage = MutableStateFlow(AppLanguage.ENGLISH)

    override val language: Flow<AppLanguage> = currentLanguage
    override val storeImagesRemotely: Flow<Boolean> = MutableStateFlow(false)

    override suspend fun setLanguage(language: AppLanguage) {
        currentLanguage.value = language
    }

    override suspend fun setStoreImagesRemotely(enabled: Boolean) = Unit

    override suspend fun clearLocalData() = Unit

    override suspend fun exportDataAsJson(): Outcome<String> = Outcome.success("{}")
}
