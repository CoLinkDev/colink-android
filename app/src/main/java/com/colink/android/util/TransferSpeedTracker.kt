package com.colink.android.util

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object TransferSpeedTracker {
    private class SpeedState(
        var lastBytes: Long,
        var lastTimeMillis: Long,
        var lastSpeed: Long
    )

    private val trackers = ConcurrentHashMap<String, SpeedState>()

    fun getSpeed(sessionId: String, transferredBytes: Long): Long {
        val now = System.currentTimeMillis()
        val state = trackers[sessionId]
        if (state == null) {
            trackers[sessionId] = SpeedState(
                lastBytes = transferredBytes,
                lastTimeMillis = now,
                lastSpeed = 0L
            )
            return 0L
        }

        val timeDiff = now - state.lastTimeMillis
        if (timeDiff >= 1000) {
            val bytesDiff = transferredBytes - state.lastBytes
            if (bytesDiff >= 0) {
                state.lastSpeed = (bytesDiff * 1000) / timeDiff
            }
            state.lastBytes = transferredBytes
            state.lastTimeMillis = now
        }
        return state.lastSpeed
    }

    fun remove(sessionId: String) {
        trackers.remove(sessionId)
    }

    fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.coerceAtLeast(0).toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex += 1
        }
        return if (unitIndex == 0) {
            "${value.toLong()} ${units[unitIndex]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unitIndex])
        }
    }

    fun formatSpeed(bytesPerSecond: Long): String {
        return "${formatBytes(bytesPerSecond)}/s"
    }
}
