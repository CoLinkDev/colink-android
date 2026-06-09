package com.colink.android

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle

class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forwardShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        forwardShare(intent)
    }

    private fun forwardShare(source: Intent?) {
        if (source?.action == Intent.ACTION_SEND) {
            val target = Intent(source).apply {
                setClass(this@ShareActivity, MainActivity::class.java)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(target)
        }
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
