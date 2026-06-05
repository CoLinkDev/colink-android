package com.colink.android.network.music

import com.colink.android.network.message.MusicLyricPayload
import com.colink.android.network.message.MusicProgressPayload
import com.colink.android.network.message.MusicTrackPayload
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MusicSyncState(
    val sourceDeviceId: String? = null,
    val track: MusicTrackPayload? = null,
    val lyric: MusicLyricPayload? = null,
    val progress: MusicProgressPayload? = null,
    val lastUpdatedAt: Long = 0L,
)

@Singleton
class MusicSyncManager @Inject constructor() {
    private val _state = MutableStateFlow(MusicSyncState())

    val state: StateFlow<MusicSyncState> = _state.asStateFlow()

    fun beginSession(sourceDeviceId: String) {
        val normalized = sourceDeviceId.trim()
        if (normalized.isBlank()) {
            return
        }
        _state.value = MusicSyncState(
            sourceDeviceId = normalized,
            lastUpdatedAt = System.currentTimeMillis(),
        )
    }

    fun endSession() {
        _state.value = MusicSyncState(lastUpdatedAt = System.currentTimeMillis())
    }

    fun reset() {
        endSession()
    }

    fun acceptTrack(fromDeviceId: String, payload: MusicTrackPayload) {
        updateIfSourceMatches(fromDeviceId) { current ->
            val trackId = payload.trackId?.trim()?.takeIf { it.isNotBlank() }
            if (trackId == null) {
                current.copy(
                    track = null,
                    lyric = null,
                    progress = null,
                )
            } else {
                current.copy(
                    track = payload.copy(trackId = trackId),
                    lyric = null,
                    progress = null,
                )
            }
        }
    }

    fun acceptLyric(fromDeviceId: String, payload: MusicLyricPayload) {
        updateIfSourceMatches(fromDeviceId) { current ->
            val trackId = current.track?.trackId?.takeIf { it.isNotBlank() }
            if (trackId == null || payload.trackId != trackId) {
                current
            } else {
                current.copy(lyric = payload)
            }
        }
    }

    fun acceptProgress(fromDeviceId: String, payload: MusicProgressPayload) {
        updateIfSourceMatches(fromDeviceId) { current ->
            val trackId = current.track?.trackId?.takeIf { it.isNotBlank() }
            if (trackId == null || payload.trackId != trackId) {
                current
            } else {
                current.copy(progress = payload)
            }
        }
    }

    private inline fun updateIfSourceMatches(
        fromDeviceId: String,
        transform: (MusicSyncState) -> MusicSyncState,
    ) {
        val normalized = fromDeviceId.trim()
        if (normalized.isBlank()) {
            return
        }
        _state.update { current ->
            if (current.sourceDeviceId != normalized) {
                return@update current
            }
            transform(current).copy(lastUpdatedAt = System.currentTimeMillis())
        }
    }
}
