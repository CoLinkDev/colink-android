package com.colink.android.ui.castboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.colink.android.ui.theme.CoLinkTheme
import com.colink.android.util.LocaleHelper
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class CastBoardActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val language = LocaleHelper.cachedLanguage(newBase)
        val wrappedContext = LocaleHelper.wrap(newBase, language)
        super.attachBaseContext(wrappedContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sourceDeviceId = intent.sourceDeviceId()
        if (sourceDeviceId == null) {
            finish()
            return
        }

        enableEdgeToEdge()
        setContent {
            CoLinkTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    CastBoardFullScreen(
                        requestedSourceDeviceId = sourceDeviceId,
                        onClose = ::finish,
                    )
                }
            }
        }
    }

    companion object {
        const val ACTION_CASTBOARD = "com.colink.android.action.CASTBOARD"
        const val EXTRA_SOURCE_DEVICE_ID = "com.colink.android.extra.SOURCE_DEVICE_ID"

        fun createIntent(context: Context, sourceDeviceId: String): Intent =
            Intent(context, CastBoardActivity::class.java)
                .setAction(ACTION_CASTBOARD)
                .putExtra(EXTRA_SOURCE_DEVICE_ID, sourceDeviceId)
    }
}

private fun Intent.sourceDeviceId(): String? =
    getStringExtra(CastBoardActivity.EXTRA_SOURCE_DEVICE_ID)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
