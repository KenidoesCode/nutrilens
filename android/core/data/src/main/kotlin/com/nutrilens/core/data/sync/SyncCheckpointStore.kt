package com.nutrilens.core.data.sync

import com.nutrilens.core.datastore.UserPreferencesStore
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The cursor marking how far the device has caught up with the server.
 *
 * A narrow interface rather than the whole preferences store, for two reasons:
 * the sync engine has no business reading the user's language, and depending on
 * a concrete `Context`-bound class would make the engine's behaviour testable
 * only under Robolectric.
 */
interface SyncCheckpointStore {

    /** Millis since epoch of the last fully successful sync, or `null`. */
    val lastSyncedAtEpochMillis: Flow<Long?>

    suspend fun setLastSyncedAt(epochMillis: Long)
}

@Singleton
class PreferencesSyncCheckpointStore @Inject constructor(
    private val preferences: UserPreferencesStore,
) : SyncCheckpointStore {

    override val lastSyncedAtEpochMillis: Flow<Long?> = preferences.lastSyncedAtEpochMillis

    override suspend fun setLastSyncedAt(epochMillis: Long) {
        preferences.setLastSyncedAt(epochMillis)
    }
}
