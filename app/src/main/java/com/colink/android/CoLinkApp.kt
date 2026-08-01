package com.colink.android

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import java.io.File

@HiltAndroidApp
class CoLinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        File(cacheDir, "updates").deleteRecursively()
    }
}
