package com.colink.android.domain.repository

import com.colink.android.domain.model.Session
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    val session: Flow<Session?>

    suspend fun bootstrap(): Result<Unit>

    suspend fun refreshProfile(): Result<Unit>

    suspend fun login(identifier: String, password: String): Result<Unit>

    suspend fun register(email: String, username: String, password: String): Result<Unit>

    suspend fun logout(): Result<Unit>

    suspend fun currentSession(): Result<Session>
}
