package com.colink.android.network.lan

import com.colink.android.data.local.db.dao.TrustedPeerKeyDao
import com.colink.android.data.local.db.entity.TrustedPeerKeyEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
        dao.upsert(record(deviceId = "device", publicKey = "key", trustedByLan = true))

        assertEquals(LanTrustState.Trusted, store.trustState("device", "key"))
        assertTrue(store.isLanTrusted("device"))
    }

    @Test
    fun changedTrustedKeyIsKeyChanged() = runBlocking {
        dao.upsert(record(deviceId = "device", publicKey = "old", trustedByLan = true))

        assertEquals(LanTrustState.KeyChanged, store.trustState("device", "new"))
    }

    @Test
    fun trustedPeersFlowReflectsLanTrustChanges() = runBlocking {
        assertTrue(store.trustedPeers.first().isEmpty())

        dao.upsert(record(deviceId = "device", publicKey = "key", trustedByLan = true))

        assertEquals(listOf("device"), store.trustedPeers.first().map { it.deviceId })
    }

    @Test
    fun cloudTrustedKeyIsTrustedButNotLanTrusted() = runBlocking {
        dao.upsert(record(deviceId = "device", publicKey = "key", trustedByCloud = true))

        assertEquals(LanTrustState.Unknown, store.trustState("device", "key"))
        assertFalse(store.isLanTrusted("device"))
        assertTrue(store.isTrusted("device"))
    }

    @Test
    fun changedCloudTrustedKeyIsKeyChanged() = runBlocking {
        dao.upsert(record(deviceId = "device", publicKey = "old", trustedByCloud = true))

        assertEquals(LanTrustState.KeyChanged, store.trustState("device", "new"))
    }

    @Test
    fun unpairedRecordIsUnknown() = runBlocking {
        dao.upsert(record(deviceId = "device", publicKey = "key"))

        assertEquals(LanTrustState.Unknown, store.trustState("device", "key"))
        assertFalse(store.isLanTrusted("device"))
    }

    @Test
    fun clearLanPairingKeepsKeyButRemovesLanTrust() = runBlocking {
        dao.upsert(record(deviceId = "device", publicKey = "old", trustedByLan = true))

        store.clearLanPairing("device")

        val record = dao.get("device")
        assertEquals("old", record?.publicKey)
        assertFalse(record?.trustedByLan == true)
        assertEquals(LanTrustState.Unknown, store.trustState("device", "old"))
    }

    private fun record(
        deviceId: String,
        publicKey: String,
        trustedByLan: Boolean = false,
        trustedByCloud: Boolean = false,
    ): TrustedPeerKeyEntity =
        TrustedPeerKeyEntity(
            deviceId = deviceId,
            name = "Device",
            publicKey = publicKey,
            keyUpdatedAt = 0,
            trustedByLan = trustedByLan,
            trustedByCloud = trustedByCloud,
        )
}

private class FakeTrustedPeerKeyDao : TrustedPeerKeyDao {
    private val records = LinkedHashMap<String, TrustedPeerKeyEntity>()
    private val observedRecords = MutableStateFlow<List<TrustedPeerKeyEntity>>(emptyList())

    override suspend fun get(deviceId: String): TrustedPeerKeyEntity? =
        records[deviceId]

    override suspend fun getAll(): List<TrustedPeerKeyEntity> =
        records.values.toList()

    override fun observeAll(): Flow<List<TrustedPeerKeyEntity>> = observedRecords

    override suspend fun upsert(record: TrustedPeerKeyEntity) {
        records[record.deviceId] = record
        publish()
    }

    override suspend fun clearLanTrust(deviceId: String) {
        records[deviceId]?.let { records[deviceId] = it.copy(trustedByLan = false) }
        publish()
    }

    override suspend fun clearCloudTrust() {
        records.replaceAll { _, record -> record.copy(trustedByCloud = false) }
        publish()
    }

    override suspend fun deleteUntrusted() {
        records.entries.removeIf { !it.value.trustedByLan && !it.value.trustedByCloud }
        publish()
    }

    override suspend fun delete(deviceId: String) {
        records.remove(deviceId)
        publish()
    }

    private fun publish() {
        observedRecords.value = records.values.toList()
    }
}
