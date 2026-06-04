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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

private const val ACCESS_TOKEN_TTL_MILLIS = 15 * 60 * 1000L

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val authApi: AuthApi,
    private val settingsDataStore: SettingsDataStore,
    private val deviceRepository: DeviceRepository,
) : AuthRepository {
    private val refreshMutex = Mutex()

    override val session: Flow<Session?> = settingsDataStore.session

    override suspend fun bootstrap(): Result<Unit> =
        runCatching {
            deviceRepository.ensureLocalDeviceIdentity().getOrThrow()
            deviceRepository.resetDevicePresence().getOrThrow()
            val session = settingsDataStore.currentSession()
            if (session == null) {
                deviceRepository.listLocalDevices().getOrThrow()
                return@runCatching
            }
            val refreshed = try {
                refreshIfNeeded(session)
            } catch (error: Throwable) {
                if (isAuthError(error)) {
                    clearCloudSession()
                } else {
                    deviceRepository.listLocalDevices().getOrThrow()
                }
                return@runCatching
            }
            try {
                deviceRepository.ensureDeviceIdentity(refreshed).getOrThrow()
                deviceRepository.syncDevices(refreshed).getOrThrow()
            } catch (error: Throwable) {
                if (isAuthError(error)) {
                    clearCloudSession()
                } else {
                    deviceRepository.listLocalDevices().getOrThrow()
                }
            }
        }

    override suspend fun login(identifier: String, password: String): Result<Unit> =
        runCatching {
            val response = authApi
                .login(
                    url = apiEndpoint(settingsDataStore.currentSettings().serverUrl, "/api/v1/auth/login"),
                    request = LoginRequestDto(identifier.trim(), password),
                )
                .requireData()
            saveSessionAndPrepareDevice(response.userId, response.token, response.refreshToken)
        }

    override suspend fun register(email: String, username: String, password: String): Result<Unit> =
        runCatching {
            val response = authApi
                .register(
                    url = apiEndpoint(settingsDataStore.currentSettings().serverUrl, "/api/v1/auth/register"),
                    request = RegisterRequestDto(email.trim(), username.trim(), password),
                )
                .requireData()
            saveSessionAndPrepareDevice(response.userId, response.token, response.refreshToken)
        }

    override suspend fun logout(): Result<Unit> =
        runCatching {
            val session = settingsDataStore.currentSession()
            if (session != null) {
                runCatching {
                    authApi.logout(
                        url = apiEndpoint(settingsDataStore.currentSettings().serverUrl, "/api/v1/auth/logout"),
                        authorization = bearer(session.accessToken),
                        request = LogoutRequestDto(session.refreshToken),
                    )
                }
            }
            settingsDataStore.clearSession()
            deviceRepository.clearCloudTrust().getOrThrow()
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
                Session(
                    userId = latest.userId,
                    accessToken = response.token,
                    refreshToken = response.refreshToken,
                    accessTokenExpiresAt = System.currentTimeMillis() + ACCESS_TOKEN_TTL_MILLIS,
                ).also { settingsDataStore.saveSession(it) }
            }
        }

    private suspend fun saveSessionAndPrepareDevice(
        userId: String,
        token: String,
        refreshToken: String,
    ) {
        val session = Session(
            userId = userId,
            accessToken = token,
            refreshToken = refreshToken,
            accessTokenExpiresAt = System.currentTimeMillis() + ACCESS_TOKEN_TTL_MILLIS,
        )
        deviceRepository.ensureDeviceIdentity(session).getOrThrow()
        settingsDataStore.saveSession(session)
        deviceRepository.syncDevices(session).getOrThrow()
    }

    private suspend fun clearCloudSession() {
        settingsDataStore.clearSession()
        deviceRepository.clearCloudTrust().getOrThrow()
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
}
