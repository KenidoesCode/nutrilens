package com.nutrilens.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A queued operation waiting to reach the server.
 *
 * Meals carry their own sync columns; this queue exists for everything else
 * (deletions, portion corrections, renames) so those are not lost when the
 * network is unavailable either. One row per logical operation, keyed by the
 * idempotency key the server will deduplicate on.
 */
@Entity(
    tableName = "sync_queue",
    indices = [
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["state", "nextAttemptAtEpochMillis"]),
    ],
)
data class SyncOperationEntity(
    @PrimaryKey val idempotencyKey: String,
    val operation: String,
    val entityType: String,
    val entityId: String,
    /** JSON body for the operation, serialised at enqueue time. */
    val payloadJson: String? = null,
    val state: String,
    val attempts: Int = 0,
    val lastError: String? = null,
    val nextAttemptAtEpochMillis: Long? = null,
    val createdAtEpochMillis: Long,
)
