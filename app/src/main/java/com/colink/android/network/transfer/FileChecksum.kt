package com.colink.android.network.transfer

import java.io.File
import org.bouncycastle.crypto.digests.Blake3Digest

fun File.blake3Checksum(): String {
    val digest = Blake3Digest(256)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    inputStream().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }
    val output = ByteArray(32)
    digest.doFinal(output, 0)
    return "blake3:${output.joinToString("") { "%02x".format(it) }}"
}
