package com.colink.android.network.lan

import com.colink.android.data.local.db.dao.TrustedPeerKeyDao
import com.colink.android.data.local.db.entity.TrustedPeerKeyEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LanTrustStore @Inject constructor(
    private val trustedPeerKeyDao: TrustedPeerKeyDao,
) {
    suspend fun get(deviceId: String): TrustedPeerKeyEntity? =
        trustedPeerKeyDao.get(deviceId)

    suspend fun trust(
        deviceId: String,
        name: String,
        publicKey: String,
    ) {
        val now = System.currentTimeMillis()
        trustedPeerKeyDao.upsert(
            TrustedPeerKeyEntity(
                deviceId = deviceId,
                name = name,
                publicKey = publicKey,
                keyUpdatedAt = now,
                trustedAt = now,
            ),
        )
    }
}
