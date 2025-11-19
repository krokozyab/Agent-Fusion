package com.orchestrator.context.neo4j

import com.orchestrator.utils.Logger
import org.neo4j.driver.Values

class DocumentStructureIndexer(private val driver: Neo4jDriverInterface) {
    private val log = Logger.logger("com.orchestrator.context.neo4j.DocumentStructureIndexer")

    fun indexDocumentStructure(structure: DocumentStructure) {
        log.info("Indexing document structure: {} ({} sections)", structure.filePath, structure.sections.size)

        // Create or update Document node
        try {
            driver.executeInTransaction(
                """
                MERGE (d:Document {path: ${'$'}path})
                SET d.documentType = ${'$'}documentType, d.fileType = 'DOCUMENT'
                """.trimIndent(),
                mapOf("path" to structure.filePath, "documentType" to structure.documentType.name)
            ) { _ -> Unit }
            log.info("Created/updated Document node for: {}", structure.filePath)
        } catch (e: Exception) {
            log.error("Failed to create Document node for {}: {}", structure.filePath, e.message)
            throw e
        }

        // Create section nodes
        structure.sections.forEach { section ->
            log.debug("Creating section node: title={}, paragraphs={}", section.title, section.paragraphs.size)
            createSectionNode(section, structure.filePath)
        }
        log.info("Successfully indexed document structure: {}", structure.filePath)
    }

    fun linkChunkToSection(chunkId: Long, sectionId: String) {
        driver.executeInTransaction(
            """
            MATCH (s:Section {id: ${'$'}sectionId})
            MERGE (ch:Chunk {id: ${'$'}chunkId})
            MERGE (s)-[:HAS_CHUNK]->(ch)
            """.trimIndent(),
            mapOf("sectionId" to sectionId, "chunkId" to chunkId)
        ) { _ -> Unit }
    }

    fun linkChunkToParagraph(chunkId: Long, paragraphId: String) {
        driver.executeInTransaction(
            """
            MATCH (p:Paragraph {id: ${'$'}paragraphId})
            MERGE (ch:Chunk {id: ${'$'}chunkId})
            MERGE (p)-[:HAS_CHUNK]->(ch)
            """.trimIndent(),
            mapOf("paragraphId" to paragraphId, "chunkId" to chunkId)
        ) { _ -> Unit }
    }

    fun deleteDocumentStructure(filePath: String) {
        driver.executeInTransaction(
            """
            MATCH (d:Document {path: ${'$'}path})
            OPTIONAL MATCH (d)-[:HAS_SECTION]->(s:Section)
            OPTIONAL MATCH (s)-[:HAS_PARAGRAPH]->(p:Paragraph)
            DETACH DELETE d, s, p
            """.trimIndent(),
            mapOf("path" to filePath)
        ) { _ -> Unit }
    }

    /**
     * Link persisted chunks to document paragraphs based on line number ranges.
     * This enables traversing from Sections through Paragraphs to Chunks in Neo4j queries.
     */
    fun linkChunksToParagraphs(
        structure: DocumentStructure,
        persistedChunks: List<com.orchestrator.context.domain.Chunk>,
        sourceChunks: List<com.orchestrator.context.domain.Chunk>
    ) {
        // Build a map of source chunks (before persistence) to persisted chunks (with IDs)
        // Match by content hash or position
        val chunkMap = mutableMapOf<Int, Long>()
        sourceChunks.forEachIndexed { index, sourceChunk ->
            persistedChunks.getOrNull(index)?.let { persistedChunk ->
                chunkMap[index] = persistedChunk.id
            }
        }

        // For each section and its paragraphs, link to overlapping chunks
        structure.sections.forEach { section ->
            section.paragraphs.forEach { paragraph ->
                // Find chunks that overlap with this paragraph's line range
                if (paragraph.startLine != null && paragraph.endLine != null) {
                    sourceChunks.forEachIndexed { index, chunk ->
                        val chunkStart = chunk.startLine ?: 0
                        val chunkEnd = chunk.endLine ?: 0

                        // Check if chunk overlaps with paragraph
                        if (chunkStart <= paragraph.endLine && chunkEnd >= paragraph.startLine) {
                            chunkMap[index]?.let { chunkId ->
                                linkChunkToParagraph(chunkId, paragraph.id)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun createSectionNode(section: Section, documentPath: String) {
        driver.executeInTransaction(
            """
            MATCH (d:Document {path: ${'$'}documentPath})
            MERGE (s:Section {id: ${'$'}id})
            SET s.level = ${'$'}level, s.title = ${'$'}title,
                s.startLine = ${'$'}startLine, s.endLine = ${'$'}endLine
            MERGE (d)-[:HAS_SECTION]->(s)
            """.trimIndent(),
            mapOf(
                "documentPath" to documentPath,
                "id" to section.id,
                "level" to section.level,
                "title" to section.title,
                "startLine" to section.startLine,
                "endLine" to section.endLine
            )
        ) { _ -> Unit }

        // Create paragraph nodes for this section
        section.paragraphs.forEach { paragraph ->
            createParagraphNode(paragraph, section.id)
        }
    }

    private fun createParagraphNode(paragraph: Paragraph, sectionId: String) {
        driver.executeInTransaction(
            """
            MATCH (s:Section {id: ${'$'}sectionId})
            MERGE (p:Paragraph {id: ${'$'}id})
            SET p.ordinal = ${'$'}ordinal, p.startLine = ${'$'}startLine, p.endLine = ${'$'}endLine
            MERGE (s)-[:HAS_PARAGRAPH]->(p)
            """.trimIndent(),
            mapOf(
                "sectionId" to sectionId,
                "id" to paragraph.id,
                "ordinal" to paragraph.ordinal,
                "startLine" to paragraph.startLine,
                "endLine" to paragraph.endLine
            )
        ) { _ -> Unit }
    }
}
