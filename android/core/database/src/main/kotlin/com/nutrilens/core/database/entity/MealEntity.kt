package com.nutrilens.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A locally-stored meal.
 *
 * The primary key is a client-generated UUID, not an autoincrement. Offline
 * first requires it: the phone mints the id while it has no network, and that
 * same id is what the server stores, so a later sync can never duplicate the
 * record.
 *
 * [consumedAtEpochMillis] is an absolute instant and [timeZoneId] the zone the
 * user was in. Both are kept for the same reason the server keeps both.
 */
@Entity(
    tableName = "meals",
    indices = [
        Index(value = ["consumedAtEpochMillis"]),
        Index(value = ["syncState"]),
        Index(value = ["isDeleted", "consumedAtEpochMillis"]),
        Index(value = ["idempotencyKey"], unique = true),
    ],
)
data class MealEntity(
    @PrimaryKey val id: String,
    val consumedAtEpochMillis: Long,
    val timeZoneId: String,
    val mealType: String,
    val notes: String? = null,
    @ColumnInfo(name = "localImagePath") val localImagePath: String? = null,

    val totalMassGrams: Double? = null,
    val totalEnergyKcal: Double? = null,

    val syncState: String,
    val remoteId: String? = null,

    /**
     * Sent with the upload so a retry the server already applied is recognised
     * rather than creating a second meal.
     */
    val idempotencyKey: String,

    val syncAttempts: Int = 0,
    val lastSyncErrorMessage: String? = null,
    val nextAttemptAtEpochMillis: Long? = null,

    /** Soft deletion: the server still has to be told, so the row must survive. */
    val isDeleted: Boolean = false,

    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
