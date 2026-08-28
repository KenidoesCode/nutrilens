package com.nutrilens.core.datastore

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.nutrilens.core.common.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session storage backed by the Android Keystore.
 *
 * Tokens are the credentials to a person's dietary history, so they are never
 * written in the clear. `EncryptedSharedPreferences` encrypts both keys and
 * values under a master key held in the hardware-backed Keystore, which means
 * the ciphertext is useless off the device even with filesystem access.
 *
 * DataStore is used elsewhere in this module for ordinary preferences; it has
 * no encrypted variant, so the token store deliberately uses a different
 * mechanism rather than a less protected one.
 */
@Singleton
class EncryptedAuthTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AuthTokenStore {

    private val preferences: SharedPreferences by lazy { createPreferences() }

    private fun createPreferences(): SharedPreferences {
        val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
            .setKeyGenParameterSpec(
                KeyGenParameterSpec.Builder(
                    MASTER_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            .build()

        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        )
    }

    override val tokens: Flow<AuthTokens?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(readBlocking())
        }
        trySend(readBlocking())
        preferences.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.flowOn(ioDispatcher)

    override suspend fun read(): AuthTokens? = withContext(ioDispatcher) { readBlocking() }

    private fun readBlocking(): AuthTokens? {
        val access = preferences.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val refresh = preferences.getString(KEY_REFRESH_TOKEN, null) ?: return null
        val expiry = preferences.getLong(KEY_ACCESS_EXPIRES_AT, 0L)
        // A stored pair with no expiry is corrupt; treating it as signed out is
        // safer than sending a token whose lifetime is unknown.
        if (expiry <= 0L) return null
        return AuthTokens(access, refresh, expiry)
    }

    override suspend fun save(tokens: AuthTokens) = withContext(ioDispatcher) {
        preferences.edit()
            .putString(KEY_ACCESS_TOKEN, tokens.accessToken)
            .putString(KEY_REFRESH_TOKEN, tokens.refreshToken)
            .putLong(KEY_ACCESS_EXPIRES_AT, tokens.accessExpiresAtEpochSeconds)
            .commit()
        Unit
    }

    override suspend fun clear() = withContext(ioDispatcher) {
        // commit(), not apply(): sign-out must not leave a window in which the
        // tokens are still on disk after the UI says the user is signed out.
        preferences.edit().clear().commit()
        Unit
    }

    private companion object {
        const val FILE_NAME = "nutrilens_session"
        const val MASTER_KEY_ALIAS = "nutrilens_session_master_key"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_ACCESS_EXPIRES_AT = "access_expires_at"
    }
}
