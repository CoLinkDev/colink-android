package com.colink.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlTest {
    @Test
    fun normalizesHttpAndHttpsServerUrls() {
        assertEquals(
            "https://sync.example.com/api",
            normalizeServerUrl("  https://sync.example.com/api/  "),
        )
        assertEquals(
            "http://127.0.0.1:8080",
            normalizeServerUrl("http://127.0.0.1:8080/"),
        )
    }

    @Test
    fun rejectsUrlsWithoutAnHttpHost() {
        assertNull(normalizeServerUrl("sync.example.com"))
        assertNull(normalizeServerUrl("ftp://sync.example.com"))
        assertNull(normalizeServerUrl("https:///api"))
    }
}
