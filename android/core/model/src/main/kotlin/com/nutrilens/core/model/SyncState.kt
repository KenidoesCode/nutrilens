package com.nutrilens.core.model

/**
 * Where a locally-created record stands in its journey to the server.
 *
 * This is the state machine that makes the app offline-first. A meal is usable
 * the instant it is [PENDING]; everything after that is bookkeeping the user
 * never has to think about.
 *
 * ```
 * PENDING ──► SYNCING ──► SYNCED
 *                │
 *                ▼
 *             FAILED ──► RETRYING ──► SYNCING
 * ```
 */
enum class SyncState {
    /** Saved on device, not yet sent. The meal is already fully usable. */
    PENDING,

    /** An upload is in flight. */
    SYNCING,

    /** The server has acknowledged it. */
    SYNCED,

    /** The attempt failed and the backoff window has not elapsed. */
    FAILED,

    /** Queued for another attempt after backoff. */
    RETRYING,
    ;

    /** Whether the record still owes the server an upload. */
    val isOutstanding: Boolean
        get() = this != SYNCED

    /** Whether a worker may pick this record up right now. */
    val isEligibleForUpload: Boolean
        get() = this == PENDING || this == RETRYING || this == FAILED
}
