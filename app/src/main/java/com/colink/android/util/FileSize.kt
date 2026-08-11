package com.colink.android.util

private const val KB = 1024L
private const val MB = 1024L * 1024
private const val GB = 1024L * 1024 * 1024

fun formatFileSize(bytes: Long): String {
    val value = if (bytes < 0) 0L else bytes
    val unit: Long
    val suffix: String
    when {
        value >= GB -> {
            unit = GB
            suffix = "GB"
        }
        value >= MB -> {
            unit = MB
            suffix = "MB"
        }
        value >= KB -> {
            unit = KB
            suffix = "KB"
        }
        else -> return "$value B"
    }
    val whole = value / unit
    val fraction = (value % unit) * 10 / unit
    return "$whole.$fraction $suffix"
}
