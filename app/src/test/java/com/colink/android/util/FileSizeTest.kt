package com.colink.android.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FileSizeTest {

    @Test
    fun formatsGiBWithOneDecimalPlace() {
        assertEquals("4.6 GB", formatFileSize(4_994_967_296L))
        assertEquals("4.5 GB", formatFileSize(4_939_212_390L))
        assertEquals("1.0 GB", formatFileSize(1_073_741_824L))
        assertEquals("2.0 GB", formatFileSize(2_147_483_648L))
        assertEquals("4.7 GB", formatFileSize(5_129_100_000L))
        assertEquals("9.9 GB", formatFileSize(10_663_676_416L))
        assertEquals("9.8 GB", formatFileSize(10_630_044_057L))
        assertEquals("1024.0 GB", formatFileSize(1_099_511_627_776L))
    }

    @Test
    fun formatsMiBWithOneDecimalPlace() {
        assertEquals("1.0 MB", formatFileSize(1_048_576L))
        assertEquals("117.7 MB", formatFileSize(123_456_789L))
        assertEquals("500.0 MB", formatFileSize(524_288_000L))
        assertEquals("1023.9 MB", formatFileSize(1_073_741_823L))
    }

    @Test
    fun formatsKiBWithOneDecimalPlace() {
        assertEquals("1.0 KB", formatFileSize(1_024L))
        assertEquals("1.5 KB", formatFileSize(1_536L))
        assertEquals("63.9 KB", formatFileSize(65_472L))
        assertEquals("976.5 KB", formatFileSize(999_999L))
        assertEquals("921.5 KB", formatFileSize(943_718L))
        assertEquals("1023.9 KB", formatFileSize(1_048_575L))
    }

    @Test
    fun formatsPlainBytesBelowOneKiB() {
        assertEquals("0 B", formatFileSize(0L))
        assertEquals("1 B", formatFileSize(1L))
        assertEquals("999 B", formatFileSize(999L))
        assertEquals("1023 B", formatFileSize(1_023L))
    }

    @Test
    fun clampsNegativeValuesToZero() {
        assertEquals("0 B", formatFileSize(-1L))
        assertEquals("0 B", formatFileSize(Long.MIN_VALUE))
    }

    @Test
    fun boundaryBetweenUnits() {
        assertEquals("1023 B", formatFileSize(1_023L))
        assertEquals("1.0 KB", formatFileSize(1_024L))
        assertEquals("1023.9 KB", formatFileSize(1_048_575L))
        assertEquals("1.0 MB", formatFileSize(1_048_576L))
        assertEquals("1023.9 MB", formatFileSize(1_073_741_823L))
        assertEquals("1.0 GB", formatFileSize(1_073_741_824L))
    }

    @Test
    fun largeValuesDoNotOverflow() {
        assertEquals("8589934591.9 GB", formatFileSize(Long.MAX_VALUE))
        assertEquals("1000000.0 GB", formatFileSize(1_000_000L * 1_073_741_824L))
    }

    @Test
    fun decimalTruncationIsNotRounding() {
        assertEquals("4.9 GB", formatFileSize(5_313_540_638L))
        assertEquals("1.2 KB", formatFileSize(1_299L))
    }
}
