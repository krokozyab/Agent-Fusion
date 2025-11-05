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
