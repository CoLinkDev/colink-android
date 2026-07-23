package com.colink.android.network.cloud

import com.colink.android.network.message.CloudClientEnvelope
import com.colink.android.network.message.CloudServerEnvelope
import com.colink.android.util.CoLinkLog
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
        CoLinkLog.i("Cloud", "opening cloud websocket url=$url")
        webSocket = okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    CoLinkLog.i("Cloud", "cloud websocket opened")
                    listener.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = runCatching {
                        json.decodeFromString<CloudServerEnvelope>(text)
                    }.onFailure { error ->
                        CoLinkLog.w("Cloud", "failed to decode cloud websocket message", error)
                    }.getOrNull() ?: return
                    listener.onMessage(message)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    CoLinkLog.w("Cloud", "cloud websocket closed code=$code reason=$reason")
                    listener.onClosed(reason.ifBlank { "closed" })
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    CoLinkLog.w("Cloud", "cloud websocket failed", t)
                    listener.onClosed(t.message ?: "websocket failure")
                }
            },
        )
    }

    fun send(envelope: CloudClientEnvelope): Boolean {
        val payload = json.encodeToString(envelope)
        val sent = webSocket?.send(payload) ?: false
        if (!sent) {
            CoLinkLog.w("Cloud", "cloud websocket send failed type=${envelope.type}")
        }
        return sent
    }

    fun queuedBytes(): Long = webSocket?.queueSize() ?: 0L

    fun close() {
        CoLinkLog.d("Cloud", "closing cloud websocket")
        webSocket?.close(1000, "client closing")
        webSocket = null
    }

    interface Listener {
        fun onOpen()

        fun onMessage(message: CloudServerEnvelope)

        fun onClosed(reason: String?)
    }
}
