package com.colink.android.service

import android.content.Context

object CoLinkRuntimeStarter {
    fun ensureStarted(context: Context) {
        CoLinkService.start(context.applicationContext)
    }
}
