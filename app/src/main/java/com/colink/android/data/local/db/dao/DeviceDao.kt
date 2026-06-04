package com.colink.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.colink.android.data.local.db.entity.DeviceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY online DESC, name COLLATE NOCASE ASC")
    fun observeDevices(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices")
    suspend fun getDevices(): List<DeviceEntity>

    @Query("SELECT * FROM devices WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getDevice(deviceId: String): DeviceEntity?

    @Upsert
    suspend fun upsertAll(devices: List<DeviceEntity>)

    @Query("DELETE FROM devices")
    suspend fun clear()

    @Query("DELETE FROM devices WHERE deviceId = :deviceId")
    suspend fun delete(deviceId: String)

    @Query(
        """
        UPDATE devices
        SET localIp = NULL,
            localPort = NULL,
            lanAvailable = 0,
            lanState = 'unavailable',
            online = cloudAvailable,
            activeRoute = CASE WHEN cloudAvailable = 1 THEN 'cloud' ELSE NULL END
        """,
    )
    suspend fun clearAllLanEndpoints()
}
