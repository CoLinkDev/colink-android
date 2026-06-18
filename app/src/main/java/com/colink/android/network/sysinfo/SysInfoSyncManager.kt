package com.colink.android.network.sysinfo

import com.colink.android.network.message.SysInfoStatsPayload
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SysInfoSyncState(
    val sourceDeviceId: String? = null,
    val stats: SysInfoStatsPayload? = null,
    val lastUpdatedAt: Long = 0L,
)

@Singleton
class SysInfoSyncManager @Inject constructor() {
    private val _state = MutableStateFlow(SysInfoSyncState())

    val state: StateFlow<SysInfoSyncState> = _state.asStateFlow()

    fun beginSession(sourceDeviceId: String) {
        val normalized = sourceDeviceId.trim()
        if (normalized.isBlank()) {
            return
        }
        _state.value = SysInfoSyncState(
            sourceDeviceId = normalized,
            lastUpdatedAt = System.currentTimeMillis(),
        )
    }

    fun endSession() {
        _state.value = SysInfoSyncState(lastUpdatedAt = System.currentTimeMillis())
    }

    fun acceptStats(fromDeviceId: String, payload: SysInfoStatsPayload) {
        val normalized = fromDeviceId.trim()
        if (normalized.isBlank()) {
            return
        }
        _state.update { current ->
            if (current.sourceDeviceId != normalized) {
                return@update current
            }
            current.copy(
                stats = payload.copy(
                    cpu = payload.cpu.coerceIn(0.0, 100.0),
                    mem = payload.mem.coerceIn(0.0, 100.0),
                    gpu = payload.gpu?.coerceIn(0.0, 100.0),
                ),
                lastUpdatedAt = System.currentTimeMillis(),
            )
        }
    }
}
