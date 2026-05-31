package com.colink.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.colink.android.data.local.db.entity.TrustedPeerKeyEntity

@Dao
interface TrustedPeerKeyDao {
    @Query("SELECT * FROM trusted_peer_keys WHERE deviceId = :deviceId LIMIT 1")
    suspend fun get(deviceId: String): TrustedPeerKeyEntity?

    @Query("SELECT * FROM trusted_peer_keys ORDER BY name COLLATE NOCASE ASC, deviceId ASC")
    suspend fun getAll(): List<TrustedPeerKeyEntity>

    @Upsert
    suspend fun upsert(record: TrustedPeerKeyEntity)

    @Query("DELETE FROM trusted_peer_keys WHERE deviceId = :deviceId")
    suspend fun delete(deviceId: String)
}
