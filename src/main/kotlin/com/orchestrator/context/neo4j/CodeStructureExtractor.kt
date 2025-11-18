package com.orchestrator.context.neo4j

interface CodeStructureExtractor {
    fun extractStructure(filePath: String, content: String): CodeStructure
    fun supportsLanguage(language: String): Boolean
}
