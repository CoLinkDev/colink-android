package com.colink.android.network

import com.colink.android.network.message.isValidWakeOnLanMac
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Enumeration

internal object WakeOnLan {
    private const val PORT = 9

    fun send(targetMac: String): Int {
        val packet = buildMagicPacket(targetMac)
            ?: throw IllegalArgumentException("invalid Wake-on-LAN MAC address")
        val destinations = broadcastAddresses()
        if (destinations.isEmpty()) {
            throw IOException("no active IPv4 broadcast interface")
        }
        var sent = 0
        var lastError: Throwable? = null
        DatagramSocket().use { socket ->
            socket.broadcast = true
            destinations.forEach { destination ->
                runCatching {
                    socket.send(DatagramPacket(packet, packet.size, destination, PORT))
                }.onSuccess {
                    sent += 1
                }.onFailure { error ->
                    lastError = error
                }
            }
        }
        if (sent == 0) {
            throw IOException("failed to send Wake-on-LAN packet", lastError)
        }
        return sent
    }

    internal fun buildMagicPacket(targetMac: String): ByteArray? {
        if (!isValidWakeOnLanMac(targetMac)) {
            return null
        }
        val mac = targetMac.split(':').map { part -> part.toInt(16).toByte() }.toByteArray()
        return ByteArray(6 + (mac.size * 16)) { index ->
            if (index < 6) 0xff.toByte() else mac[(index - 6) % mac.size]
        }
    }

    private fun broadcastAddresses(): List<InetAddress> =
        networkInterfaces()
            .filter { networkInterface ->
                runCatching {
                    networkInterface.isUp && !networkInterface.isLoopback && !networkInterface.isPointToPoint
                }.getOrDefault(false)
            }
            .flatMap { networkInterface ->
                networkInterface.interfaceAddresses.mapNotNull { address -> address.broadcast }
            }
            .distinct()

    private fun networkInterfaces(): List<NetworkInterface> =
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()

    private fun <T> Enumeration<T>.toList(): List<T> = buildList {
        while (hasMoreElements()) {
            add(nextElement())
        }
    }
}
