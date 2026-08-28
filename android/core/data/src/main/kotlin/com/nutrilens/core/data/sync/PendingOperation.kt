package com.nutrilens.core.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An edit to an already-synced meal, queued until it reaches the server.
 *
 * Creating a meal is handled by the meal row's own sync state. Edits need their
 * own queue because re-uploading an edited meal under its original idempotency
 * key is, by design, a *replay*: the server recognises the key and returns the
 * meal unchanged, so the edit would be silently discarded. Edits therefore go
 * through the item endpoints instead, addressed by the server's own item ids.
 */
@Serializable
sealed interface PendingOperation {

    /** Server-side id of the meal this operation belongs to. */
    val remoteMealId: String

    @Serializable
    @SerialName("correct_portion")
    data class CorrectPortion(
        override val remoteMealId: String,
        val remoteItemId: String,
        val volumeMl: Double,
    ) : PendingOperation

    @Serializable
    @SerialName("rename_item")
    data class RenameItem(
        override val remoteMealId: String,
        val remoteItemId: String,
        val displayName: String,
    ) : PendingOperation

    @Serializable
    @SerialName("remove_item")
    data class RemoveItem(
        override val remoteMealId: String,
        val remoteItemId: String,
    ) : PendingOperation

    companion object {
        const val ENTITY_TYPE_MEAL_ITEM = "meal_item"
    }
}
