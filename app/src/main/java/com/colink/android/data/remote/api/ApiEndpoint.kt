package com.colink.android.data.remote.api

fun apiEndpoint(baseUrl: String, path: String): String =
    "${baseUrl.trim().trimEnd('/')}/${path.trimStart('/')}"
