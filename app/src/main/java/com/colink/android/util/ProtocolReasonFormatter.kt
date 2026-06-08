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
            "colink:handshake.signature_invalid.v1" -> context.getString(R.string.err_handshake_signature_invalid)
            "colink:handshake.key_changed.v1" -> context.getString(R.string.err_handshake_key_changed)
            "colink:handshake.user_rejected.v1" -> context.getString(R.string.err_handshake_user_rejected)
            else -> reason
        }
    }
}
