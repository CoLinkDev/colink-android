package com.colink.android.network.lan

import android.content.Context
import android.os.Build
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton

private const val SERVICE_TYPE = "_colink._tcp."

@Singleton
class NsdDiscovery @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val manager = context.getSystemService(NsdManager::class.java)
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun serviceInfo(
        serviceName: String,
        port: Int,
        deviceId: String,
        deviceName: String,
    ): NsdServiceInfo =
        NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("deviceId", deviceId)
            setAttribute("version", "1")
            deviceName.trim().takeIf { it.isNotEmpty() && it.length <= 200 }?.let {
                setAttribute("name", it)
            }
        }

    fun start(
        serviceName: String,
        port: Int,
        deviceId: String,
        deviceName: String,
        listener: Listener,
    ) {
        stop()
        registerService(serviceInfo(serviceName, port, deviceId, deviceName))
        discover(listener)
    }

    fun stop() {
        registrationListener?.let { runCatching { manager.unregisterService(it) } }
        discoveryListener?.let { runCatching { manager.stopServiceDiscovery(it) } }
        registrationListener = null
        discoveryListener = null
    }

    private fun registerService(info: NsdServiceInfo) {
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        manager.registerService(
            info,
            NsdManager.PROTOCOL_DNS_SD,
            registrationListener,
        )
    }

    private fun discover(listener: Listener) {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE) {
                    return
                }
                manager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val deviceId = serviceInfo.attributes["deviceId"]
                                ?.decodeToString()
                                ?.takeIf { it.isNotBlank() }
                                ?: return
                            val version = serviceInfo.attributes["version"]
                                ?.decodeToString()
                                ?: return
                            if (version != "1") {
                                return
                            }
                            val name = serviceInfo.attributes["name"]
                                ?.decodeToString()
                                ?.trim()
                                .orEmpty()
                            val hostAddress = resolvedHostAddress(serviceInfo) ?: return
                            listener.onServiceResolved(
                                deviceId = deviceId,
                                name = name,
                                ip = hostAddress,
                                port = serviceInfo.port,
                            )
                        }
                    },
                )
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val deviceId = serviceInfo.attributes["deviceId"]
                    ?.decodeToString()
                    ?.takeIf { it.isNotBlank() }
                    ?: return
                listener.onServiceLost(deviceId)
            }
        }
        manager.discoverServices(
            SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            discoveryListener,
        )
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

    interface Listener {
        fun onServiceResolved(deviceId: String, name: String, ip: String, port: Int)

        fun onServiceLost(deviceId: String)
    }
}
