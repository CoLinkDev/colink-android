package com.colink.android.util

import java.net.URI

fun normalizeServerUrl(value: String): String? {
    val normalized = value.trim().trimEnd('/')
    if (normalized.isBlank()) {
        return null
    }

    val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
        return null
    }
    return normalized
}
