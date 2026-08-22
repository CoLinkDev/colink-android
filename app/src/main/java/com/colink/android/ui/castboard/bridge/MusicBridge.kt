package com.colink.android.ui.castboard.bridge

import android.webkit.WebView
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

private val json = Json {
    encodeDefaults = true
}

class MusicBridge {
    private var webView: WebView? = null
    private var pageReady = false
    private var forceSync = true
    private var lastState: MusicSyncState = MusicSyncState()
    private var lastSysInfoState: SysInfoSyncState = SysInfoSyncState()
    private var lastTrack: MusicTrackPayload? = null
    private var lastLyric: MusicLyricPayload? = null
    private var lastProgress: MusicProgressPayload? = null

    fun bind(webView: WebView) {
        this.webView = webView
    }

    fun unbind() {
        webView = null
        pageReady = false
        forceSync = true
        lastTrack = null
        lastLyric = null
        lastProgress = null
        lastState = MusicSyncState()
        lastSysInfoState = SysInfoSyncState()
    }

    fun markPageReady() {
        pageReady = true
        forceSync = true
        flush()
        flushSysInfo()
    }

    fun markPageLoading() {
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
        val view = webView ?: return
        if (!pageReady) {
            return
        }

        val state = lastState
        val track = state.track ?: MusicTrackPayload()
        val trackId = state.track?.trackId.orEmpty()
        val lyric = state.lyric ?: MusicLyricPayload(trackId = trackId)
        val progress = state.progress ?: MusicProgressPayload(trackId = trackId, progress = 0L, paused = true)

        if (forceSync || track != lastTrack) {
            dispatchBusiness(view, MUSIC_TRACK_TYPE, track)
            lastTrack = track
        }

        if (forceSync || lyric != lastLyric) {
            dispatchBusiness(view, MUSIC_LYRIC_TYPE, lyric)
            lastLyric = lyric
        }

        if (forceSync || progress != lastProgress) {
            dispatchBusiness(view, MUSIC_PROGRESS_TYPE, progress)
            lastProgress = progress
        }

        forceSync = false
    }

    private fun flushSysInfo() {
        val view = webView ?: return
        if (!pageReady) {
            return
        }
        val stats = lastSysInfoState.stats ?: return
        dispatchBusiness(view, SYSINFO_STATS_TYPE, stats)
    }

    private inline fun <reified T> dispatchBusiness(view: WebView, type: String, payload: T) {
        val script = "window.handleCoLinkBusinessEvent(${json.encodeToString(type)}, ${json.encodeToString(payload)})"
        view.post {
            view.evaluateJavascript(script, null)
        }
    }
}
