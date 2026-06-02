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

    suspend fun trustState(deviceId: String, publicKey: String): LanTrustState {
        val record = trustedPeerKeyDao.get(deviceId) ?: return LanTrustState.Unknown
        if (record.trustedAt == null) {
            return LanTrustState.Unknown
        }
        return if (record.publicKey == publicKey) {
            LanTrustState.Trusted
        } else {
            LanTrustState.KeyChanged
        }
    }

    suspend fun isLanTrusted(deviceId: String): Boolean =
        trustedPeerKeyDao.get(deviceId)?.trustedAt != null

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

    suspend fun clearLanPairing(
        deviceId: String,
        name: String,
        publicKey: String,
    ) {
        trustedPeerKeyDao.upsert(
            TrustedPeerKeyEntity(
                deviceId = deviceId,
                name = name,
                publicKey = publicKey,
                keyUpdatedAt = System.currentTimeMillis(),
                trustedAt = null,
            ),
        )
    }
}

enum class LanTrustState {
    Trusted,
    Unknown,
    KeyChanged,
}
