package com.colink.android.domain.repository

import com.colink.android.domain.model.AppUpdate

interface UpdateRepository {
    suspend fun checkForUpdate(): Result<AppUpdate?>
}
