package com.colink.android.network

import com.colink.android.network.message.SwimGossip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwimMembershipTest {
    private var now = 1_000L

    private fun membership(): SwimMembership =
        SwimMembership(maxGossip = 10) { now }

    @Test
    fun ensureLocalStartedEnqueuesSingleSelfAlive() {
        val membership = membership()

        val first = membership.ensureLocalStarted("local")
        now += 100
        val second = membership.ensureLocalStarted("local")

        assertNotNull(first)
        assertNull(second)
        assertEquals(
            listOf(
                SwimGossip(
                    deviceId = "local",
                    state = "alive",
                    incarnation = 1_000L,
                ),
            ),
            membership.gossipBatch(),
        )
    }

    @Test
    fun gossipBatchDoesNotCreateNewSelfAlive() {
        val membership = membership()
        membership.ensureLocalStarted("local")

        val firstBatch = membership.gossipBatch()
        now += 100
        val secondBatch = membership.gossipBatch()

        assertEquals(firstBatch, secondBatch)
        assertEquals(1_000L, secondBatch.single().incarnation)
    }

    @Test
    fun refuteSelfCreatesHigherIncarnationAlive() {
        val membership = membership()
        val initial = membership.ensureLocalStarted("local")!!

        now = initial.incarnation
        val refutation = membership.refuteSelf("local")

        assertTrue(refutation.incarnation > initial.incarnation)
        assertEquals("local", refutation.deviceId)
        assertEquals("alive", refutation.state)
        assertEquals(refutation, membership.gossipBatch().first())
    }

    @Test
    fun sameIncarnationGossipOnlyAcceptsHigherPriorityState() {
        val membership = membership()
        val localDeviceId = "local"
        val peerDeviceId = "peer"

        assertNotNull(
            membership.markMember(localDeviceId, peerDeviceId, MemberState.Alive, 100L, explicit = true),
        )
        assertNotNull(
            membership.markMember(localDeviceId, peerDeviceId, MemberState.Suspect, 100L, explicit = true),
        )
        assertNotNull(
            membership.markMember(localDeviceId, peerDeviceId, MemberState.Dead, 100L, explicit = true),
        )
        assertNull(
            membership.markMember(localDeviceId, peerDeviceId, MemberState.Alive, 100L, explicit = true),
        )

        assertEquals(MemberState.Dead, membership.memberState(peerDeviceId))
    }

    @Test
    fun deadAndLeftRejectSameIncarnationGossip() {
        val membership = membership()
        val localDeviceId = "local"

        assertNotNull(
            membership.markMember(localDeviceId, "dead-peer", MemberState.Dead, 100L, explicit = true),
        )
        assertNull(
            membership.markMember(localDeviceId, "dead-peer", MemberState.Suspect, 100L, explicit = true),
        )
        assertNotNull(
            membership.markMember(localDeviceId, "left-peer", MemberState.Left, 100L, explicit = true),
        )
        assertNull(
            membership.markMember(localDeviceId, "left-peer", MemberState.Dead, 100L, explicit = true),
        )

        assertEquals(MemberState.Dead, membership.memberState("dead-peer"))
        assertEquals(MemberState.Left, membership.memberState("left-peer"))
    }

    @Test
    fun thirdPartyLeftIsRejected() {
        val membership = membership()

        val rejected = membership.mergeMember(
            localDeviceId = "local",
            originDeviceId = "observer",
            entry = SwimGossip(
                deviceId = "peer",
                state = "left",
                incarnation = 100L,
            ),
        )
        val accepted = membership.mergeMember(
            localDeviceId = "local",
            originDeviceId = "peer",
            entry = SwimGossip(
                deviceId = "peer",
                state = "left",
                incarnation = 100L,
            ),
        )

        assertNull(rejected)
        assertNotNull(accepted)
        assertEquals(MemberState.Left, membership.memberState("peer"))
    }

    @Test
    fun higherIncarnationOverridesAnyState() {
        val membership = membership()

        assertNotNull(
            membership.markMember("local", "peer", MemberState.Dead, 100L, explicit = true),
        )
        assertNotNull(
            membership.markMember("local", "peer", MemberState.Alive, 101L, explicit = true),
        )

        assertEquals(MemberState.Alive, membership.memberState("peer"))
    }
}
