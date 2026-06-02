package com.colink.android.domain.model

data class LanPairingRequest(
    val requestId: String,
    val deviceId: String,
    val name: String,
    val code: String,
    val reason: String,
    val publicKey: String,
)

data class LanPairingCandidate(
    val deviceId: String,
    val ip: String,
    val port: Int,
    val state: String,
)
