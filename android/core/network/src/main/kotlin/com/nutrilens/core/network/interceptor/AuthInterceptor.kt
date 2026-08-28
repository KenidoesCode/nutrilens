package com.nutrilens.core.network.interceptor

import com.nutrilens.core.datastore.AuthTokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attaches the bearer token to authenticated requests.
 *
 * Endpoints that mint tokens are excluded: sending a stale (possibly expired)
 * access token to `/auth/refresh` invites the server to reject the refresh
 * itself, which would sign the user out for no reason.
 *
 * `runBlocking` is deliberate. OkHttp interceptors are synchronous by
 * contract and already run on a background thread; the read is a fast,
 * in-process Keystore lookup.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenStore: AuthTokenStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (isUnauthenticatedPath(request.url.encodedPath)) {
            return chain.proceed(request)
        }

        val accessToken = runBlocking { tokenStore.read()?.accessToken }
            ?: return chain.proceed(request)

        return chain.proceed(
            request.newBuilder()
                .header("Authorization", "Bearer $accessToken")
                .build(),
        )
    }

    private fun isUnauthenticatedPath(path: String): Boolean =
        UNAUTHENTICATED_PATHS.any { path.endsWith(it) }

    private companion object {
        val UNAUTHENTICATED_PATHS = listOf(
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
        )
    }
}
