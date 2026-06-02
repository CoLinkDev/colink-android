package com.colink.android.network.lan

import com.colink.android.domain.model.LanPairingRequest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class LanPairingCoordinator @Inject constructor() {
    private val pendingDecisions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val _pendingRequest = MutableStateFlow<LanPairingRequest?>(null)
    val pendingRequest: StateFlow<LanPairingRequest?> = _pendingRequest.asStateFlow()

    data class Decision(
        val requestId: String,
        val accepted: Boolean,
    )

    suspend fun request(
        deviceId: String,
        name: String,
        publicKey: String,
        code: String,
        reason: String,
    ): Decision {
        val requestId = UUID.randomUUID().toString()
        val decision = CompletableDeferred<Boolean>()
        pendingDecisions[requestId] = decision
        _pendingRequest.value = LanPairingRequest(
            requestId = requestId,
            deviceId = deviceId,
            name = name,
            code = code,
            reason = reason,
            publicKey = publicKey,
        )
        val accepted = withTimeoutOrNull(60_000) { decision.await() }
        pendingDecisions.remove(requestId)
        if (accepted == null) {
            fail(requestId, "LAN pairing timed out")
            return Decision(requestId, false)
        }
        if (!accepted) {
            clear(requestId)
        }
        return Decision(requestId, accepted)
    }

    fun respond(requestId: String, accepted: Boolean) {
        pendingDecisions.remove(requestId)?.complete(accepted)
        if (accepted) {
            val current = _pendingRequest.value
            if (current?.requestId == requestId) {
                _pendingRequest.value = current.copy(waiting = true, error = null)
            }
        } else {
            clear(requestId)
        }
    }

    fun complete(requestId: String) {
        clear(requestId)
    }

    fun fail(requestId: String, reason: String) {
        val current = _pendingRequest.value
        if (current?.requestId == requestId) {
            _pendingRequest.value = current.copy(waiting = false, error = reason)
        }
    }

    fun clear(requestId: String) {
        if (_pendingRequest.value?.requestId == requestId) {
            _pendingRequest.value = null
        }
    }
}
