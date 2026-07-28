package com.colink.android.data.repository

import com.colink.android.data.local.datastore.SettingsDataStore
import com.colink.android.data.remote.api.AuthApi
import com.colink.android.data.remote.api.apiEndpoint
import com.colink.android.data.remote.dto.LoginRequestDto
import com.colink.android.data.remote.dto.LogoutRequestDto
import com.colink.android.data.remote.dto.RefreshRequestDto
import com.colink.android.data.remote.dto.RegisterRequestDto
import com.colink.android.data.remote.dto.ApiException
import com.colink.android.data.remote.dto.requireData
import com.colink.android.domain.model.Session
import com.colink.android.domain.repository.AuthRepository
import com.colink.android.domain.repository.DeviceRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException

private const val LEGACY_ACCESS_TOKEN_TTL_MILLIS = 15 * 60 * 1000L
private const val LONG_ACCESS_TOKEN_REFRESH_BUFFER_MILLIS = 60 * 60 * 1000L
private const val SHORT_ACCESS_TOKEN_REFRESH_PERCENT = 90L
private const val REMOTE_LOGOUT_TIMEOUT_MILLIS = 3_000L

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val authApi: AuthApi,
    private val settingsDataStore: SettingsDataStore,
    private val deviceRepository: DeviceRepository,
) : AuthRepository {
    private val refreshMutex = Mutex()
    private val sessionMutex = Mutex()

    override val session: Flow<Session?> = settingsDataStore.session

    override suspend fun bootstrap(): Result<Unit> =
        runCatching {
            deviceRepository.ensureLocalDeviceIdentity().getOrThrow()
            deviceRepository.resetDevicePresence().getOrThrow()
            deviceRepository.listLocalDevices().getOrThrow()
        }

    override suspend fun refreshProfile(): Result<Unit> =
        runCatching {
            val session = currentSession().getOrThrow()
            val profile = authApi.getProfile(
                url = apiEndpoint(settingsDataStore.currentSettings().serverUrl, "/api/v1/me"),
                authorization = bearer(session.accessToken),
            ).requireData()
            saveSessionIfCurrent(
                expected = session,
                updated = session.copy(
                    username = profile.username,
                    email = profile.email,
                ),
            )
        }

    override suspend fun login(identifier: String, password: String): Result<Unit> =
        runCatching {
            val response = authApi
                .login(
                    url = apiEndpoint(settingsDataStore.currentSettings().serverUrl, "/api/v1/auth/login"),
                    request = LoginRequestDto(identifier.trim(), password),
                )
                .requireData()
            saveSessionAndPrepareDevice(response.userId, response.token, response.refreshToken, response.expiresIn)
        }

    override suspend fun register(email: String, username: String, password: String): Result<Unit> =
        runCatching {
            val response = authApi
                .register(
                    url = apiEndpoint(settingsDataStore.currentSettings().serverUrl, "/api/v1/auth/register"),
                    request = RegisterRequestDto(email.trim(), username.trim(), password),
                )
                .requireData()
            saveSessionAndPrepareDevice(response.userId, response.token, response.refreshToken, response.expiresIn)
        }

    override suspend fun logout(): Result<Unit> =
        runCatching {
            val session = sessionMutex.withLock {
                settingsDataStore.currentSession().also { settingsDataStore.clearSession() }
            }
            deviceRepository.clearCloudTrust().getOrThrow()
            if (session != null) {
                revokeSessionBestEffort(session)
            }
        }

    override suspend fun currentSession(): Result<Session> =
        try {
            val session = settingsDataStore.currentSession() ?: error("not logged in")
            Result.success(refreshIfNeeded(session))
        } catch (error: Throwable) {
            if (isAuthError(error)) {
                clearCloudSession()
            }
            Result.failure(error)
        }

    private suspend fun refreshIfNeeded(session: Session): Session =
        refreshMutex.withLock {
            val latest = settingsDataStore.currentSession() ?: error("not logged in")
            if (!latest.isExpiringSoon()) {
                latest
            } else {
                val response = authApi
                    .refresh(
                        url = apiEndpoint(settingsDataStore.currentSettings().serverUrl, "/api/v1/auth/refresh"),
                        request = RefreshRequestDto(latest.refreshToken),
                    )
                    .requireData()
                val timing = sessionTiming(response.expiresIn)
                val refreshedSession = Session(
                    userId = latest.userId,
                    accessToken = response.token,
                    refreshToken = response.refreshToken,
                    accessTokenExpiresAt = timing.expiresAt,
                    accessTokenRefreshAt = timing.refreshAt,
                    email = latest.email,
                )
                sessionMutex.withLock {
                    check(isCurrentSession(latest)) { "not logged in" }
                    settingsDataStore.saveSession(refreshedSession)
                }
                refreshedSession
            }
        }

    private suspend fun saveSessionAndPrepareDevice(
        userId: String,
        token: String,
        refreshToken: String,
        expiresIn: Long?,
    ) {
        val timing = sessionTiming(expiresIn)
        val initialSession = Session(
            userId = userId,
            accessToken = token,
            refreshToken = refreshToken,
            accessTokenExpiresAt = timing.expiresAt,
            accessTokenRefreshAt = timing.refreshAt,
        )
        val profile = authApi.getProfile(
            url = apiEndpoint(settingsDataStore.currentSettings().serverUrl, "/api/v1/me"),
            authorization = bearer(initialSession.accessToken),
        ).requireData()
        val session = initialSession.copy(username = profile.username, email = profile.email)
        deviceRepository.ensureDeviceIdentity(session).getOrThrow()
        settingsDataStore.saveSession(session)
        deviceRepository.syncDevices(session).getOrThrow()
    }

    private suspend fun clearCloudSession() {
        sessionMutex.withLock {
            settingsDataStore.clearSession()
        }
        deviceRepository.clearCloudTrust().getOrThrow()
    }

    private suspend fun saveSessionIfCurrent(expected: Session, updated: Session) {
        sessionMutex.withLock {
            if (isCurrentSession(expected)) {
                settingsDataStore.saveSession(updated)
            }
        }
    }

    private suspend fun isCurrentSession(expected: Session): Boolean =
        settingsDataStore.currentSession()?.let { current ->
            current.userId == expected.userId && current.refreshToken == expected.refreshToken
        } == true

    private suspend fun revokeSessionBestEffort(session: Session) {
        withTimeoutOrNull(REMOTE_LOGOUT_TIMEOUT_MILLIS) {
            try {
                authApi.logout(
                    url = apiEndpoint(settingsDataStore.currentSettings().serverUrl, "/api/v1/auth/logout"),
                    authorization = bearer(session.accessToken),
                    request = LogoutRequestDto(session.refreshToken),
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
            }
        }
    }

    private fun isAuthError(error: Throwable): Boolean =
        when (error) {
            is ApiException -> error.code in setOf(1020, 1021, 1030)
            is HttpException -> error.code() == 401
            else -> error.message?.equals("unauthorized", ignoreCase = true) == true ||
                error.message?.equals("invalid refresh token", ignoreCase = true) == true ||
                error.message?.equals("token revoked", ignoreCase = true) == true
        }

    private fun bearer(token: String): String = "Bearer $token"

    private fun sessionTiming(expiresInSeconds: Long?): SessionTiming {
        val now = System.currentTimeMillis()
        val expiresInMillis = (expiresInSeconds?.coerceAtLeast(0L)?.times(1000L))
            ?: LEGACY_ACCESS_TOKEN_TTL_MILLIS
        val refreshAfterMillis = if (expiresInMillis <= LONG_ACCESS_TOKEN_REFRESH_BUFFER_MILLIS) {
            expiresInMillis * SHORT_ACCESS_TOKEN_REFRESH_PERCENT / 100L
        } else {
            expiresInMillis - LONG_ACCESS_TOKEN_REFRESH_BUFFER_MILLIS
        }
        return SessionTiming(
            expiresAt = now + expiresInMillis,
            refreshAt = now + refreshAfterMillis.coerceAtLeast(0L),
        )
    }

    private data class SessionTiming(
        val expiresAt: Long,
        val refreshAt: Long,
    )
}
