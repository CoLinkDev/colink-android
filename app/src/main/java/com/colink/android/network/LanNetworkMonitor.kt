package com.colink.android.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.colink.android.util.CoLinkLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LanNetworkMonitor @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val lanNetworks = ConcurrentHashMap.newKeySet<Network>()
    private val suspended = AtomicBoolean(false)
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start(onLanLost: () -> Unit, onLanAvailable: () -> Unit) {
        if (callback != null || connectivityManager == null) return

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handleAvailable(network, onLanAvailable)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.isLanNetwork()) handleAvailable(network, onLanAvailable)
            }

            override fun onLost(network: Network) {
                if (lanNetworks.remove(network) && suspended.compareAndSet(false, true)) {
                    onLanLost()
                }
            }
        }
        callback = networkCallback
        runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
            .onFailure { error ->
                callback = null
                CoLinkLog.w("Connection", "failed to register network callback", error)
            }
    }

    fun stop() {
        val networkCallback = callback ?: return reset()
        callback = null
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
            .onFailure { error ->
                CoLinkLog.w("Connection", "failed to unregister network callback", error)
            }
        reset()
    }

    private fun handleAvailable(network: Network, onLanAvailable: () -> Unit) {
        val capabilities = connectivityManager?.getNetworkCapabilities(network) ?: return
        if (!capabilities.isLanNetwork()) return
        lanNetworks.add(network)
        if (suspended.compareAndSet(true, false)) onLanAvailable()
    }

    private fun reset() {
        suspended.set(false)
        lanNetworks.clear()
    }

    private fun NetworkCapabilities.isLanNetwork(): Boolean =
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}
