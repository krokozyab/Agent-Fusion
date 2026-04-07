package com.orchestrator.context.chunking

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DocumentExtractorTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `extract docx content to normalized text`() {
        val docxPath = tempDir.resolve("sample.docx")
        createDocx(docxPath, listOf("First line", "Second line"))

        val extracted = WordDocumentExtractor.extract(docxPath, "docx")

        assertEquals("First line\nSecond line", extracted)
        assertTrue(WordDocumentExtractor.supports("docx"))
    }

    @Test
    fun `extract falls back to HTML stripping for Confluence doc exports`() {
        // Confluence exports pages as HTML fragments with a .doc extension.
        // Real users have seen: "The document is really a UNKNOWN file" from POI.
        val docPath = tempDir.resolve("confluence-page.doc")
        val html = """
            <html><head><title>AME Approval and GDPR Masking</title>
            <style>body { color: red; }</style>
            </head><body>
            <h1>AME Approval</h1>
            <p>First paragraph &amp; detail.</p>
            <script>alert('x');</script>
            <p>Second paragraph &#8212; with em-dash.</p>
            </body></html>
        """.trimIndent()
        Files.writeString(docPath, html)

        val extracted = WordDocumentExtractor.extract(docPath, "doc")

        assertTrue(extracted.contains("AME Approval"), "should contain heading text")
        assertTrue(extracted.contains("First paragraph & detail."), "should decode entities")
        assertTrue(extracted.contains("Second paragraph"), "should contain second paragraph")
        assertTrue(!extracted.contains("<h1>"), "HTML tags must be stripped")
        assertTrue(!extracted.contains("alert"), "script contents must be stripped")
        assertTrue(!extracted.contains("color: red"), "style contents must be stripped")
    }

    @Test
    fun `extract handles docx file saved with doc extension`() {
        // User renamed foo.docx to foo.doc. Should still work via magic sniffing.
        val docPath = tempDir.resolve("misnamed.doc")
        createDocx(docPath, listOf("Hello", "World"))

        val extracted = WordDocumentExtractor.extract(docPath, "doc")

        assertEquals("Hello\nWorld", extracted)
    }

    @Test
    fun `extract pdf content to normalized text`() {
        val pdfPath = tempDir.resolve("sample.pdf")
        createPdf(pdfPath, listOf("Heading", "Body line"))

        val extracted = PdfDocumentExtractor.extract(pdfPath)

        assertEquals("Heading\nBody line", extracted)
    }

    private fun createDocx(path: Path, lines: List<String>) {
        XWPFDocument().use { document ->
            lines.forEach { line ->
                val paragraph = document.createParagraph()
                val run = paragraph.createRun()
                run.setText(line)
            }
            Files.newOutputStream(path).use { output ->
                document.write(output)
            }
        }
    }

    private fun createPdf(path: Path, lines: List<String>) {
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.newLineAtOffset(72f, 700f)
                lines.forEachIndexed { index, line ->
                    if (index > 0) {
                        content.newLineAtOffset(0f, -18f)
                    }
                    content.showText(line)
                }
                content.endText()
            }
            document.save(path.toFile())
        }
    }
}
