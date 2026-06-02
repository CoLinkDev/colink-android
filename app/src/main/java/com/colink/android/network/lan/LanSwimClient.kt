package com.colink.android.network.lan

import com.colink.android.domain.model.DeviceIdentity
import com.colink.android.network.message.SwimEnvelope
import com.colink.android.network.message.SwimGossip
import com.colink.android.network.message.SwimPayload
import com.colink.android.util.CoLinkLog
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val DIRECT_SWIM_TIMEOUT_MILLIS = 2_000L
private const val INDIRECT_SWIM_TIMEOUT_MILLIS = 2_000L

@Singleton
class LanSwimClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    suspend fun ping(
        identity: DeviceIdentity,
        ip: String,
        port: Int,
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
    ): Result<SwimEnvelope> =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            runCatching {
                val client = okHttpClient.newBuilder()
                    .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                    .build()
                val request = Request.Builder()
                    .url("http://$ip:$port/peer/swim/v1")
                    .post(
                        json.encodeToString(envelope)
                            .toRequestBody("application/json".toMediaType()),
                    )
                    .build()
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "SWIM ping failed: ${response.code}" }
                    val body = response.body?.string().orEmpty()
                    json.decodeFromString(SwimEnvelope.serializer(), body).also {
                        CoLinkLog.d(
                            "SWIM",
                            "HTTP ${envelope.type} succeeded ip=$ip port=$port elapsed=${System.currentTimeMillis() - startedAt}ms",
                        )
                    }
                }
            }.onFailure { error ->
                CoLinkLog.w(
                    "SWIM",
                    "HTTP ${envelope.type} failed ip=$ip port=$port elapsed=${System.currentTimeMillis() - startedAt}ms",
                    error,
                )
            }
        }
}
