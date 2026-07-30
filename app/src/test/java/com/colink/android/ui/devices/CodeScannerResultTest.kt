package com.colink.android.ui.devices

import com.google.mlkit.common.MlKitException
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeScannerResultTest {
    @Test
    fun userClosingTheScannerIsNotReportedAsUnavailable() {
        val cancelledByScanner = mockk<MlKitException>()
        every { cancelledByScanner.errorCode } returns MlKitException.CODE_SCANNER_CANCELLED
        val emptyScannerResult = mockk<MlKitException>()
        every { emptyScannerResult.errorCode } returns MlKitException.INTERNAL

        assertTrue(isCodeScannerCancellation(cancelledByScanner))
        assertTrue(isCodeScannerCancellation(emptyScannerResult))
        assertTrue(isCodeScannerCancellation(CancellationException()))
    }

    @Test
    fun scannerUnavailableRemainsAnError() {
        val unavailable = mockk<MlKitException>()
        every { unavailable.errorCode } returns MlKitException.CODE_SCANNER_UNAVAILABLE

        assertFalse(isCodeScannerCancellation(unavailable))
    }
}
