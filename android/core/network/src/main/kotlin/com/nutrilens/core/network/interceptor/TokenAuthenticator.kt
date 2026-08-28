package com.nutrilens.core.network.interceptor

import com.nutrilens.core.datastore.AuthTokenStore
import com.nutrilens.core.datastore.AuthTokens
import com.nutrilens.core.network.dto.RefreshRequestDto
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.time.Instant
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Refreshes an expired session once, transparently, on a 401.
 *
 * OkHttp calls an [Authenticator] only after a request has been rejected, so
 * this costs nothing on the happy path and needs no clock-skew guessing.
 *
 * Two hazards are handled explicitly:
 *
 * - **Refresh storms.** Several requests can 401 at once. A mutex serialises
 *   them and, once inside, each re-checks whether another thread already
 *   obtained a newer token; only the first actually calls the server.
 * - **Infinite loops.** If the refreshed token is also rejected, [responseCount]
 *   stops the retry rather than looping forever.
 *
 * The refresh call goes through a separate Retrofit instance (supplied as a
 * [Provider] to break the dependency cycle) that does not install this
 * authenticator, so a failing refresh cannot recurse into itself.
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val tokenStore: AuthTokenStore,
    private val refreshApi: Provider<TokenRefreshApi>,
) : Authenticator {

    private val refreshMutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= MAX_REFRESH_ATTEMPTS) return null

        val failedToken = response.request.header("Authorization")
            ?.removePrefix("Bearer ")

        val refreshed = runBlocking {
            refreshMutex.withLock {
                val current = tokenStore.read() ?: return@withLock null

                // Another request may already have refreshed while this one
                // waited for the lock; reuse that token instead of burning the
                // refresh token on a second, redundant rotation.
                if (failedToken != null && current.accessToken != failedToken) {
                    return@withLock current
                }

                when (val outcome = refreshApi.get().refresh(current.refreshToken)) {
                    is RefreshOutcome.Refreshed -> outcome.tokens.also { tokenStore.save(it) }

                    // The server rejected the refresh token itself. Clearing is
                    // what makes the app fall back to the sign-in screen rather
                    // than retrying a session that no longer exists.
                    RefreshOutcome.Rejected -> {
                        tokenStore.clear()
                        null
                    }

                    // A transient failure is not evidence the session is dead.
                    // Signing the user out over a dropped connection would cost
                    // them their session for a problem that resolves itself.
                    RefreshOutcome.Unavailable -> null
                }
            }
        } ?: return null

        return response.request.newBuilder()
            .header("Authorization", "Bearer ${refreshed.accessToken}")
            .build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private companion object {
        const val MAX_REFRESH_ATTEMPTS = 2
    }
}

/** What happened when the app tried to rotate its session. */
sealed interface RefreshOutcome {

    /** A new token pair was issued. */
    data class Refreshed(val tokens: AuthTokens) : RefreshOutcome

    /** The server refused the refresh token: the session is over. */
    data object Rejected : RefreshOutcome

    /**
     * The attempt could not be completed -- no network, a timeout, a 5xx.
     *
     * Distinct from [Rejected] because the session may well still be valid, and
     * discarding it over a dropped connection would sign the user out for a
     * problem that resolves itself.
     */
    data object Unavailable : RefreshOutcome
}

/**
 * The minimal refresh capability, kept separate from
 * [com.nutrilens.core.network.api.NutriLensApi].
 *
 * Its implementation must use an OkHttp client with no authenticator attached,
 * or a failing refresh would trigger another refresh.
 */
interface TokenRefreshApi {
    suspend fun refresh(refreshToken: String): RefreshOutcome
}

/** Retrofit-backed [TokenRefreshApi]. */
class RetrofitTokenRefreshApi(
    private val service: RefreshService,
) : TokenRefreshApi {

    override suspend fun refresh(refreshToken: String): RefreshOutcome = try {
        val response = service.refresh(RefreshRequestDto(refreshToken))
        val body = response.body()
        when {
            response.isSuccessful && body != null -> RefreshOutcome.Refreshed(
                AuthTokens(
                    accessToken = body.accessToken,
                    refreshToken = body.refreshToken,
                    accessExpiresAtEpochSeconds = Instant.parse(body.accessExpiresAt).epochSecond,
                ),
            )

            // Only the server saying "no" ends the session. A 5xx is the
            // server's problem, not evidence about this refresh token.
            response.code() in REJECTION_STATUS_CODES -> RefreshOutcome.Rejected

            else -> RefreshOutcome.Unavailable
        }
    } catch (e: Exception) {
        RefreshOutcome.Unavailable
    }

    private companion object {
        val REJECTION_STATUS_CODES = setOf(400, 401, 403, 404, 422)
    }

    interface RefreshService {
        @retrofit2.http.POST("api/v1/auth/refresh")
        suspend fun refresh(
            @retrofit2.http.Body body: RefreshRequestDto,
        ): retrofit2.Response<com.nutrilens.core.network.dto.TokenResponseDto>
    }
}
