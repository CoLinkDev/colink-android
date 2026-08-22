package com.colink.android.ui.castboard.bridge

import androidx.webkit.JavaScriptReplyProxy
import com.colink.android.network.message.MUSIC_LYRIC_TYPE
import com.colink.android.network.message.MUSIC_PROGRESS_TYPE
import com.colink.android.network.message.MUSIC_TRACK_TYPE
import com.colink.android.network.message.SYSINFO_STATS_TYPE
import com.colink.android.network.message.MusicLyricPayload
import com.colink.android.network.message.MusicProgressPayload
import com.colink.android.network.message.MusicTrackPayload
import com.colink.android.network.music.MusicSyncState
import com.colink.android.network.sysinfo.SysInfoSyncState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

private val json = Json {
    encodeDefaults = true
}

class MusicBridge {
    private var replyProxy: JavaScriptReplyProxy? = null
    private var pageReady = false
    private var forceSync = true
    private var lastState: MusicSyncState = MusicSyncState()
    private var lastSysInfoState: SysInfoSyncState = SysInfoSyncState()
    private var lastTrack: MusicTrackPayload? = null
    private var lastLyric: MusicLyricPayload? = null
    private var lastProgress: MusicProgressPayload? = null

    fun unbind() {
        replyProxy = null
        pageReady = false
        forceSync = true
        lastTrack = null
        lastLyric = null
        lastProgress = null
        lastState = MusicSyncState()
        lastSysInfoState = SysInfoSyncState()
    }

    fun markPageReady(replyProxy: JavaScriptReplyProxy) {
        this.replyProxy = replyProxy
        pageReady = true
        forceSync = true
        flush()
        flushSysInfo()
    }

    fun markPageLoading() {
        replyProxy = null
        pageReady = false
        forceSync = true
    }

    fun sync(state: MusicSyncState) {
        lastState = state
        flush()
    }

    fun syncSysInfo(state: SysInfoSyncState) {
        lastSysInfoState = state
        flushSysInfo()
    }

    private fun flush() {
        if (!pageReady) {
            return
        }

        val state = lastState
        val track = state.track ?: MusicTrackPayload()
        val trackId = state.track?.trackId.orEmpty()
        val lyric = state.lyric ?: MusicLyricPayload(trackId = trackId)
        val progress = state.progress ?: MusicProgressPayload(trackId = trackId, progress = 0L, paused = true)

        if (forceSync || track != lastTrack) {
            dispatchBusiness(MUSIC_TRACK_TYPE, track)
            lastTrack = track
        }

        if (forceSync || lyric != lastLyric) {
            dispatchBusiness(MUSIC_LYRIC_TYPE, lyric)
            lastLyric = lyric
        }

        if (forceSync || progress != lastProgress) {
            dispatchBusiness(MUSIC_PROGRESS_TYPE, progress)
            lastProgress = progress
        }

        forceSync = false
    }

    private fun flushSysInfo() {
        if (!pageReady) {
            return
        }
        val stats = lastSysInfoState.stats ?: return
        dispatchBusiness(SYSINFO_STATS_TYPE, stats)
    }

    private inline fun <reified T> dispatchBusiness(type: String, payload: T) {
        val proxy = replyProxy ?: return
        val message = buildJsonObject {
            put("channel", "castboard")
            put("kind", "event")
            put("type", "business")
            put("payload", buildJsonObject {
                put("type", type)
                put("payload", json.encodeToJsonElement(payload))
            })
        }
        proxy.postMessage(json.encodeToString(message))
    }
}
