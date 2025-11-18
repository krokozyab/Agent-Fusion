package com.orchestrator.context.neo4j

data class DocumentStructure(
    val filePath: String,
    val documentType: DocumentType,
    val sections: List<Section>,
    val metadata: Map<String, String> = emptyMap()
)

data class Section(
    val id: String,
    val level: Int,
    val title: String?,
    val paragraphs: List<Paragraph>,
    val startLine: Int?,
    val endLine: Int?
)

data class Paragraph(
    val id: String,
    val ordinal: Int,
    val content: String,
    val startLine: Int?,
    val endLine: Int?
)

enum class DocumentType {
    PDF,
    WORD,
    MARKDOWN,
    PLAINTEXT
}
