package com.colink.android.ui.filesystem

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteFilesystemFormatTest {
    @Test
    fun formatsFileSizesWithTheCorrectUnits() {
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
        assertEquals("848 MB", formatBytes(888_816_761))
        assertEquals("1.0 GB", formatBytes(1024L * 1024 * 1024))
    }
}
