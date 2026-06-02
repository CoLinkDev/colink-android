package com.colink.android.network

import com.colink.android.network.message.SwimGossip
import kotlin.math.max

private const val MAX_FUTURE_INCARNATION_MILLIS = 5 * 60 * 1000L

internal class SwimMembership(
    private val maxGossip: Int,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val members = mutableMapOf<String, MemberRecord>()
    private val gossipQueue = ArrayDeque<SwimGossip>()
    private var localDeviceId: String? = null
    private var localIncarnation: Long? = null

    fun ensureLocalStarted(deviceId: String): SwimGossip? =
        synchronized(lock) {
            if (localDeviceId == deviceId && localIncarnation != null) {
                return@synchronized null
            }
            if (localDeviceId != null && localDeviceId != deviceId) {
                clearLocked()
            }
            localDeviceId = deviceId
            val incarnation = nowMillis()
            localIncarnation = incarnation
            SwimGossip(
                deviceId = deviceId,
                state = MemberState.Alive.wireValue,
                incarnation = incarnation,
            ).also(::pushGossipLocked)
        }

    fun refuteSelf(deviceId: String): SwimGossip =
        synchronized(lock) {
            if (localDeviceId != null && localDeviceId != deviceId) {
                clearLocked()
            }
            localDeviceId = deviceId
            val incarnation = max(nowMillis(), (localIncarnation ?: 0L) + 1)
            localIncarnation = incarnation
            SwimGossip(
                deviceId = deviceId,
                state = MemberState.Alive.wireValue,
                incarnation = incarnation,
            ).also(::pushGossipLocked)
        }

    fun leaveSelf(deviceId: String): SwimGossip =
        synchronized(lock) {
            if (localDeviceId != null && localDeviceId != deviceId) {
                clearLocked()
            }
            localDeviceId = deviceId
            val incarnation = max(nowMillis(), (localIncarnation ?: 0L) + 1)
            localIncarnation = incarnation
            SwimGossip(
                deviceId = deviceId,
                state = MemberState.Left.wireValue,
                incarnation = incarnation,
            ).also(::pushGossipLocked)
        }

    fun observeAlive(localDeviceId: String, deviceId: String): MemberUpdate? =
        markMember(localDeviceId, deviceId, MemberState.Alive, incarnation = null, explicit = false)

    fun mergeMember(
        localDeviceId: String,
        originDeviceId: String,
        entry: SwimGossip,
    ): MemberUpdate? {
        if (entry.incarnation > nowMillis() + MAX_FUTURE_INCARNATION_MILLIS) {
            return null
        }
        val state = MemberState.fromWire(entry.state) ?: return null
        if (state == MemberState.Left && originDeviceId != entry.deviceId) {
            return null
        }
        return markMember(
            localDeviceId = localDeviceId,
            deviceId = entry.deviceId,
            state = state,
            incarnation = entry.incarnation,
            explicit = true,
        )
    }

    fun markMember(
        localDeviceId: String,
        deviceId: String,
        state: MemberState,
        incarnation: Long?,
        explicit: Boolean,
    ): MemberUpdate? =
        synchronized(lock) {
            if (deviceId == localDeviceId) {
                return@synchronized null
            }
            val now = nowMillis()
            val nextIncarnation = incarnation ?: members[deviceId]?.incarnation ?: now
            val existing = members[deviceId]
            if (!shouldAcceptMemberUpdate(existing, state, nextIncarnation, explicit)) {
                return@synchronized null
            }
            members[deviceId] = MemberRecord(state, nextIncarnation, now)
            pushGossipLocked(
                SwimGossip(
                    deviceId = deviceId,
                    state = state.wireValue,
                    incarnation = nextIncarnation,
                ),
            )
            MemberUpdate(deviceId, state, nextIncarnation)
        }

    fun promoteExpiredSuspects(
        localDeviceId: String,
        suspectTimeoutMillis: Long,
    ): List<MemberUpdate> {
        val now = nowMillis()
        val expired = synchronized(lock) {
            members
                .filter { (_, member) ->
                    member.state == MemberState.Suspect &&
                        now - member.updatedAt >= suspectTimeoutMillis
                }
                .keys
                .toList()
        }
        return expired.mapNotNull { deviceId ->
            markMember(localDeviceId, deviceId, MemberState.Dead, incarnation = null, explicit = false)
        }
    }

    fun gossipBatch(): List<SwimGossip> =
        synchronized(lock) {
            gossipQueue.toList().asReversed().take(maxGossip)
        }

    fun membersSnapshot(): Map<String, MemberRecord> =
        synchronized(lock) {
            members.toMap()
        }

    fun memberState(deviceId: String): MemberState? =
        synchronized(lock) {
            members[deviceId]?.state
        }

    fun clear() {
        synchronized(lock) {
            clearLocked()
        }
    }

    private fun clearLocked() {
        members.clear()
        gossipQueue.clear()
        localDeviceId = null
        localIncarnation = null
    }

    private fun pushGossipLocked(entry: SwimGossip) {
        gossipQueue.addLast(entry)
        while (gossipQueue.size > maxGossip * 4) {
            gossipQueue.removeFirst()
        }
    }

    companion object {
        fun shouldAcceptMemberUpdate(
            existing: MemberRecord?,
            state: MemberState,
            incarnation: Long,
            explicit: Boolean,
        ): Boolean =
            when {
                existing == null -> true
                incarnation < existing.incarnation -> false
                incarnation > existing.incarnation -> true
                explicit -> existing.state !in setOf(MemberState.Dead, MemberState.Left) &&
                    state.priority > existing.state.priority

                else -> state != existing.state
            }
    }
}

internal data class MemberUpdate(
    val deviceId: String,
    val state: MemberState,
    val incarnation: Long,
)

internal data class MemberRecord(
    val state: MemberState,
    val incarnation: Long,
    val updatedAt: Long,
)

internal enum class MemberState(
    val wireValue: String,
    val priority: Int,
) {
    Alive("alive", 0),
    Suspect("suspect", 1),
    Dead("dead", 2),
    Left("left", 3);

    companion object {
        fun fromWire(value: String): MemberState? =
            entries.firstOrNull { it.wireValue == value }
    }
}
