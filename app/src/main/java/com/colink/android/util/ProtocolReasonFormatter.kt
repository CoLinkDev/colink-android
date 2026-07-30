package com.colink.android.util

import android.content.Context
import com.colink.android.R

object ProtocolReasonFormatter {
    fun format(context: Context, reason: String?): String {
        if (reason.isNullOrBlank()) return ""
        if (!reason.startsWith("colink:")) return reason
        
        return when (reason) {
            "colink:transfer.user_rejected.v1" -> context.getString(R.string.err_transfer_user_rejected)
            "colink:transfer.user_cancelled.v1" -> context.getString(R.string.err_transfer_user_cancelled)
            "colink:transfer.checksum_mismatch.v1" -> context.getString(R.string.err_transfer_checksum_mismatch)
            "colink:transfer.storage_full.v1" -> context.getString(R.string.err_transfer_storage_full)
            "colink:auth.signature_invalid.v1" -> context.getString(R.string.err_handshake_signature_invalid)
            "colink:auth.key_changed.v1" -> context.getString(R.string.err_handshake_key_changed)
            "colink:pairing.user_rejected.v1" -> context.getString(R.string.err_handshake_user_rejected)
            "colink:pairing.cancelled.v1" -> context.getString(R.string.err_pairing_cancelled)
            "colink:pairing.timeout.v1" -> context.getString(R.string.err_pairing_timeout)
            "colink:pairing.connection_closed.v1" -> context.getString(R.string.err_pairing_connection_closed)
            "colink:pairing.pair_string_invalid.v1",
            "colink:pairing.identity_mismatch.v1",
            -> context.getString(R.string.err_pair_qr_invalid)
            "colink:pairing.pair_string_expired.v1" -> context.getString(R.string.err_pair_qr_expired)
            "colink:pairing.pair_string_unavailable.v1" -> context.getString(R.string.err_pair_qr_unavailable)
            "colink:pairing.device_unavailable.v1" -> context.getString(R.string.err_pair_qr_device_unavailable)
            "colink:pairing.already_trusted.v1" -> context.getString(R.string.err_pair_qr_already_paired)
            "colink:pairing.local_identity_unavailable.v1" -> context.getString(R.string.err_pair_qr_not_ready)
            else -> reason
        }
    }
}
