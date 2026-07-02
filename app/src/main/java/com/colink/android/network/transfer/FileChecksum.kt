package com.colink.android.network.transfer

import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream
import java.security.MessageDigest
import org.bouncycastle.crypto.digests.Blake3Digest

private const val DEFAULT_FILE_CHECKSUM_ALGORITHM = "blake3"

class FileChecksumVerifier private constructor(
    private val expectedDigest: String,
    private val hasher: FileChecksumHasher,
) {
    fun update(bytes: ByteArray) {
        hasher.update(bytes)
    }

    fun verify(): Boolean =
        hasher.digestHex().equals(expectedDigest, ignoreCase = true)

    companion object {
        fun from(checksum: String): FileChecksumVerifier {
            val (algorithm, digest) = checksum.splitChecksum()
            if (algorithm == "none") {
                require(digest == "none") { "none checksum must use none:none" }
            }
            return FileChecksumVerifier(digest, FileChecksumHasher.create(algorithm))
        }
    }
}

fun ContentResolver.fileChecksum(uri: Uri): String =
    openInputStream(uri)?.use { input ->
        buildFileChecksum(input, DEFAULT_FILE_CHECKSUM_ALGORITHM)
    } ?: error("file is unavailable")

private fun buildFileChecksum(input: InputStream, algorithm: String): String {
    val hasher = FileChecksumHasher.create(algorithm)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read <= 0) {
            break
        }
        hasher.update(buffer, read)
    }
    return "${hasher.algorithm}:${hasher.digestHex()}"
}

private fun String.splitChecksum(): Pair<String, String> {
    val index = indexOf(':')
    require(index > 0) { "checksum must include an algorithm prefix" }
    return substring(0, index).lowercase() to substring(index + 1)
}

private interface FileChecksumHasher {
    val algorithm: String

    fun update(bytes: ByteArray) {
        update(bytes, bytes.size)
    }

    fun update(bytes: ByteArray, length: Int)

    fun digestHex(): String

    companion object {
        fun create(algorithm: String): FileChecksumHasher =
            when (algorithm.lowercase()) {
                "sha256" -> Sha256FileChecksumHasher()
                "blake3" -> Blake3FileChecksumHasher()
                "none" -> NoopFileChecksumHasher()
                else -> error("unsupported checksum algorithm: $algorithm")
            }
    }
}

private class NoopFileChecksumHasher : FileChecksumHasher {
    override val algorithm: String = "none"

    override fun update(bytes: ByteArray, length: Int) = Unit

    override fun digestHex(): String = "none"
}

private class Sha256FileChecksumHasher : FileChecksumHasher {
    private val digest = MessageDigest.getInstance("SHA-256")

    override val algorithm: String = "sha256"

    override fun update(bytes: ByteArray, length: Int) {
        digest.update(bytes, 0, length)
    }

    override fun digestHex(): String =
        digest.digest().toHex()
}

private class Blake3FileChecksumHasher : FileChecksumHasher {
    private val digest = Blake3Digest(256)

    override val algorithm: String = "blake3"

    override fun update(bytes: ByteArray, length: Int) {
        digest.update(bytes, 0, length)
    }

    override fun digestHex(): String {
        val output = ByteArray(32)
        digest.doFinal(output, 0)
        return output.toHex()
    }
}

private fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it) }
