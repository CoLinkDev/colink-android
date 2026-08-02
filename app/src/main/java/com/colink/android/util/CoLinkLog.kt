package com.colink.android.util

import android.util.Log

object CoLinkLog {
    private const val TAG = "CoLink"
    @Volatile private var sink: ((String, String, String) -> Unit)? = null

    fun installSink(newSink: (String, String, String) -> Unit) {
        sink = newSink
    }

    fun d(component: String, message: String) {
        Log.d(TAG, format(component, message))
        sink?.invoke("DEBUG", component, message)
    }

    fun i(component: String, message: String) {
        Log.i(TAG, format(component, message))
        sink?.invoke("INFO", component, message)
    }

    fun w(component: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.w(TAG, format(component, message))
        } else {
            Log.w(TAG, format(component, message), throwable)
        }
        sink?.invoke("WARN", component, formatThrowable(message, throwable))
    }

    fun e(component: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.e(TAG, format(component, message))
        } else {
            Log.e(TAG, format(component, message), throwable)
        }
        sink?.invoke("ERROR", component, formatThrowable(message, throwable))
    }

    fun shortId(value: String?): String =
        when {
            value == null -> "null"
            value.length <= 8 -> value
            else -> value.take(8)
        }

    private fun format(component: String, message: String): String =
        "[$component] $message"

    private fun formatThrowable(message: String, throwable: Throwable?): String =
        if (throwable == null) message else "$message (${throwable.javaClass.simpleName})"
}
