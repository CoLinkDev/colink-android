package com.colink.android.network.lan

import android.content.Context
import com.colink.android.crypto.Handshake
import com.colink.android.data.local.datastore.SettingsDataStore
import com.colink.android.domain.model.DeviceIdentity
import com.colink.android.network.camera.CameraDataFrame
import com.colink.android.network.message.BusinessEnvelope
import com.colink.android.network.message.SwimEnvelope
import com.colink.android.network.message.SwimGossip
import com.colink.android.network.transfer.FileDataFrame
import com.colink.android.util.CoLinkLog
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanWebSocketServerShutdownTest {
    @Test
    fun `stops and restarts with an active websocket`() {
        mockkObject(CoLinkLog)
        every { CoLinkLog.i(any(), any()) } returns Unit
        every { CoLinkLog.w(any(), any(), any()) } returns Unit
        val server = createServer()
        val client = OkHttpClient()
        val uncaught = AtomicReference<Throwable>()
        val previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.compareAndSet(null, error) }

        try {
            repeat(3) {
                val port = checkNotNull(server.start(NoOpLanListener))
                val helloReceived = CountDownLatch(1)
                val closed = CountDownLatch(1)
                val failure = AtomicReference<Throwable>()
                val webSocket = client.newWebSocket(
                    Request.Builder().url("ws://127.0.0.1:$port/peer").build(),
                    object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            helloReceived.countDown()
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, reason)
                            closed.countDown()
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            closed.countDown()
                        }

                        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                            failure.set(error)
                            closed.countDown()
                        }
                    },
                )

                assertTrue("server did not establish the websocket", helloReceived.await(5, TimeUnit.SECONDS))
                server.stop()

                assertFalse(server.isRunning())
                assertTrue("client did not observe websocket shutdown", closed.await(5, TimeUnit.SECONDS))
                assertNull(failure.get())
                webSocket.cancel()
            }
            assertNull(uncaught.get())
        } finally {
            if (server.isRunning()) server.stop()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            Thread.setDefaultUncaughtExceptionHandler(previousUncaughtHandler)
            unmockkObject(CoLinkLog)
        }
    }

    private fun createServer(): LanWebSocketServer {
        val settingsDataStore = mockk<SettingsDataStore>()
        coEvery { settingsDataStore.currentDeviceIdentity() } returns DeviceIdentity(
            userId = null,
            deviceId = "test-device",
            name = "Test Device",
            type = "android",
            publicKey = "public-key",
            privateKey = "private-key",
        )
        return LanWebSocketServer(
            context = mockk<Context>(relaxed = true),
            settingsDataStore = settingsDataStore,
            lanRuntimeState = mockk(relaxed = true),
            json = Json { ignoreUnknownKeys = true },
            handshake = mockk<Handshake>(relaxed = true),
            lanSwimClient = mockk<LanSwimClient>(relaxed = true),
            lanTrustStore = mockk<LanTrustStore>(relaxed = true),
            pairingCoordinator = mockk<LanPairingCoordinator>(relaxed = true),
            pairStringStore = mockk<PairStringStore>(relaxed = true),
        )
    }

    private object NoOpLanListener : LanWebSocketServer.Listener {
        override fun onConnected(deviceId: String) = Unit
        override fun onPeerP2pVersion(deviceId: String, version: String) = Unit
        override fun onPeerBusinessVersion(deviceId: String, version: String) = Unit
        override fun onMessage(
            fromDeviceId: String,
            envelopeId: String,
            correlationId: String?,
            message: BusinessEnvelope,
        ) = Unit

        override fun onDisconnected(deviceId: String) = Unit
        override fun onKeyChanged(deviceId: String, name: String) = Unit
        override fun onTransferConnected(sessionId: String) = Unit
        override fun onTransferFrame(sessionId: String, frame: FileDataFrame) = Unit
        override fun onTransferClosed(sessionId: String) = Unit
        override fun onCameraConnected(sessionId: String) = Unit
        override fun onCameraFrame(sessionId: String, frame: CameraDataFrame) = Unit
        override fun onCameraClosed(sessionId: String) = Unit
        override fun onSwimMessage(message: SwimEnvelope, sourceIp: String?) = Unit
        override fun currentSwimGossip(localDeviceId: String): List<SwimGossip> = emptyList()
        override fun currentSwimIncarnation(localDeviceId: String): Long = 0L
    }
}
