package com.colink.android.data.repository

private val supportedUpdateArchitectures = setOf(
    "arm64-v8a",
    "armeabi-v7a",
    "x86_64",
    "x86",
)

internal fun selectUpdateArchitecture(supportedAbis: Array<String>): String? =
    supportedAbis
        .asSequence()
        .map { it.lowercase() }
        .firstOrNull { it in supportedUpdateArchitectures }
