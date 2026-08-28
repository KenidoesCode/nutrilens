package com.nutrilens.core.datastore.di

import com.nutrilens.core.datastore.AuthTokenStore
import com.nutrilens.core.datastore.EncryptedAuthTokenStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataStoreModule {

    @Binds
    @Singleton
    abstract fun bindAuthTokenStore(implementation: EncryptedAuthTokenStore): AuthTokenStore
}
