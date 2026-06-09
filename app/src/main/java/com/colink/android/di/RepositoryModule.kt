package com.colink.android.di

import com.colink.android.data.repository.AuthRepositoryImpl
import com.colink.android.data.repository.DeviceRepositoryImpl
import com.colink.android.data.repository.FileTransferRepositoryImpl
import com.colink.android.data.repository.MessageRepositoryImpl
import com.colink.android.data.repository.UpdateRepositoryImpl
import com.colink.android.domain.repository.AuthRepository
import com.colink.android.domain.repository.DeviceRepository
import com.colink.android.domain.repository.FileTransferRepository
import com.colink.android.domain.repository.MessageRepository
import com.colink.android.domain.repository.UpdateRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindDeviceRepository(impl: DeviceRepositoryImpl): DeviceRepository

    @Binds
    @Singleton
    abstract fun bindMessageRepository(impl: MessageRepositoryImpl): MessageRepository

    @Binds
    @Singleton
    abstract fun bindFileTransferRepository(impl: FileTransferRepositoryImpl): FileTransferRepository

    @Binds
    @Singleton
    abstract fun bindUpdateRepository(impl: UpdateRepositoryImpl): UpdateRepository
}
