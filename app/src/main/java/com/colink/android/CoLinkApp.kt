package com.colink.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.colink.android.data.local.diagnostics.DiagnosticLogStore
import com.colink.android.sync.NotesSyncWorker
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class CoLinkApp : Application(), Configuration.Provider {
    @Inject lateinit var diagnosticLogStore: DiagnosticLogStore
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        File(cacheDir, "updates").deleteRecursively()
        diagnosticLogStore.initialize()
        NotesSyncWorker.schedule(this)
    }
}
