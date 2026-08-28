package com.nutrilens.core.common.network

import kotlinx.coroutines.flow.Flow

/**
 * Reports whether the device currently has usable internet.
 *
 * An interface because the sync engine's behaviour under changing connectivity
 * is exactly what needs testing, and that is impractical against the real
 * framework callbacks.
 */
interface ConnectivityObserver {

    /** Emits the current state immediately, then on every change. */
    val isOnline: Flow<Boolean>

    /**
     * A point-in-time reading.
     *
     * Advisory only: connectivity can drop between this call and the request.
     * Callers must still handle a network failure rather than trusting it.
     */
    fun isCurrentlyOnline(): Boolean
}
