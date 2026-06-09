package com.colink.android.ui.castboard

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.colink.android.MainActivity
import com.colink.android.service.CoLinkRuntimeStarter
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
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val sourceDeviceId = intent.sourceDeviceId()
        if (sourceDeviceId == null) {
            finish()
            return
        }

        CoLinkRuntimeStarter.ensureStarted(this)

        setContent {
            CoLinkTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    CastBoardFullScreen(
                        requestedSourceDeviceId = sourceDeviceId,
                        onClose = {
                            if (isTaskRoot) {
                                startActivity(Intent(this, MainActivity::class.java))
                            }
                            finish()
                        },
                    )
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
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
