package com.colink.android.network.lan

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.colink.android.util.CoLinkLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.ArrayDeque
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

private const val SERVICE_TYPE = "_colink._tcp."
private const val RESOLVE_TIMEOUT_MILLIS = 5_000L

@Singleton
class NsdDiscovery @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val manager = context.getSystemService(NsdManager::class.java)
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var activeListener: Listener? = null
    private var refreshRequested = false
    private var refreshCompletion: CompletableDeferred<Unit>? = null
    private val resolveLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingResolves = ArrayDeque<PendingResolve>()
    private val queuedServiceNames = mutableSetOf<String>()
    private var activeResolve: PendingResolve? = null
    private var discoveryGeneration = 0L

    fun serviceInfo(
        serviceName: String,
        port: Int,
        deviceId: String,
        deviceName: String,
        deviceType: String,
    ): NsdServiceInfo =
        NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("deviceId", deviceId)
            setAttribute("version", "1")
            deviceType.trim().takeIf { it.isNotEmpty() }?.let {
                setAttribute("type", it)
            }
            deviceName.trim().takeIf { it.isNotEmpty() && it.length <= 200 }?.let {
                setAttribute("name", it)
            }
        }

    fun start(
        serviceName: String,
        port: Int,
        deviceId: String,
        deviceName: String,
        deviceType: String,
        listener: Listener,
    ) {
        stop()
        activeListener = listener
        CoLinkLog.i(
            "LAN",
            "starting NSD service=$serviceName device=${CoLinkLog.shortId(deviceId)} name=$deviceName type=$deviceType port=$port",
        )
        registerService(serviceInfo(serviceName, port, deviceId, deviceName, deviceType))
        discover(listener)
    }

    fun stop() {
        activeListener = null
        refreshRequested = false
        refreshCompletion?.complete(Unit)
        refreshCompletion = null
        invalidatePendingResolves()
        registrationListener?.let { runCatching { manager.unregisterService(it) } }
        discoveryListener?.let { runCatching { manager.stopServiceDiscovery(it) } }
        registrationListener = null
        discoveryListener = null
        CoLinkLog.d("LAN", "stopped NSD")
    }

    fun refreshDiscovery(): Deferred<Unit>? {
        val listener = activeListener ?: return null
        val discovery = discoveryListener
        if (discovery == null) {
            discover(listener)
            return CompletableDeferred<Unit>().also { it.complete(Unit) }
        }
        if (refreshRequested) {
            return refreshCompletion
        }
        val completion = CompletableDeferred<Unit>()
        refreshCompletion = completion
        refreshRequested = true
        invalidatePendingResolves()
        runCatching { manager.stopServiceDiscovery(discovery) }
            .onFailure { error ->
                refreshRequested = false
                refreshCompletion?.complete(Unit)
                refreshCompletion = null
                CoLinkLog.w("LAN", "NSD refresh stop failed", error)
            }
        return completion
    }

    private fun registerService(info: NsdServiceInfo) {
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                CoLinkLog.i("LAN", "NSD service registered name=${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                CoLinkLog.w("LAN", "NSD registration failed name=${serviceInfo.serviceName} code=$errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                CoLinkLog.i("LAN", "NSD service unregistered name=${serviceInfo.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                CoLinkLog.w("LAN", "NSD unregistration failed name=${serviceInfo.serviceName} code=$errorCode")
            }
        }
        manager.registerService(
            info,
            NsdManager.PROTOCOL_DNS_SD,
            registrationListener,
        )
    }

    private fun discover(listener: Listener) {
        val generation = synchronized(resolveLock) { discoveryGeneration }
        lateinit var discovery: NsdManager.DiscoveryListener
        discovery = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                CoLinkLog.w("LAN", "NSD discovery start failed type=$serviceType code=$errorCode")
                if (discoveryListener === discovery) {
                    discoveryListener = null
                }
                refreshCompletion?.complete(Unit)
                refreshCompletion = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                CoLinkLog.w("LAN", "NSD discovery stop failed type=$serviceType code=$errorCode")
                if (discoveryListener === discovery) {
                    refreshRequested = false
                    refreshCompletion?.complete(Unit)
                    refreshCompletion = null
                }
            }

            override fun onDiscoveryStarted(serviceType: String) {
                CoLinkLog.i("LAN", "NSD discovery started type=$serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                CoLinkLog.i("LAN", "NSD discovery stopped type=$serviceType")
                finishDiscovery(discovery)
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (discoveryListener !== discovery || serviceInfo.serviceType != SERVICE_TYPE) {
                    return
                }
                CoLinkLog.d("LAN", "NSD service found name=${serviceInfo.serviceName}")
                enqueueResolve(serviceInfo, listener, generation)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                if (discoveryListener !== discovery) {
                    return
                }
                val deviceId = serviceInfo.attributes["deviceId"]
                    ?.decodeToString()
                    ?.takeIf { it.isNotBlank() }
                    ?: return
                CoLinkLog.w("LAN", "NSD service lost device=${CoLinkLog.shortId(deviceId)}")
                listener.onServiceLost(deviceId)
            }
        }
        discoveryListener = discovery
        manager.discoverServices(
            SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            discovery,
        )
    }

    private fun finishDiscovery(discovery: NsdManager.DiscoveryListener) {
        if (discoveryListener !== discovery) {
            return
        }
        discoveryListener = null
        restartDiscoveryIfRequested()
    }

    private fun restartDiscoveryIfRequested() {
        if (!refreshRequested) return
        refreshRequested = false
        activeListener?.let(::discover)
        refreshCompletion?.complete(Unit)
        refreshCompletion = null
    }

    private fun enqueueResolve(serviceInfo: NsdServiceInfo, listener: Listener, generation: Long) {
        val pending = synchronized(resolveLock) {
            val serviceName = serviceInfo.serviceName
            if (
                generation != discoveryGeneration ||
                activeListener == null ||
                activeResolve?.takeIf { it.generation == generation }?.serviceName == serviceName ||
                !queuedServiceNames.add(serviceName)
            ) {
                null
            } else {
                PendingResolve(serviceInfo, listener, generation).also(pendingResolves::addLast)
            }
        }
        if (pending != null) {
            mainHandler.post(::resolveNext)
        }
    }

    private fun resolveNext() {
        val pending = takeNextResolve() ?: return

        runCatching {
            manager.resolveService(
                pending.serviceInfo,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        CoLinkLog.w("LAN", "NSD resolve failed name=${serviceInfo.serviceName} code=$errorCode")
                        finishResolve(pending)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (isCurrentDiscovery(pending)) {
                            serviceInfo.toResolvedService()?.let { resolved ->
                                CoLinkLog.i(
                                    "LAN",
                                    "NSD service resolved device=${CoLinkLog.shortId(resolved.deviceId)} name=${resolved.name} type=${resolved.type} ip=${resolved.ip} port=${resolved.port}",
                                )
                                pending.listener.onServiceResolved(
                                    deviceId = resolved.deviceId,
                                    name = resolved.name,
                                    type = resolved.type,
                                    ip = resolved.ip,
                                    port = resolved.port,
                                )
                            }
                        }
                        finishResolve(pending)
                    }
                },
            )
        }.onFailure { error ->
            CoLinkLog.w("LAN", "NSD resolve start failed name=${pending.serviceName}", error)
            finishResolve(pending)
        }
        mainHandler.postDelayed({ finishResolve(pending) }, RESOLVE_TIMEOUT_MILLIS)
    }

    private fun takeNextResolve(): PendingResolve? =
        synchronized(resolveLock) {
            if (activeResolve != null) {
                null
            } else if (pendingResolves.isEmpty()) {
                null
            } else {
                pendingResolves.removeFirst().also {
                    queuedServiceNames.remove(it.serviceName)
                    activeResolve = it
                }
            }
        }

    private fun finishResolve(pending: PendingResolve) {
        synchronized(resolveLock) {
            if (activeResolve != pending) {
                return
            }
            activeResolve = null
        }
        mainHandler.post(::resolveNext)
    }

    private fun invalidatePendingResolves() {
        synchronized(resolveLock) {
            discoveryGeneration += 1
            pendingResolves.clear()
            queuedServiceNames.clear()
        }
    }

    private fun isCurrentDiscovery(pending: PendingResolve): Boolean =
        synchronized(resolveLock) { pending.generation == discoveryGeneration && activeListener != null }

    private fun NsdServiceInfo.toResolvedService(): ResolvedService? {
        val deviceId = attributes["deviceId"]
            ?.decodeToString()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val version = attributes["version"]
            ?.decodeToString()
            ?: return null
        if (version != "1") {
            return null
        }
        val name = attributes["name"]
            ?.decodeToString()
            ?.trim()
            .orEmpty()
        val deviceType = attributes["type"]
            ?.decodeToString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "unknown"
        val hostAddress = resolvedHostAddress(this) ?: return null
        return ResolvedService(deviceId, name, deviceType, hostAddress, port)
    }

    @Suppress("DEPRECATION")
    private fun resolvedHostAddress(serviceInfo: NsdServiceInfo): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceInfo.hostAddresses
                .firstOrNull { it is Inet4Address }
                ?.hostAddress
        } else {
            serviceInfo.host
                ?.takeIf { it is Inet4Address }
                ?.hostAddress
        }

    private fun emptyDiscoveryListener(): NsdManager.DiscoveryListener =
        object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
    }

    private data class PendingResolve(
        val serviceInfo: NsdServiceInfo,
        val listener: Listener,
        val generation: Long,
    ) {
        val serviceName: String = serviceInfo.serviceName
    }

    private data class ResolvedService(
        val deviceId: String,
        val name: String,
        val type: String,
        val ip: String,
        val port: Int,
    )

    interface Listener {
        fun onServiceResolved(deviceId: String, name: String, type: String, ip: String, port: Int)

        fun onServiceLost(deviceId: String)
    }
}
