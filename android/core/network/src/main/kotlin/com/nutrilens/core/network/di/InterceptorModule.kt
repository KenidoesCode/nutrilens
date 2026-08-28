package com.nutrilens.core.network.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import okhttp3.Interceptor
import javax.inject.Named

/**
 * Declares the extra-interceptor set so it can legitimately be empty.
 *
 * Release builds contribute nothing here. The debug build adds request
 * logging, which must never ship: NutriLens requests carry bearer tokens and
 * meal photographs, and logging them would write both into logcat.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class InterceptorModule {

    @Multibinds
    @Named("networkInterceptors")
    abstract fun bindNetworkInterceptors(): Set<Interceptor>
}
