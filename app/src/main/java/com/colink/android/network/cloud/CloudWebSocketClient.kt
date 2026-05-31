package com.colink.android.network.cloud

import com.colink.android.network.message.CloudClientEnvelope
import com.colink.android.network.message.CloudServerEnvelope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

@Singleton
class CloudWebSocketClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    private var webSocket: WebSocket? = null

    fun connect(
        url: String,
        listener: Listener,
    ) {
        close()
        val request = Request.Builder().url(url).build()
        webSocket = okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    listener.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = runCatching {
                        json.decodeFromString<CloudServerEnvelope>(text)
                    }.getOrNull() ?: return
                    listener.onMessage(message)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onClosed(reason.ifBlank { "closed" })
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    listener.onClosed(t.message ?: "websocket failure")
                }
            },
        )
    }

    fun send(envelope: CloudClientEnvelope): Boolean {
        val payload = json.encodeToString(envelope)
        return webSocket?.send(payload) ?: false
    }

    fun close() {
        webSocket?.close(1000, "client closing")
        webSocket = null
    }

    interface Listener {
        fun onOpen()

        fun onMessage(message: CloudServerEnvelope)

        fun onClosed(reason: String?)
    }
}
