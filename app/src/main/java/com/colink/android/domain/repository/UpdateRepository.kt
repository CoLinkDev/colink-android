package com.colink.android.domain.repository

import com.colink.android.domain.model.AppUpdate
import com.colink.android.domain.model.UpdateDownloadState
import kotlinx.coroutines.flow.Flow

interface UpdateRepository {
    suspend fun checkForUpdate(): Result<AppUpdate?>
    fun downloadAndInstall(update: AppUpdate): Flow<UpdateDownloadState>
}
