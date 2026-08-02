package com.colink.android

import android.app.Application
import com.colink.android.data.local.diagnostics.DiagnosticLogStore
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class CoLinkApp : Application() {
    @Inject lateinit var diagnosticLogStore: DiagnosticLogStore

    override fun onCreate() {
        super.onCreate()
        File(cacheDir, "updates").deleteRecursively()
        diagnosticLogStore.initialize()
    }
}
