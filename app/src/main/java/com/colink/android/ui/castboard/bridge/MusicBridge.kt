package com.colink.android.ui.castboard.bridge

import android.webkit.WebView
import com.colink.android.network.message.MusicLyricLinePayload
import com.colink.android.network.message.MusicTrackPayload
import com.colink.android.network.music.MusicSyncState
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val MUSIC_EVENT_TRACK = "Track"
private const val MUSIC_EVENT_LYRIC = "Lyric"
private const val MUSIC_EVENT_PROGRESS = "PlayerProgress"

private val json = Json {
    encodeDefaults = true
}

@Serializable
private data class LegacyTrackEvent(
    val author: String = "",
    val title: String = "",
    val album: String = "",
    val cover: String = "",
    val duration: Long = 0,
    val durationHuman: String = "0:00",
    val url: String = "",
    val id: String = "",
    val isVideo: Boolean = false,
    val isAdvertisement: Boolean = false,
    val inLibrary: Boolean = false,
)

@Serializable
private data class LegacyLyricEvent(
    val lines: List<LegacyLyricLine> = emptyList(),
    val title: String = "",
    val author: String = "",
    val duration: Long = 0,
    val hasLyric: Boolean = false,
    val hasTranslatedLyric: Boolean = false,
    val hasKaraokeLyric: Boolean = false,
    val lrc: String = "",
    val translatedLyric: String = "",
    val karaokeLyric: String = "",
)

@Serializable
private data class LegacyLyricLine(
    val time: Double,
    val text: String,
)

@Serializable
private data class LegacyProgressEvent(
    val progress: Long,
    val paused: Boolean,
)

class MusicBridge {
    private var webView: WebView? = null
    private var pageReady = false
    private var forceSync = true
    private var lastState: MusicSyncState = MusicSyncState()
    private var lastTrackEvent: LegacyTrackEvent? = null
    private var lastLyricEvent: LegacyLyricEvent? = null
    private var lastProgressEvent: LegacyProgressEvent? = null

    fun bind(webView: WebView) {
        this.webView = webView
    }

    fun unbind() {
        webView = null
        pageReady = false
        forceSync = true
        lastTrackEvent = null
        lastLyricEvent = null
        lastProgressEvent = null
        lastState = MusicSyncState()
    }

    fun markPageReady() {
        pageReady = true
        forceSync = true
        flush()
    }

    fun sync(state: MusicSyncState) {
        lastState = state
        flush()
    }

    private fun flush() {
        val view = webView ?: return
        if (!pageReady) {
            return
        }

        val state = lastState
        val trackEvent = state.track.toLegacyTrackEvent()
        val lyricEvent = state.toLegacyLyricEvent()
        val progressEvent = state.toLegacyProgressEvent()

        if (forceSync || trackEvent != lastTrackEvent) {
            dispatch(view, MUSIC_EVENT_TRACK, trackEvent)
            lastTrackEvent = trackEvent
        }

        if (forceSync || lyricEvent != lastLyricEvent) {
            dispatch(view, MUSIC_EVENT_LYRIC, lyricEvent)
            lastLyricEvent = lyricEvent
        }

        if (forceSync || progressEvent != lastProgressEvent) {
            dispatch(view, MUSIC_EVENT_PROGRESS, progressEvent)
            lastProgressEvent = progressEvent
        }

        forceSync = false
    }

    private inline fun <reified T> dispatch(view: WebView, event: String, payload: T) {
        val script = "window.handleMusicEvent(${json.encodeToString(event)}, ${json.encodeToString(payload)})"
        view.post {
            view.evaluateJavascript(script, null)
        }
    }

    private fun MusicSyncState.toLegacyLyricEvent(): LegacyLyricEvent {
        val lyric = lyric ?: return emptyLyricEvent()
        val title = track?.title.orEmpty()
        val author = track?.artists.orEmpty().joinToString(", ")
        val durationSeconds = (track?.duration ?: 0L) / 1000L
        return LegacyLyricEvent(
            lines = lyric.lines.orEmpty().map { it.toLegacyLine() },
            title = title,
            author = author,
            duration = durationSeconds,
            hasLyric = lyric.lines?.isNotEmpty() == true,
            hasTranslatedLyric = lyric.translatedLines?.isNotEmpty() == true,
            hasKaraokeLyric = false,
            lrc = "",
            translatedLyric = "",
            karaokeLyric = "",
        )
    }

    private fun MusicSyncState.toLegacyProgressEvent(): LegacyProgressEvent =
        LegacyProgressEvent(
            progress = progress?.progress ?: 0L,
            paused = progress?.paused ?: true,
        )

    private fun MusicTrackPayload?.toLegacyTrackEvent(): LegacyTrackEvent {
        val track = this ?: return emptyTrackEvent()
        val durationSeconds = (track.duration ?: 0L) / 1000L
        return LegacyTrackEvent(
            author = track.artists.orEmpty().joinToString(", "),
            title = track.title.orEmpty(),
            album = track.album.orEmpty(),
            cover = track.coverUrl.orEmpty().ifBlank {
                track.coverData?.let { "data:image/png;base64,$it" }.orEmpty()
            },
            duration = durationSeconds,
            durationHuman = formatDuration(durationSeconds),
            id = track.trackId.orEmpty(),
        )
    }

    private fun MusicLyricLinePayload.toLegacyLine(): LegacyLyricLine =
        LegacyLyricLine(
            time = time / 1000.0,
            text = text,
        )

    private fun emptyTrackEvent(): LegacyTrackEvent = LegacyTrackEvent()

    private fun emptyLyricEvent(): LegacyLyricEvent = LegacyLyricEvent()

    private fun formatDuration(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        val minutes = total / 60
        val remain = total % 60
        return "$minutes:${remain.toString().padStart(2, '0')}"
    }
}
