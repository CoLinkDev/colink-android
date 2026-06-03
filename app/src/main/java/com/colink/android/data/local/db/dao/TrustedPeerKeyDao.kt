package com.colink.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.colink.android.data.local.db.entity.TrustedPeerKeyEntity

@Dao
interface TrustedPeerKeyDao {
    @Query("SELECT * FROM trusted_peer_keys WHERE device_id = :deviceId LIMIT 1")
    suspend fun get(deviceId: String): TrustedPeerKeyEntity?

    @Query("SELECT * FROM trusted_peer_keys ORDER BY name COLLATE NOCASE ASC, device_id ASC")
    suspend fun getAll(): List<TrustedPeerKeyEntity>

    @Upsert
    suspend fun upsert(record: TrustedPeerKeyEntity)

    @Query("UPDATE trusted_peer_keys SET trusted_by_lan = 0 WHERE device_id = :deviceId")
    suspend fun clearLanTrust(deviceId: String)

    @Query("UPDATE trusted_peer_keys SET trusted_by_cloud = 0")
    suspend fun clearCloudTrust()

    @Query("DELETE FROM trusted_peer_keys WHERE trusted_by_lan = 0 AND trusted_by_cloud = 0")
    suspend fun deleteUntrusted()

    @Query("DELETE FROM trusted_peer_keys WHERE device_id = :deviceId")
    suspend fun delete(deviceId: String)
}
