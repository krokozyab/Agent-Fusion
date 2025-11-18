package com.orchestrator.context.neo4j

import com.orchestrator.context.chunking.MarkdownChunker
import com.orchestrator.context.chunking.PdfDocumentExtractor
import com.orchestrator.context.chunking.WordDocumentExtractor
import com.orchestrator.context.domain.ChunkKind
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

object DocumentStructureAdapter {
    
    fun extractStructure(filePath: Path, content: String, extension: String): DocumentStructure? {
        return when {
            extension == "pdf" -> parseParagraphStructure(filePath, content, DocumentType.PDF)
            extension in setOf("doc", "docx") -> parseParagraphStructure(filePath, content, DocumentType.WORD)
            extension in setOf("md", "markdown", "txt") -> extractMarkdownStructure(filePath, content)
            else -> null
        }
    }
    
    private fun extractMarkdownStructure(filePath: Path, text: String): DocumentStructure {
        val chunks = MarkdownChunker().chunk(text, filePath.toString(), "markdown")
        
        val sections = chunks
            .filter { it.kind == ChunkKind.MARKDOWN_SECTION || it.kind == ChunkKind.CODE_BLOCK }
            .groupBy { it.summary ?: "Untitled" }
            .map { (title, sectionChunks) ->
                val level = detectHeadingLevel(title)
                val paragraphs = sectionChunks.mapIndexed { index, chunk ->
                    Paragraph(
                        id = UUID.randomUUID().toString(),
                        ordinal = index,
                        content = chunk.content,
                        startLine = chunk.startLine,
                        endLine = chunk.endLine
                    )
                }
                Section(
                    id = UUID.randomUUID().toString(),
                    level = level,
                    title = if (title != "Untitled") title else null,
                    paragraphs = paragraphs,
                    startLine = sectionChunks.firstOrNull()?.startLine,
                    endLine = sectionChunks.lastOrNull()?.endLine
                )
            }
        
        return DocumentStructure(
            filePath = filePath.toString(),
            documentType = DocumentType.MARKDOWN,
            sections = sections
        )
    }
    
    private fun parseParagraphStructure(filePath: Path, text: String, type: DocumentType): DocumentStructure {
        val paragraphs = text.split("\n\n")
            .filter { it.isNotBlank() }
            .mapIndexed { index, para ->
                Paragraph(
                    id = UUID.randomUUID().toString(),
                    ordinal = index,
                    content = para.trim(),
                    startLine = null,
                    endLine = null
                )
            }
        
        val section = Section(
            id = UUID.randomUUID().toString(),
            level = 0,
            title = null,
            paragraphs = paragraphs,
            startLine = null,
            endLine = null
        )
        
        return DocumentStructure(
            filePath = filePath.toString(),
            documentType = type,
            sections = listOf(section)
        )
    }
    
    private fun detectHeadingLevel(title: String): Int {
        val trimmed = title.trimStart()
        if (!trimmed.startsWith('#')) return 0
        return trimmed.takeWhile { it == '#' }.length.coerceIn(0, 6)
    }
}
