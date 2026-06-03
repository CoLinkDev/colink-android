package com.colink.android.network.lan

import android.content.Context
import android.os.Build
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.colink.android.util.CoLinkLog
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
        CoLinkLog.i(
            "LAN",
            "starting NSD service=$serviceName device=${CoLinkLog.shortId(deviceId)} name=$deviceName type=$deviceType port=$port",
        )
        registerService(serviceInfo(serviceName, port, deviceId, deviceName, deviceType))
        discover(listener)
    }

    fun stop() {
        registrationListener?.let { runCatching { manager.unregisterService(it) } }
        discoveryListener?.let { runCatching { manager.stopServiceDiscovery(it) } }
        registrationListener = null
        discoveryListener = null
        CoLinkLog.d("LAN", "stopped NSD")
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
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                CoLinkLog.w("LAN", "NSD discovery start failed type=$serviceType code=$errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                CoLinkLog.w("LAN", "NSD discovery stop failed type=$serviceType code=$errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String) {
                CoLinkLog.i("LAN", "NSD discovery started type=$serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                CoLinkLog.i("LAN", "NSD discovery stopped type=$serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE) {
                    return
                }
                CoLinkLog.d("LAN", "NSD service found name=${serviceInfo.serviceName}")
                manager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            CoLinkLog.w("LAN", "NSD resolve failed name=${serviceInfo.serviceName} code=$errorCode")
                        }

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
                            val deviceType = serviceInfo.attributes["type"]
                                ?.decodeToString()
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                                ?: "unknown"
                            val hostAddress = resolvedHostAddress(serviceInfo) ?: return
                            CoLinkLog.i(
                                "LAN",
                                "NSD service resolved device=${CoLinkLog.shortId(deviceId)} name=$name type=$deviceType ip=$hostAddress port=${serviceInfo.port}",
                            )
                            listener.onServiceResolved(
                                deviceId = deviceId,
                                name = name,
                                type = deviceType,
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
                CoLinkLog.w("LAN", "NSD service lost device=${CoLinkLog.shortId(deviceId)}")
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
        fun onServiceResolved(deviceId: String, name: String, type: String, ip: String, port: Int)

        fun onServiceLost(deviceId: String)
    }
}
