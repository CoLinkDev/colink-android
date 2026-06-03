package com.colink.android.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "trusted_peer_keys")
data class TrustedPeerKeyEntity(
    @PrimaryKey
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    val name: String,
    @ColumnInfo(name = "public_key")
    val publicKey: String,
    @ColumnInfo(name = "key_updated_at")
    val keyUpdatedAt: Long,
    @ColumnInfo(name = "trusted_by_lan")
    val trustedByLan: Boolean,
    @ColumnInfo(name = "trusted_by_cloud")
    val trustedByCloud: Boolean,
)

val TrustedPeerKeyEntity.isTrusted: Boolean
    get() = trustedByLan || trustedByCloud
