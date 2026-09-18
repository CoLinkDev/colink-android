package com.colink.android.network

import org.junit.Assert.assertEquals
import org.junit.Test

class LanLifecycleControllerTest {
    @Test
    fun `ignores a stale network transition`() {
        var managerStarted = true
        val events = mutableListOf<String>()
        val controller = LanLifecycleController(
            isManagerStarted = { managerStarted },
            startServices = { generation -> events += "start:$generation" },
            stopServices = { events += "stop" },
        )
        controller.resetForManagerStart()

        val lostGeneration = controller.requestAvailability(available = false)
        val availableGeneration = controller.requestAvailability(available = true)
        controller.applyAvailability(lostGeneration, available = false)
        controller.applyAvailability(availableGeneration, available = true)

        assertEquals(listOf("stop", "start:$availableGeneration"), events)
    }

    @Test
    fun `does not restart after the manager stops during shutdown`() {
        var managerStarted = true
        val events = mutableListOf<String>()
        val controller = LanLifecycleController(
            isManagerStarted = { managerStarted },
            startServices = { events += "start" },
            stopServices = {
                events += "stop"
                managerStarted = false
            },
        )
        controller.resetForManagerStart()

        controller.restartIfDesired()

        assertEquals(listOf("stop"), events)
    }

    @Test
    fun `invalidates deferred startup work when LAN becomes unavailable`() {
        var managerStarted = true
        var deferredWorkRuns = 0
        var startedGeneration = -1L
        val controller = LanLifecycleController(
            isManagerStarted = { managerStarted },
            startServices = { generation -> startedGeneration = generation },
            stopServices = {},
        )
        controller.resetForManagerStart()
        controller.startIfDesired()

        controller.requestAvailability(available = false)
        controller.runIfCurrent(startedGeneration) { deferredWorkRuns += 1 }

        assertEquals(0, deferredWorkRuns)
    }

    @Test
    fun `manager shutdown prevents queued availability from restarting LAN`() {
        var managerStarted = true
        val events = mutableListOf<String>()
        val controller = LanLifecycleController(
            isManagerStarted = { managerStarted },
            startServices = { events += "start" },
            stopServices = { events += "stop" },
        )
        controller.resetForManagerStart()
        val availableGeneration = controller.requestAvailability(available = true)

        managerStarted = false
        controller.stopAndInvalidate()
        controller.applyAvailability(availableGeneration, available = true)

        assertEquals(listOf("stop"), events)
    }
}
