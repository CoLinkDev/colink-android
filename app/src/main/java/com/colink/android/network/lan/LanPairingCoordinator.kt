package com.colink.android.network.lan

import com.colink.android.domain.model.LanPairingRequest
import com.colink.android.notification.CoLinkNotifier
import com.colink.android.util.CoLinkLog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PAIRING_DECISION_TIMEOUT_MILLIS = 240_000L
const val REASON_PAIRING_TIMEOUT = "colink:pairing.timeout.v1"
const val MESSAGE_PAIRING_TIMEOUT = "LAN pairing timed out"
const val REASON_PAIRING_USER_REJECTED = "colink:pairing.user_rejected.v1"
const val MESSAGE_PAIRING_USER_REJECTED = "User declined the pairing request"
const val REASON_PAIRING_CANCELLED = "colink:pairing.cancelled.v1"
const val REASON_PAIRING_CONNECTION_CLOSED = "colink:pairing.connection_closed.v1"

@Singleton
class LanPairingCoordinator @Inject constructor(
    private val notifier: CoLinkNotifier,
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val cancellationActions = ConcurrentHashMap<String, () -> Unit>()
    private val decisionActions = ConcurrentHashMap<String, (Decision) -> Unit>()
    private val decisionTimeouts = ConcurrentHashMap<String, Job>()
    private val _pendingRequest = MutableStateFlow<LanPairingRequest?>(null)
    val pendingRequest: StateFlow<LanPairingRequest?> = _pendingRequest.asStateFlow()

    data class Decision(
        val requestId: String,
        val accepted: Boolean,
        val reason: String? = null,
        val message: String? = null,
    )

    fun showVerification(
        deviceId: String,
        name: String,
        publicKey: String,
        code: String,
        reason: String,
    ): String {
        val requestId = UUID.randomUUID().toString()
        _pendingRequest.value = LanPairingRequest(
            requestId = requestId,
            deviceId = deviceId,
            name = name,
            code = code,
            reason = reason,
            publicKey = publicKey,
            initiatedLocally = true,
            waiting = true,
        )
        CoLinkLog.i(
            "Pairing",
            "LAN pairing verification shown device=${CoLinkLog.shortId(deviceId)} name=$name",
        )
        return requestId
    }

    fun registerCancellation(requestId: String, action: () -> Unit) {
        cancellationActions[requestId] = action
    }

    fun request(
        deviceId: String,
        name: String,
        publicKey: String,
        code: String,
        reason: String,
        initiatedLocally: Boolean,
        onDecision: (Decision) -> Unit,
    ): String {
        val requestId = UUID.randomUUID().toString()
        decisionActions[requestId] = onDecision
        val request = LanPairingRequest(
            requestId = requestId,
            deviceId = deviceId,
            name = name,
            code = code,
            reason = reason,
            publicKey = publicKey,
            initiatedLocally = initiatedLocally,
        )
        _pendingRequest.value = request
        CoLinkLog.i(
            "Pairing",
            "LAN pairing requested device=${CoLinkLog.shortId(deviceId)} name=$name reason=$reason",
        )
        if (!initiatedLocally) {
            scope.launch { notifier.notifyLanPairingRequest(request) }
        }
        decisionTimeouts[requestId] = scope.launch {
            delay(PAIRING_DECISION_TIMEOUT_MILLIS)
            resolve(
                requestId,
                accepted = false,
                reason = REASON_PAIRING_TIMEOUT,
                message = MESSAGE_PAIRING_TIMEOUT,
            )
        }
        return requestId
    }

    fun respond(requestId: String, accepted: Boolean) {
        resolve(
            requestId,
            accepted,
            reason = if (accepted) null else REASON_PAIRING_USER_REJECTED,
            message = if (accepted) null else MESSAGE_PAIRING_USER_REJECTED,
        )
    }

    fun complete(requestId: String) {
        CoLinkLog.i("Pairing", "LAN pairing completed request=${CoLinkLog.shortId(requestId)}")
        removeActions(requestId)
        clear(requestId)
    }

    fun fail(requestId: String, reason: String) {
        CoLinkLog.w("Pairing", "LAN pairing failed request=${CoLinkLog.shortId(requestId)} reason=$reason")
        if (reason == REASON_PAIRING_CONNECTION_CLOSED) {
            clear(requestId)
            return
        }
        val current = _pendingRequest.value
        if (current?.requestId == requestId && current.error != null) {
            return
        }
        removeActions(requestId)
        if (current?.requestId == requestId) {
            _pendingRequest.value = current.copy(waiting = false, error = reason)
        }
        notifier.cancelLanPairingRequest()
    }

    fun hasPendingRequest(deviceId: String): Boolean =
        _pendingRequest.value?.deviceId == deviceId

    fun failPendingRequest(deviceId: String, reason: String) {
        _pendingRequest.value
            ?.takeIf { it.deviceId == deviceId }
            ?.let { fail(it.requestId, reason) }
    }

    fun clear(requestId: String) {
        removeActions(requestId)
        if (_pendingRequest.value?.requestId == requestId) {
            _pendingRequest.value = null
        }
        notifier.cancelLanPairingRequest()
    }

    fun cancel(requestId: String) {
        cancellationActions.remove(requestId)?.invoke()
        fail(requestId, REASON_PAIRING_CANCELLED)
        CoLinkLog.i("Pairing", "LAN pairing cancelled request=${CoLinkLog.shortId(requestId)}")
    }

    private fun resolve(requestId: String, accepted: Boolean, reason: String?, message: String?) {
        val action = decisionActions.remove(requestId) ?: return
        decisionTimeouts.remove(requestId)?.cancel()
        val current = _pendingRequest.value
        if (current?.requestId == requestId) {
            _pendingRequest.value = if (accepted) {
                current.copy(waiting = true, error = null)
            } else {
                current.copy(waiting = false, error = reason ?: message)
            }
        }
        notifier.cancelLanPairingRequest()
        action(Decision(requestId, accepted, reason, message))
        CoLinkLog.i("Pairing", "LAN pairing decision request=${CoLinkLog.shortId(requestId)} accepted=$accepted")
    }

    private fun removeActions(requestId: String) {
        cancellationActions.remove(requestId)
        decisionActions.remove(requestId)
        decisionTimeouts.remove(requestId)?.cancel()
    }
}
