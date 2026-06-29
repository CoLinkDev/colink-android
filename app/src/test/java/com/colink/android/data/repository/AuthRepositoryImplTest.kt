package com.colink.android.data.repository

import com.colink.android.data.local.datastore.SettingsDataStore
import com.colink.android.data.remote.api.AuthApi
import com.colink.android.data.remote.dto.ApiEnvelope
import com.colink.android.data.remote.dto.RefreshResponseDto
import com.colink.android.domain.model.AppSettings
import com.colink.android.domain.model.Session
import com.colink.android.domain.repository.DeviceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthRepositoryImplTest {
    private val authApi = mockk<AuthApi>()
    private val settingsDataStore = mockk<SettingsDataStore>()
    private val deviceRepository = mockk<DeviceRepository>(relaxed = true)

    @Test
    fun concurrentSessionRequestsShareSingleTokenRefresh() = runTest {
        var storedSession = expiringSession()
        every { settingsDataStore.session } returns MutableStateFlow(storedSession)
        coEvery { settingsDataStore.currentSession() } answers { storedSession }
        coEvery { settingsDataStore.currentSettings() } returns AppSettings("https://example.test")
        coEvery { settingsDataStore.saveSession(any()) } coAnswers {
            storedSession = firstArg()
        }
        coEvery { authApi.refresh(any(), any()) } coAnswers {
            delay(100)
            ApiEnvelope(
                code = 0,
                data = RefreshResponseDto(
                    token = "new-access",
                    refreshToken = "new-refresh",
                    expiresIn = 3_600,
                ),
                message = "ok",
            )
        }
        val repository = AuthRepositoryImpl(authApi, settingsDataStore, deviceRepository)

        val sessions = List(8) { async { repository.currentSession().getOrThrow() } }.awaitAll()

        assertEquals(List(8) { "new-access" }, sessions.map(Session::accessToken))
        coVerify(exactly = 1) { authApi.refresh(any(), any()) }
        coVerify(exactly = 1) { settingsDataStore.saveSession(any()) }
    }

    @Test
    fun validSessionDoesNotCallRefreshEndpoint() = runTest {
        val session = expiringSession().copy(
            accessTokenRefreshAt = System.currentTimeMillis() + 60_000,
        )
        every { settingsDataStore.session } returns MutableStateFlow(session)
        coEvery { settingsDataStore.currentSession() } returns session
        val repository = AuthRepositoryImpl(authApi, settingsDataStore, deviceRepository)

        val result = repository.currentSession().getOrThrow()

        assertEquals(session, result)
        coVerify(exactly = 0) { authApi.refresh(any(), any()) }
    }

    private fun expiringSession(): Session = Session(
        userId = "user-1",
        accessToken = "old-access",
        refreshToken = "old-refresh",
        accessTokenExpiresAt = System.currentTimeMillis() + 60_000,
        accessTokenRefreshAt = 0,
        email = "alice@example.test",
    )
}
