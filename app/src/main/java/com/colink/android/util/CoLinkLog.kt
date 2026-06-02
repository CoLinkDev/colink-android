package com.colink.android.util

import android.util.Log

object CoLinkLog {
    private const val TAG = "CoLink"

    fun d(component: String, message: String) {
        Log.d(TAG, format(component, message))
    }

    fun i(component: String, message: String) {
        Log.i(TAG, format(component, message))
    }

    fun w(component: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.w(TAG, format(component, message))
        } else {
            Log.w(TAG, format(component, message), throwable)
        }
    }

    fun e(component: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.e(TAG, format(component, message))
        } else {
            Log.e(TAG, format(component, message), throwable)
        }
    }

    fun shortId(value: String?): String =
        when {
            value == null -> "null"
            value.length <= 8 -> value
            else -> value.take(8)
        }

    private fun format(component: String, message: String): String =
        "[$component] $message"
}
