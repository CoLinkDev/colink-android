package com.colink.android.data.repository

import android.util.Log
import com.colink.android.crypto.KeyManager
import com.colink.android.data.local.DeviceNameProvider
import com.colink.android.data.local.datastore.SettingsDataStore
import com.colink.android.data.local.db.dao.DeviceDao
import com.colink.android.data.local.db.dao.TrustedPeerKeyDao
import com.colink.android.data.local.db.entity.DeviceEntity
import com.colink.android.data.remote.api.DeviceApi
import com.colink.android.data.remote.dto.ApiEnvelope
import com.colink.android.data.remote.dto.DeviceDto
import com.colink.android.data.remote.dto.DeviceListResponseDto
import com.colink.android.domain.model.AppSettings
import com.colink.android.domain.model.Session
import com.colink.android.network.cloud.CloudRuntimeState
import com.colink.android.network.lan.LanRuntimeState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceRepositoryImplTest {
    private val deviceApi = mockk<DeviceApi>()
    private val settingsDataStore = mockk<SettingsDataStore>()
    private val deviceDao = mockk<DeviceDao>()
    private val trustedPeerKeyDao = mockk<TrustedPeerKeyDao>()
    private val keyManager = mockk<KeyManager>()
    private val deviceNameProvider = mockk<DeviceNameProvider>()

    @Test
    fun concurrentSyncsAreSerializedAndKeepTheSecondSnapshot() = runTest {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        try {
        var persisted = emptyList<DeviceEntity>()
        val observedDevices = MutableStateFlow(emptyList<DeviceEntity>())
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val firstResponse = CompletableDeferred<ApiEnvelope<DeviceListResponseDto>>()
        val secondResponse = CompletableDeferred<ApiEnvelope<DeviceListResponseDto>>()
        var requestCount = 0

        every { deviceDao.observeDevices() } returns observedDevices
        coEvery { deviceDao.getDevices() } answers { persisted }
        coEvery { deviceDao.replaceAll(any()) } coAnswers {
            persisted = firstArg()
            observedDevices.value = persisted
        }
        coEvery { trustedPeerKeyDao.getAll() } returns emptyList()
        coEvery { settingsDataStore.currentSettings() } returns AppSettings("https://example.test")
        coEvery { settingsDataStore.currentDeviceIdentity() } returns null
        coEvery { deviceApi.listDevices(any(), any()) } coAnswers {
            when (requestCount++) {
                0 -> {
                    firstStarted.complete(Unit)
                    firstResponse.await()
                }
                else -> {
                    secondStarted.complete(Unit)
                    secondResponse.await()
                }
            }
        }

        val repository = DeviceRepositoryImpl(
            deviceApi = deviceApi,
            settingsDataStore = settingsDataStore,
            deviceDao = deviceDao,
            trustedPeerKeyDao = trustedPeerKeyDao,
            keyManager = keyManager,
            deviceNameProvider = deviceNameProvider,
            lanRuntimeState = LanRuntimeState(),
            cloudRuntimeState = CloudRuntimeState(),
        )
        val session = Session("user", "token", "refresh", 1_000, 500)

        val first = async { repository.syncDevices(session) }
        firstStarted.await()
        val second = async { repository.syncDevices(session) }
        testScheduler.runCurrent()
        assertEquals(1, requestCount)

        firstResponse.complete(response("First"))
        first.await().getOrThrow()
        secondStarted.await()
        secondResponse.complete(response("Second"))
        second.await().getOrThrow()

        assertEquals("Second", persisted.single().name)
        } finally {
            unmockkStatic(Log::class)
        }
    }

    private fun response(name: String): ApiEnvelope<DeviceListResponseDto> =
        ApiEnvelope(
            code = 0,
            data = DeviceListResponseDto(
                devices = listOf(
                    DeviceDto(
                        deviceId = "device-a",
                        name = name,
                        type = "windows",
                        online = true,
                        publicKey = "",
                    ),
                ),
            ),
            message = "ok",
        )
}
