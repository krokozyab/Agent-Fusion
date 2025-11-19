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
