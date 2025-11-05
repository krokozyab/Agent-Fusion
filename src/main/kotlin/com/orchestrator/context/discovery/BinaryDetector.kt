package com.orchestrator.context.discovery

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.min

/** Detects files that should be treated as binary to avoid indexing them as text. */
object BinaryDetector {

    private val binaryExtensions = setOf(
        ".exe", ".dll", ".so", ".dylib", ".bin", ".dat", ".class", ".jar",
        ".war", ".ear", ".zip", ".gz", ".gzip", ".tgz", ".tar", ".bz2", ".7z",
        ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".svgz", ".ico", ".heic",
        ".pdf", ".mp3", ".wav", ".flac", ".aac", ".ogg", ".mp4", ".mkv", ".avi",
        ".mov", ".wmv", ".sqlite", ".db", ".duckdb"
    )

    private val textLikeMimeTypes = setOf(
        "application/json",
        "application/xml",
        "application/javascript",
        "application/x-javascript",
        "application/x-sh",
        "application/x-shellscript",
        "application/x-yaml",
        "application/yaml"
    )

    private val binaryMimePrefixes = listOf("image/", "audio/", "video/", "font/", "model/")

    private val binaryMimeTypes = setOf(
        "application/octet-stream",
        "application/pdf",
        "application/zip",
        "application/x-zip-compressed",
        "application/x-tar",
        "application/gzip",
        "application/x-gzip",
        "application/x-7z-compressed",
        "application/vnd.ms-excel",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/x-msdownload"
    )

    fun detectByExtension(path: Path): Boolean {
        val name = path.fileName?.toString()?.lowercase(Locale.US) ?: return false
        val idx = name.lastIndexOf('.')
        if (idx == -1) return false
        val extension = name.substring(idx)
        return binaryExtensions.contains(extension)
    }

    fun detectByMimeType(path: Path): Boolean {
        val mimeType = tryProbeContentType(path)?.lowercase(Locale.US) ?: return false
        if (mimeType.startsWith("text/") || textLikeMimeTypes.contains(mimeType)) {
            return false
        }
        if (binaryMimeTypes.contains(mimeType)) {
            return true
        }
        return binaryMimePrefixes.any { prefix -> mimeType.startsWith(prefix) }
    }

    fun detectByContent(path: Path): Boolean {
        if (!Files.isRegularFile(path)) return false
        val maxBytes = 8 * 1024
        val bytes = ByteArray(maxBytes)
        var totalRead = 0

        try {
            Files.newInputStream(path).use { input ->
                var offset = 0
                while (offset < maxBytes) {
                    val read = input.read(bytes, offset, maxBytes - offset)
                    if (read <= 0) break
                    offset += read
                }
                totalRead = offset
            }
        } catch (_: IOException) {
            return false
        }

        if (totalRead == 0) return false

        // Check for null bytes (strong indicator of binary content)
        for (i in 0 until totalRead) {
            if (bytes[i].toInt() and 0xFF == 0) {
                return true
            }
        }

        // Try to decode as UTF-8. If it's valid UTF-8 with mostly printable characters, it's text
        return !isValidUtf8Text(bytes, totalRead)
    }

    /**
     * Check if byte array is valid UTF-8 text with acceptable ratio of printable characters.
     * Returns true if the content is likely text, false if likely binary.
     */
    private fun isValidUtf8Text(bytes: ByteArray, length: Int): Boolean {
        try {
            // Attempt UTF-8 decoding
            val text = String(bytes, 0, length, Charsets.UTF_8)

            // Count characters that are printable or common whitespace
            var printableCount = 0
            var replacementCharCount = 0
            var totalChars = 0

            for (char in text) {
                totalChars++
                val codePoint = char.code

                // Check for replacement character (indicates invalid UTF-8 sequences)
                if (codePoint == 0xFFFD) {
                    replacementCharCount++
                }

                // Consider as printable:
                // - Common whitespace: space, tab, newline, carriage return
                // - ASCII printable: 32-126
                // - Valid Unicode beyond ASCII (but exclude control characters and replacement char)
                when {
                    codePoint in 32..126 -> printableCount++  // ASCII printable
                    codePoint == 9 || codePoint == 10 || codePoint == 13 -> printableCount++  // Tab, LF, CR
                    codePoint > 127 && codePoint != 0xFFFD && !Character.isISOControl(codePoint) -> printableCount++  // Unicode printable
                }
            }

            if (totalChars == 0) return false

            // If there are too many replacement characters, it's likely binary
            val replacementRatio = replacementCharCount.toDouble() / totalChars.toDouble()
            if (replacementRatio > 0.05) {
                return false  // More than 5% replacement characters suggests binary
            }

            // If more than 85% of characters are printable, consider it text
            val printableRatio = printableCount.toDouble() / totalChars.toDouble()
            return printableRatio > 0.85

        } catch (_: Exception) {
            // If UTF-8 decoding fails, it's likely binary
            return false
        }
    }

    fun isBinary(path: Path): Boolean {
        if (detectByExtension(path)) return true
        if (detectByMimeType(path)) return true
        return detectByContent(path)
    }

    private fun tryProbeContentType(path: Path): String? = try {
        Files.probeContentType(path)
    } catch (_: IOException) {
        null
    }
}
