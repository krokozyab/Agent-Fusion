package com.orchestrator.context.chunking

import java.nio.file.Files
import java.nio.file.Path
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper

/**
 * Utility for extracting text content from PDF documents.
 */
object PdfDocumentExtractor {
    fun extract(path: Path): String {
        Files.newInputStream(path).use { inputStream ->
            PDDocument.load(inputStream).use { document ->
                val stripper = PDFTextStripper()
                // Preserve paragraph boundaries
                stripper.sortByPosition = true
                val text = stripper.getText(document)
                return text.normalizeWhitespace()
            }
        }
    }

    private fun String.normalizeWhitespace(): String =
        this.replace('\r', '\n')
            .replace("\u0000", "")
            .replace(Regex("\n+"), "\n")
            .trim()
}
