package com.colink.android.util

fun isBreakingVersionUpdate(currentVersion: String, latestVersion: String): Boolean {
    val currentMajor = semanticMajor(currentVersion) ?: return false
    val latestMajor = semanticMajor(latestVersion) ?: return false
    return latestMajor > currentMajor
}

private fun semanticMajor(version: String): Int? {
    val normalized = version.trim().removePrefix("v").removePrefix("V")
    val major = normalized.substringBefore('.')
    return major.toIntOrNull()?.takeIf { it >= 0 }
}
