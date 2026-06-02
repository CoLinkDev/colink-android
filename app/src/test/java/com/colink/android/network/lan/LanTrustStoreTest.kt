package com.colink.android.network.lan

import com.colink.android.data.local.db.dao.TrustedPeerKeyDao
import com.colink.android.data.local.db.entity.TrustedPeerKeyEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanTrustStoreTest {
    private val dao = FakeTrustedPeerKeyDao()
    private val store = LanTrustStore(dao)

    @Test
    fun unknownDeviceIsNotTrusted() = runBlocking {
        assertEquals(LanTrustState.Unknown, store.trustState("device", "key"))
        assertFalse(store.isLanTrusted("device"))
    }

    @Test
    fun matchingTrustedKeyIsTrusted() = runBlocking {
        dao.upsert(record(deviceId = "device", publicKey = "key", trustedAt = 100))

        assertEquals(LanTrustState.Trusted, store.trustState("device", "key"))
        assertTrue(store.isLanTrusted("device"))
    }

    @Test
    fun changedTrustedKeyIsKeyChanged() = runBlocking {
        dao.upsert(record(deviceId = "device", publicKey = "old", trustedAt = 100))

        assertEquals(LanTrustState.KeyChanged, store.trustState("device", "new"))
    }

    @Test
    fun unpairedRecordIsUnknown() = runBlocking {
        dao.upsert(record(deviceId = "device", publicKey = "key", trustedAt = null))

        assertEquals(LanTrustState.Unknown, store.trustState("device", "key"))
        assertFalse(store.isLanTrusted("device"))
    }

    @Test
    fun clearLanPairingKeepsRecordButRemovesTrust() = runBlocking {
        dao.upsert(record(deviceId = "device", publicKey = "old", trustedAt = 100))

        store.clearLanPairing("device", "Device", "new")

        val record = dao.get("device")
        assertEquals("new", record?.publicKey)
        assertEquals(null, record?.trustedAt)
        assertEquals(LanTrustState.Unknown, store.trustState("device", "new"))
    }

    private fun record(
        deviceId: String,
        publicKey: String,
        trustedAt: Long?,
    ): TrustedPeerKeyEntity =
        TrustedPeerKeyEntity(
            deviceId = deviceId,
            name = "Device",
            publicKey = publicKey,
            keyUpdatedAt = 0,
            trustedAt = trustedAt,
        )
}

private class FakeTrustedPeerKeyDao : TrustedPeerKeyDao {
    private val records = LinkedHashMap<String, TrustedPeerKeyEntity>()

    override suspend fun get(deviceId: String): TrustedPeerKeyEntity? =
        records[deviceId]

    override suspend fun getAll(): List<TrustedPeerKeyEntity> =
        records.values.toList()

    override suspend fun upsert(record: TrustedPeerKeyEntity) {
        records[record.deviceId] = record
    }

    override suspend fun delete(deviceId: String) {
        records.remove(deviceId)
    }
}
