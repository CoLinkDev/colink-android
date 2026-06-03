package com.colink.android.di

import android.content.Context
import androidx.room.Room
import com.colink.android.data.local.db.CoLinkDatabase
import com.colink.android.data.local.db.dao.DeviceDao
import com.colink.android.data.local.db.dao.FileTransferDao
import com.colink.android.data.local.db.dao.MessageDao
import com.colink.android.data.local.db.dao.TrustedPeerKeyDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CoLinkDatabase =
        Room.databaseBuilder(context, CoLinkDatabase::class.java, "colink.db")
            .addMigrations(
                CoLinkDatabase.MIGRATION_1_2,
                CoLinkDatabase.MIGRATION_2_3,
                CoLinkDatabase.MIGRATION_3_4,
                CoLinkDatabase.MIGRATION_4_5,
                CoLinkDatabase.MIGRATION_5_6,
            )
            .build()

    @Provides
    fun provideDeviceDao(database: CoLinkDatabase): DeviceDao =
        database.deviceDao()

    @Provides
    fun provideMessageDao(database: CoLinkDatabase): MessageDao =
        database.messageDao()

    @Provides
    fun provideFileTransferDao(database: CoLinkDatabase): FileTransferDao =
        database.fileTransferDao()

    @Provides
    fun provideTrustedPeerKeyDao(database: CoLinkDatabase): TrustedPeerKeyDao =
        database.trustedPeerKeyDao()
}
