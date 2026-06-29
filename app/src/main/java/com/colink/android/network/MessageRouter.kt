package com.colink.android.network

import android.content.Context
import com.colink.android.R
import com.colink.android.network.message.BusinessEnvelope
import com.colink.android.util.CoLinkLog
import com.colink.android.util.LocaleHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRouter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun send(
        targetDeviceId: String,
        business: BusinessEnvelope,
        sendLan: suspend () -> Result<String>,
        sendCloud: () -> Result<String>,
    ): Result<String> {
        val lanResult = sendLan()
        if (lanResult.isSuccess) return lanResult

        val cloudResult = sendCloud()
        if (cloudResult.isSuccess) return cloudResult

        val lanError = lanResult.exceptionOrNull()
        val cloudError = cloudResult.exceptionOrNull()
        val error = IllegalStateException(
            LocaleHelper.localized(context).getString(R.string.message_route_unavailable),
            cloudError ?: lanError,
        )
        CoLinkLog.w(
            "Connection",
            "business send failed device=${CoLinkLog.shortId(targetDeviceId)} type=${business.type} " +
                "lan=${lanError?.message ?: "failed"} cloud=${cloudError?.message ?: "failed"}",
            error,
        )
        return Result.failure(error)
    }
}
