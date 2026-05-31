package com.colink.android.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trusted_peer_keys")
data class TrustedPeerKeyEntity(
    @PrimaryKey val deviceId: String,
    val name: String,
    val publicKey: String,
    val keyUpdatedAt: Long,
    val trustedAt: Long?,
)
