package com.nutrilens.core.network.di

import com.nutrilens.core.datastore.AuthTokenStore
import com.nutrilens.core.network.ApiErrorMapper
import com.nutrilens.core.network.api.NutriLensApi
import com.nutrilens.core.network.interceptor.AuthInterceptor
import com.nutrilens.core.network.interceptor.RetrofitTokenRefreshApi
import com.nutrilens.core.network.interceptor.TokenAuthenticator
import com.nutrilens.core.network.interceptor.TokenRefreshApi
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.time.Duration
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Qualifier
import javax.inject.Singleton

/** The client used only to refresh a session; it carries no authenticator. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RefreshClient

/** Injected base URL, so build variants and tests can point elsewhere. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApiBaseUrl

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(15)
    private val READ_TIMEOUT: Duration = Duration.ofSeconds(30)

    // Image analysis runs a model server-side; the wait is legitimately longer
    // than a JSON round trip, and a short timeout here would fail requests that
    // were about to succeed.
    private val CALL_TIMEOUT: Duration = Duration.ofSeconds(90)

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        // The server may add fields; an older client must keep working rather
        // than failing to parse a response it mostly understands.
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideApiErrorMapper(json: Json): ApiErrorMapper = ApiErrorMapper(json)

    @Provides
    @Singleton
    @RefreshClient
    fun provideRefreshClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT)
        .readTimeout(READ_TIMEOUT)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun provideTokenRefreshApi(
        @RefreshClient client: OkHttpClient,
        @ApiBaseUrl baseUrl: String,
        json: Json,
    ): TokenRefreshApi {
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory(APPLICATION_JSON))
            .build()
        return RetrofitTokenRefreshApi(
            retrofit.create(RetrofitTokenRefreshApi.RefreshService::class.java),
        )
    }

    @Provides
    @Singleton
    fun provideAuthInterceptor(tokenStore: AuthTokenStore): AuthInterceptor =
        AuthInterceptor(tokenStore)

    @Provides
    @Singleton
    fun provideTokenAuthenticator(
        tokenStore: AuthTokenStore,
        refreshApi: Provider<TokenRefreshApi>,
    ): TokenAuthenticator = TokenAuthenticator(tokenStore, refreshApi)

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        authenticator: TokenAuthenticator,
        @Named("networkInterceptors") extraInterceptors: Set<@JvmSuppressWildcards okhttp3.Interceptor>,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT)
        .readTimeout(READ_TIMEOUT)
        .callTimeout(CALL_TIMEOUT)
        .retryOnConnectionFailure(true)
        .addInterceptor(authInterceptor)
        .apply { extraInterceptors.forEach(::addInterceptor) }
        .authenticator(authenticator)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(
        client: OkHttpClient,
        @ApiBaseUrl baseUrl: String,
        json: Json,
    ): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory(APPLICATION_JSON))
        .build()

    @Provides
    @Singleton
    fun provideNutriLensApi(retrofit: Retrofit): NutriLensApi =
        retrofit.create(NutriLensApi::class.java)

    private val APPLICATION_JSON = "application/json".toMediaType()
}
