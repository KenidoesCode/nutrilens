package com.nutrilens.core.datastore

import kotlinx.coroutines.flow.Flow

/** A stored session. */
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAtEpochSeconds: Long,
) {
    /**
     * Whether the access token should be refreshed before use.
     *
     * A skew window is applied so a token that expires mid-flight is refreshed
     * beforehand rather than producing a spurious 401 the user sees.
     */
    fun needsRefresh(nowEpochSeconds: Long): Boolean =
        nowEpochSeconds >= accessExpiresAtEpochSeconds - REFRESH_SKEW_SECONDS

    companion object {
        const val REFRESH_SKEW_SECONDS = 60L
    }
}

/**
 * Persists the session across process death.
 *
 * An interface so the network layer depends on the capability rather than on
 * Keystore, and so tests can substitute an in-memory store.
 */
interface AuthTokenStore {

    /** Emits the current tokens, or `null` when signed out. */
    val tokens: Flow<AuthTokens?>

    suspend fun read(): AuthTokens?

    suspend fun save(tokens: AuthTokens)

    suspend fun clear()
}
