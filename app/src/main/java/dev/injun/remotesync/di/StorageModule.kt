package dev.injun.remotesync.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.injun.remotesync.data.DefaultStorageFactory
import dev.injun.remotesync.sync.StorageFactory

@Module
@InstallIn(SingletonComponent::class)
interface StorageModule {

    @Binds
    fun bindStorageFactory(impl: DefaultStorageFactory): StorageFactory
}
