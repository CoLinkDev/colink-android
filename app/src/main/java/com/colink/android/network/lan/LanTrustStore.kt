package com.colink.android.network.lan

import com.colink.android.data.local.db.dao.TrustedPeerKeyDao
import com.colink.android.data.local.db.entity.TrustedPeerKeyEntity
import com.colink.android.data.local.db.entity.isTrusted
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
        if (!record.isTrusted) {
            return LanTrustState.Unknown
        }
        if (record.publicKey != publicKey) {
            return LanTrustState.KeyChanged
        }
        return if (record.trustedByLan) LanTrustState.Trusted else LanTrustState.Unknown
    }

    suspend fun isLanTrusted(deviceId: String): Boolean =
        trustedPeerKeyDao.get(deviceId)?.trustedByLan == true

    suspend fun trust(
        deviceId: String,
        name: String,
        publicKey: String,
    ) {
        val now = System.currentTimeMillis()
        val existing = trustedPeerKeyDao.get(deviceId)
        val keyChanged = existing != null && existing.publicKey != publicKey
        trustedPeerKeyDao.upsert(
            TrustedPeerKeyEntity(
                deviceId = deviceId,
                name = name,
                publicKey = publicKey,
                keyUpdatedAt = now,
                trustedByLan = true,
                trustedByCloud = existing?.trustedByCloud == true && !keyChanged,
            ),
        )
    }

    suspend fun clearLanPairing(deviceId: String) =
        trustedPeerKeyDao.clearLanTrust(deviceId)
}

enum class LanTrustState {
    Trusted,
    Unknown,
    KeyChanged,
}
