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
        val buffer = ByteArray(1024)
        var totalRead = 0
        var nonAscii = 0

        try {
            Files.newInputStream(path).use { input ->
                while (totalRead < maxBytes) {
                    val toRead = min(buffer.size, maxBytes - totalRead)
                    val read = input.read(buffer, 0, toRead)
                    if (read <= 0) break

                    for (i in 0 until read) {
                        val b = buffer[i].toInt() and 0xFF
                        if (b == 0) {
                            return true
                        }
                        if (!b.isLikelyText()) {
                            nonAscii++
                        }
                    }
                    totalRead += read
                }
            }
        } catch (_: IOException) {
            return false
        }

        if (totalRead == 0) return false
        val ratio = nonAscii.toDouble() / totalRead.toDouble()
        return ratio > 0.30
    }

    fun isBinary(path: Path): Boolean {
        if (detectByExtension(path)) return true
        if (detectByMimeType(path)) return true
        return detectByContent(path)
    }

    private fun Int.isLikelyText(): Boolean =
        this == 9 || this == 10 || this == 12 || this == 13 || (this in 32..126)

    private fun tryProbeContentType(path: Path): String? = try {
        Files.probeContentType(path)
    } catch (_: IOException) {
        null
    }
}
