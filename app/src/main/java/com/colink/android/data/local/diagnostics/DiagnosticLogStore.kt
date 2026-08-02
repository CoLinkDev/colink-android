package com.colink.android.data.local.diagnostics

import android.content.Context
import android.os.Build
import com.colink.android.BuildConfig
import com.colink.android.data.local.db.dao.DiagnosticLogDao
import com.colink.android.data.local.db.entity.DiagnosticLogEntity
import com.colink.android.util.CoLinkLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Singleton
class DiagnosticLogStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diagnosticLogDao: DiagnosticLogDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize() {
        CoLinkLog.installSink(::record)
        record(
            level = "INFO",
            component = "App",
            message = "started version=${BuildConfig.VERSION_NAME} debug=${BuildConfig.DEBUG} sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER}/${Build.MODEL}",
        )
        reportPreviousCrash()
        installCrashMarker()
    }

    suspend fun export(fromMillis: Long, toMillis: Long, outputStream: OutputStream) {
        val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())
        val entries = diagnosticLogDao.loadBetween(fromMillis, toMillis)
        OutputStreamWriter(outputStream, Charsets.UTF_8).buffered().use { writer ->
            writer.appendLine("# CoLink Android diagnostics")
            writer.appendLine("# version=${BuildConfig.VERSION_NAME} generated_at=${formatter.format(Instant.now())}")
            writer.appendLine("# range=${formatter.format(Instant.ofEpochMilli(fromMillis))}..${formatter.format(Instant.ofEpochMilli(toMillis))}")
            entries.forEach { entry ->
                writer.append(formatter.format(Instant.ofEpochMilli(entry.createdAt)))
                writer.append(' ')
                writer.append(entry.level)
                writer.append(" [")
                writer.append(entry.component)
                writer.append("] ")
                writer.appendLine(entry.message.replace("\n", "\\n"))
            }
        }
    }

    private fun record(level: String, component: String, message: String) {
        scope.launch {
            diagnosticLogDao.insert(
                DiagnosticLogEntity(
                    createdAt = System.currentTimeMillis(),
                    level = level,
                    component = component,
                    message = message,
                ),
            )
            diagnosticLogDao.trimTo(MAX_ENTRIES)
        }
    }

    private fun reportPreviousCrash() {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val exceptionType = preferences.getString(PREVIOUS_CRASH_TYPE, null) ?: return
        val occurredAt = preferences.getLong(PREVIOUS_CRASH_AT, 0L)
        preferences.edit().clear().apply()
        record(
            level = "ERROR",
            component = "Crash",
            message = "previous uncaught exception type=$exceptionType occurredAt=$occurredAt",
        )
    }

    private fun installCrashMarker() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREVIOUS_CRASH_TYPE, throwable.javaClass.name)
                .putLong(PREVIOUS_CRASH_AT, System.currentTimeMillis())
                .apply()
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private companion object {
        const val MAX_ENTRIES = 20_000
        const val PREFERENCES_NAME = "diagnostic_log_store"
        const val PREVIOUS_CRASH_TYPE = "previous_crash_type"
        const val PREVIOUS_CRASH_AT = "previous_crash_at"
    }
}
