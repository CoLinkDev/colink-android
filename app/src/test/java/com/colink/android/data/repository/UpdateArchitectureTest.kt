package com.colink.android.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateArchitectureTest {
    @Test
    fun selectsTheFirstSupportedAbi() {
        assertEquals(
            "arm64-v8a",
            selectUpdateArchitecture(arrayOf("arm64-v8a", "armeabi-v7a")),
        )
    }

    @Test
    fun skipsUnsupportedAbis() {
        assertEquals(
            "x86_64",
            selectUpdateArchitecture(arrayOf("riscv64", "x86_64", "x86")),
        )
    }

    @Test
    fun returnsNullWhenNoSupportedAbiExists() {
        assertNull(selectUpdateArchitecture(arrayOf("riscv64")))
    }
}
