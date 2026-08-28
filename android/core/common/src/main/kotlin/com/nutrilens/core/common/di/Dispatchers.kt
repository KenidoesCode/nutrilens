package com.nutrilens.core.common.di

import javax.inject.Qualifier

/**
 * Qualifiers for injected coroutine dispatchers.
 *
 * Nothing in the app calls `Dispatchers.IO` directly. Injecting them means a
 * test can substitute a deterministic scheduler, which is the difference
 * between a suite that is reliable and one that is flaky.
 */

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

/** Application-lifetime coroutine scope, for work that outlives a screen. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
