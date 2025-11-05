package com.orchestrator.context.chunking

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.extractor.XWPFWordExtractor

/**
 * Utility for extracting plain text from Microsoft Word documents (.doc, .docx).
 */
object WordDocumentExtractor {
    private val supportedExtensions = setOf("doc", "docx")

    fun supports(extension: String): Boolean = supportedExtensions.contains(extension.lowercase())

    fun extract(path: Path, extension: String): String {
        return when (extension.lowercase()) {
            "doc" -> extractDoc(path)
            "docx" -> extractDocx(path)
            else -> throw IllegalArgumentException("Unsupported Word extension: $extension")
        }.normalizeWhitespace()
    }

    private fun extractDoc(path: Path): String {
        Files.newInputStream(path).use { input ->
            val document = HWPFDocument(input)
            val extractor = WordExtractor(document)
            return try {
                extractor.text
            } finally {
                extractor.close()
                document.close()
            }
        }
    }

    private fun extractDocx(path: Path): String {
        Files.newInputStream(path).use { input ->
            val document = XWPFDocument(input)
            val extractor = XWPFWordExtractor(document)
            return try {
                extractor.text
            } finally {
                extractor.close()
                document.close()
            }
        }
    }

    private fun String.normalizeWhitespace(): String {
        return this
            .replace('\r', '\n')
            .replace("\u0000", "")
            .replace(Regex("\n+"), "\n")
            .trim()
    }
}
