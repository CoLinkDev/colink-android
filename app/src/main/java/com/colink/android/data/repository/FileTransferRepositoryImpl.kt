package com.colink.android.data.repository

import com.colink.android.data.local.db.dao.FileTransferDao
import com.colink.android.data.local.db.entity.toEntity
import com.colink.android.domain.model.FileTransfer
import com.colink.android.domain.repository.FileTransferRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class FileTransferRepositoryImpl @Inject constructor(
    private val fileTransferDao: FileTransferDao,
) : FileTransferRepository {
    override val transfers: Flow<List<FileTransfer>> =
        fileTransferDao.observeTransfers().map { entities -> entities.map { it.toDomain() } }

    override suspend fun save(transfer: FileTransfer) {
        fileTransferDao.upsert(transfer.toEntity())
    }

    override suspend fun get(sessionId: String): FileTransfer? =
        fileTransferDao.getTransfer(sessionId)?.toDomain()

    override suspend fun clearFinished() {
        fileTransferDao.clearFinished()
    }

    override suspend fun failUnfinished(reason: String) {
        fileTransferDao.failUnfinished(reason, System.currentTimeMillis())
    }
}
