package com.colink.android.network

internal class LanLifecycleController(
    private val isManagerStarted: () -> Boolean,
    private val startServices: (generation: Long) -> Unit,
    private val stopServices: () -> Unit,
) {
    private val lock = Any()
    private var generation = 0L
    private var desiredAvailable = true

    fun resetForManagerStart() = synchronized(lock) {
        generation += 1
        desiredAvailable = true
    }

    fun requestAvailability(available: Boolean): Long = synchronized(lock) {
        generation += 1
        desiredAvailable = available
        generation
    }

    fun applyAvailability(requestGeneration: Long, available: Boolean) = synchronized(lock) {
        if (!isCurrentLocked(requestGeneration, available)) return
        if (available) {
            stopServices()
            if (isCurrentLocked(requestGeneration, available = true)) {
                startServices(requestGeneration)
            }
        } else {
            stopServices()
        }
    }

    fun startIfDesired() = synchronized(lock) {
        if (isManagerStarted() && desiredAvailable) {
            startServices(generation)
        }
    }

    fun restartIfDesired() = synchronized(lock) {
        if (!isManagerStarted() || !desiredAvailable) return
        generation += 1
        val restartGeneration = generation
        stopServices()
        if (isCurrentLocked(restartGeneration, available = true)) {
            startServices(restartGeneration)
        }
    }

    fun stopAndInvalidate() = synchronized(lock) {
        generation += 1
        desiredAvailable = false
        stopServices()
    }

    fun runIfCurrent(requestGeneration: Long, block: () -> Unit) = synchronized(lock) {
        if (isCurrentLocked(requestGeneration, available = true)) {
            block()
        }
    }

    private fun isCurrentLocked(requestGeneration: Long, available: Boolean): Boolean =
        requestGeneration == generation &&
            desiredAvailable == available &&
            isManagerStarted()
}
