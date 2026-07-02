package com.colink.android.network.transfer

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileChecksumTest {
    @Test
    fun verifiesNoneChecksumWithoutHashingContent() {
        val verifier = FileChecksumVerifier.from("none:none")

        verifier.update("payload".encodeToByteArray())

        assertTrue(verifier.verify())
    }

    @Test
    fun rejectsMalformedNoneChecksum() {
        assertThrows(IllegalArgumentException::class.java) {
            FileChecksumVerifier.from("none:abc")
        }
    }
}
