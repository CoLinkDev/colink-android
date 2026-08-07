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
    private var defaultNetwork: Network? = null
    private var defaultNetworkValidated = false
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start(
        onLanLost: () -> Unit,
        onLanAvailable: () -> Unit,
        onNetworkLost: () -> Unit,
        onNetworkAvailable: () -> Unit,
    ) {
        if (callback != null || connectivityManager == null) return

        defaultNetwork = connectivityManager.activeNetwork
        defaultNetworkValidated = defaultNetwork?.let { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        } == true

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handleDefaultNetwork(
                    network = network,
                    capabilities = connectivityManager.getNetworkCapabilities(network),
                    onNetworkLost = onNetworkLost,
                    onNetworkAvailable = onNetworkAvailable,
                )
                handleLanAvailable(network, onLanAvailable)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                handleDefaultNetwork(
                    network = network,
                    capabilities = capabilities,
                    onNetworkLost = onNetworkLost,
                    onNetworkAvailable = onNetworkAvailable,
                )
                if (capabilities.isLanNetwork()) handleLanAvailable(network, onLanAvailable)
            }

            override fun onLost(network: Network) {
                if (defaultNetwork == network) {
                    val wasAvailable = defaultNetworkValidated
                    defaultNetwork = null
                    defaultNetworkValidated = false
                    CoLinkLog.i("Network", "default network lost")
                    if (wasAvailable) {
                        onNetworkLost()
                    }
                }
                if (lanNetworks.remove(network) && suspended.compareAndSet(false, true)) {
                    CoLinkLog.i("Network", "LAN transport lost")
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

    private fun handleDefaultNetwork(
        network: Network,
        capabilities: NetworkCapabilities?,
        onNetworkLost: () -> Unit,
        onNetworkAvailable: () -> Unit,
    ) {
        val previousNetwork = defaultNetwork
        val wasAvailable = defaultNetworkValidated
        val networkChanged = defaultNetwork != network
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        defaultNetwork = network
        defaultNetworkValidated = validated
        val becameAvailable = !wasAvailable && validated
        val becameUnavailable = wasAvailable && !validated
        val switchedAvailableNetwork = wasAvailable && validated && previousNetwork != network
        if (becameAvailable || becameUnavailable || switchedAvailableNetwork) {
            CoLinkLog.i(
                "Network",
                "default network state changed available=$validated networkChanged=$networkChanged",
            )
            if (becameUnavailable) {
                onNetworkLost()
            } else {
                onNetworkAvailable()
            }
        }
    }

    private fun handleLanAvailable(network: Network, onLanAvailable: () -> Unit) {
        val capabilities = connectivityManager?.getNetworkCapabilities(network) ?: return
        if (!capabilities.isLanNetwork()) return
        lanNetworks.add(network)
        if (suspended.compareAndSet(true, false)) {
            CoLinkLog.i("Network", "LAN transport available")
            onLanAvailable()
        }
    }

    private fun reset() {
        suspended.set(false)
        lanNetworks.clear()
        defaultNetwork = null
        defaultNetworkValidated = false
    }

    private fun NetworkCapabilities.isLanNetwork(): Boolean =
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}
