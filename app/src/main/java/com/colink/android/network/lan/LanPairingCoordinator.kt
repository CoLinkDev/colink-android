package com.colink.android.network.lan

import com.colink.android.domain.model.LanPairingRequest
import com.colink.android.notification.CoLinkNotifier
import com.colink.android.util.CoLinkLog
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
class LanPairingCoordinator @Inject constructor(
    private val notifier: CoLinkNotifier,
) {
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
        val request = LanPairingRequest(
            requestId = requestId,
            deviceId = deviceId,
            name = name,
            code = code,
            reason = reason,
            publicKey = publicKey,
        )
        _pendingRequest.value = request
        CoLinkLog.i(
            "Pairing",
            "LAN pairing requested device=${CoLinkLog.shortId(deviceId)} name=$name reason=$reason",
        )
        notifier.notifyLanPairingRequest(request)
        val accepted = withTimeoutOrNull(60_000) { decision.await() }
        pendingDecisions.remove(requestId)
        if (accepted == null) {
            fail(requestId, "LAN pairing timed out")
            notifier.cancelLanPairingRequest()
            CoLinkLog.w("Pairing", "LAN pairing timed out request=${CoLinkLog.shortId(requestId)}")
            return Decision(requestId, false)
        }
        if (!accepted) {
            clear(requestId)
        }
        CoLinkLog.i(
            "Pairing",
            "LAN pairing decision request=${CoLinkLog.shortId(requestId)} accepted=$accepted",
        )
        return Decision(requestId, accepted)
    }

    fun respond(requestId: String, accepted: Boolean) {
        pendingDecisions.remove(requestId)?.complete(accepted)
        CoLinkLog.i("Pairing", "LAN pairing response request=${CoLinkLog.shortId(requestId)} accepted=$accepted")
        if (accepted) {
            val current = _pendingRequest.value
            if (current?.requestId == requestId) {
                _pendingRequest.value = current.copy(waiting = true, error = null)
            }
            notifier.cancelLanPairingRequest()
        } else {
            clear(requestId)
        }
    }

    fun complete(requestId: String) {
        CoLinkLog.i("Pairing", "LAN pairing completed request=${CoLinkLog.shortId(requestId)}")
        clear(requestId)
    }

    fun fail(requestId: String, reason: String) {
        CoLinkLog.w("Pairing", "LAN pairing failed request=${CoLinkLog.shortId(requestId)} reason=$reason")
        val current = _pendingRequest.value
        if (current?.requestId == requestId) {
            _pendingRequest.value = current.copy(waiting = false, error = reason)
        }
        notifier.cancelLanPairingRequest()
    }

    fun clear(requestId: String) {
        if (_pendingRequest.value?.requestId == requestId) {
            _pendingRequest.value = null
        }
        notifier.cancelLanPairingRequest()
    }

    fun cancel(requestId: String) {
        pendingDecisions.remove(requestId)?.complete(false)
        clear(requestId)
        CoLinkLog.i("Pairing", "LAN pairing cancelled request=${CoLinkLog.shortId(requestId)}")
    }
}
