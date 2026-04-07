package com.orchestrator.context.chunking

import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.poifs.filesystem.FileMagic
import org.apache.poi.xwpf.extractor.XWPFWordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument

/**
 * Utility for extracting plain text from Microsoft Word documents (.doc, .docx).
 *
 * The file extension is only a hint. Confluence and some other exporters produce
 * HTML files with a `.doc` extension, and users occasionally rename `.docx` to
 * `.doc` or vice versa. This extractor therefore sniffs the real file magic
 * (OLE2 vs OOXML vs HTML/plain-text) and dispatches accordingly, falling back to
 * a cheap HTML → text strip when the content is not a real Word binary.
 */
object WordDocumentExtractor {
    private val supportedExtensions = setOf("doc", "docx")

    fun supports(extension: String): Boolean = supportedExtensions.contains(extension.lowercase())

    fun extract(path: Path, extension: String): String {
        val raw = BufferedInputStream(Files.newInputStream(path)).use { input ->
            when (FileMagic.valueOf(input)) {
                FileMagic.OLE2 -> extractDoc(path)
                FileMagic.OOXML -> extractDocx(path)
                FileMagic.HTML -> stripHtml(Files.readString(path))
                // "UNKNOWN" in POI's terminology usually means "not a recognised binary
                // office format". Confluence page.doc exports land here: they are HTML
                // fragments without a <html> root, so FileMagic doesn't tag them as HTML.
                // Treat the bytes as UTF-8 text and strip tags as a best effort.
                else -> fallbackToText(path, extension)
            }
        }
        return raw.normalizeWhitespace()
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

    private fun fallbackToText(path: Path, extension: String): String {
        val bytes = Files.readAllBytes(path)
        val text = String(bytes, Charsets.UTF_8)
        // Confluence HTML-as-.doc is the common case; strip tags unconditionally —
        // on plain text (no tags) this is a no-op.
        return stripHtml(text)
    }

    private val htmlTagRegex = Regex("<[^>]+>")
    private val htmlEntityRegex = Regex("&(#\\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);")

    private fun stripHtml(html: String): String {
        // Drop <script>/<style> blocks entirely (including content) before removing tags.
        val withoutScripts = html
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
        val withoutTags = htmlTagRegex.replace(withoutScripts, " ")
        return htmlEntityRegex.replace(withoutTags) { match ->
            when (val body = match.value.removePrefix("&").removeSuffix(";")) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "nbsp" -> " "
                else -> {
                    when {
                        body.startsWith("#x") || body.startsWith("#X") ->
                            body.drop(2).toIntOrNull(16)?.let { cp -> Character.toString(cp) } ?: " "
                        body.startsWith("#") ->
                            body.drop(1).toIntOrNull()?.let { cp -> Character.toString(cp) } ?: " "
                        else -> " "
                    }
                }
            }
        }
    }

    private fun String.normalizeWhitespace(): String {
        return this
            .replace('\r', '\n')
            .replace("\u0000", "")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\n+"), "\n")
            .trim()
    }
}
