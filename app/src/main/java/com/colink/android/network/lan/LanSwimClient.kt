package com.colink.android.network.lan

import com.colink.android.domain.model.DeviceIdentity
import com.colink.android.network.message.SwimEnvelope
import com.colink.android.network.message.SwimGossip
import com.colink.android.network.message.SwimPayload
import com.colink.android.util.CoLinkLog
import java.io.InterruptedIOException
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

private const val DIRECT_SWIM_TIMEOUT_MILLIS = 1_000L
private const val INDIRECT_SWIM_TIMEOUT_MILLIS = 2_000L

@Singleton
class LanSwimClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    private val directSwimOkHttpClient = swimClient(DIRECT_SWIM_TIMEOUT_MILLIS)
    private val indirectSwimOkHttpClient = swimClient(INDIRECT_SWIM_TIMEOUT_MILLIS)

    suspend fun ping(
        identity: DeviceIdentity,
        ip: String,
        port: Int,
        incarnation: Long,
        seq: Long,
        gossip: List<SwimGossip>,
    ): Result<SwimEnvelope> =
        post(
            ip = ip,
            port = port,
            envelope = SwimEnvelope(
                type = "swim.ping",
                payload = SwimPayload(
                    seq = seq,
                    from = identity.deviceId,
                    incarnation = incarnation,
                    gossip = gossip,
                ),
            ),
            timeoutMillis = DIRECT_SWIM_TIMEOUT_MILLIS,
        )

    suspend fun pingReq(
        identity: DeviceIdentity,
        ip: String,
        port: Int,
        targetDeviceId: String,
        incarnation: Long,
        seq: Long,
        gossip: List<SwimGossip>,
    ): Result<SwimEnvelope> =
        post(
            ip = ip,
            port = port,
            envelope = SwimEnvelope(
                type = "swim.ping-req",
                payload = SwimPayload(
                    seq = seq,
                    from = identity.deviceId,
                    incarnation = incarnation,
                    target = targetDeviceId,
                    gossip = gossip,
                ),
            ),
            timeoutMillis = INDIRECT_SWIM_TIMEOUT_MILLIS,
        )

    suspend fun post(
        ip: String,
        port: Int,
        envelope: SwimEnvelope,
        timeoutMillis: Long,
    ): Result<SwimEnvelope> {
        val startedAt = System.currentTimeMillis()
        val client = when (timeoutMillis) {
            DIRECT_SWIM_TIMEOUT_MILLIS -> directSwimOkHttpClient
            INDIRECT_SWIM_TIMEOUT_MILLIS -> indirectSwimOkHttpClient
            else -> swimClient(timeoutMillis)
        }
        val request = Request.Builder()
            .url("http://$ip:$port/peer/swim/v1")
            .header("Connection", "close")
            .post(
                json.encodeToString(envelope)
                    .toRequestBody("application/json".toMediaType()),
            )
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!continuation.isActive) {
                            return
                        }
                        val result = Result.failure<SwimEnvelope>(e)
                        logSwimResult(envelope.type, ip, port, startedAt, result)
                        continuation.resume(result)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching {
                            response.use {
                                check(it.isSuccessful) { "SWIM ping failed: ${it.code}" }
                                val body = it.body?.string().orEmpty()
                                json.decodeFromString(SwimEnvelope.serializer(), body)
                            }
                        }
                        if (!continuation.isActive) {
                            return
                        }
                        logSwimResult(envelope.type, ip, port, startedAt, result)
                        continuation.resume(result)
                    }
                },
            )
        }
    }

    private fun logSwimResult(
        type: String,
        ip: String,
        port: Int,
        startedAt: Long,
        result: Result<SwimEnvelope>,
    ) {
        val elapsed = System.currentTimeMillis() - startedAt
        result
            .onSuccess {
                CoLinkLog.d("SWIM", "HTTP $type succeeded ip=$ip port=$port elapsed=${elapsed}ms")
            }
            .onFailure { error ->
                if (error.isExpectedProbeFailure()) {
                    CoLinkLog.d("SWIM", "HTTP $type timed out ip=$ip port=$port elapsed=${elapsed}ms")
                } else {
                    CoLinkLog.w("SWIM", "HTTP $type failed ip=$ip port=$port elapsed=${elapsed}ms", error)
                }
            }
    }

    private fun swimClient(timeoutMillis: Long): OkHttpClient =
        okHttpClient.newBuilder()
            .apply {
                interceptors().clear()
                networkInterceptors().clear()
            }
            .retryOnConnectionFailure(false)
            .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .build()
}

private fun Throwable.isExpectedProbeFailure(): Boolean =
    this is InterruptedIOException || cause?.isExpectedProbeFailure() == true
