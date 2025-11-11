package com.orchestrator.context.indexing

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Fast file fingerprint helper. Uses BLAKE3 when the native library is available,
 * otherwise falls back to SHA-256.
 */
object FileHasher {

    private val blake3Available: Boolean = runCatching {
        Class.forName("net.jpountz.blake3.Blake3")
    }.isSuccess

    private const val BUFFER_SIZE = 4 * 1024 * 1024 // 4 MB chunks

    fun computeHash(path: Path): ByteArray {
        require(Files.exists(path)) { "Path does not exist: $path" }
        return if (blake3Available) {
            computeWithBlake3(path)
        } else {
            computeWithSha256(path)
        }
    }

    private fun computeWithBlake3(path: Path): ByteArray {
        val hasherClass = Class.forName("net.jpountz.blake3.Blake3")
        val hasher = hasherClass.getMethod("newInstance").invoke(null)
        val updateMethod = hasherClass.getMethod("update", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val finalizeMethod = hasherClass.getMethod("digest")

        streamFile(path) { buffer, read ->
            updateMethod.invoke(hasher, buffer, 0, read)
        }
        return finalizeMethod.invoke(hasher) as ByteArray
    }

    private fun computeWithSha256(path: Path): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        streamFile(path) { buffer, read ->
            digest.update(buffer, 0, read)
        }
        return digest.digest()
    }

    private fun streamFile(path: Path, consumer: (ByteArray, Int) -> Unit) {
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                consumer(buffer, read)
            }
        }
    }

    fun hex(hash: ByteArray): String = hash.joinToString("") { "%02x".format(it) }
}
