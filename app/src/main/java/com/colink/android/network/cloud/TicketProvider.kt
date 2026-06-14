package com.colink.android.network.cloud

import com.colink.android.data.local.datastore.SettingsDataStore
import com.colink.android.data.remote.api.WsApi
import com.colink.android.data.remote.api.apiEndpoint
import com.colink.android.data.remote.dto.WsTicketRequestDto
import com.colink.android.data.remote.dto.requireData
import com.colink.android.domain.repository.AuthRepository
import com.colink.android.domain.repository.DeviceRepository
import javax.inject.Inject

data class WsTicket(
    val serverUrl: String,
    val ticket: String,
)

class TicketProvider @Inject constructor(
    private val authRepository: AuthRepository,
    private val deviceRepository: DeviceRepository,
    private val settingsDataStore: SettingsDataStore,
    private val wsApi: WsApi,
) {
    suspend fun obtainTicket(): Result<WsTicket> =
        runCatching {
            val session = authRepository.currentSession().getOrThrow()
            val identity = deviceRepository.ensureDeviceIdentity(session).getOrThrow()
            val serverUrl = settingsDataStore.currentSettings().serverUrl
            val response = wsApi
                .ticket(
                    url = apiEndpoint(serverUrl, "/api/v1/ws/ticket"),
                    authorization = "Bearer ${session.accessToken}",
                    request = WsTicketRequestDto(identity.deviceId),
                )
                .requireData()
            WsTicket(serverUrl = serverUrl, ticket = response.ticket)
        }
}
