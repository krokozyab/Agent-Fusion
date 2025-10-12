package com.orchestrator.context.chunking

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import java.time.Instant

class SqlChunker(private val maxTokens: Int = 600) : SimpleChunker {
    
    override fun chunk(content: String, filePath: String): List<Chunk> {
        if (content.isBlank()) return emptyList()
        
        val chunks = mutableListOf<Chunk>()
        val statements = splitStatements(content)
        
        statements.forEachIndexed { index, statement ->
            val trimmed = statement.trim()
            if (trimmed.isNotEmpty()) {
                val label = extractLabel(trimmed)
                chunks.add(createChunk(statement, label, index, null, null))
            }
        }
        
        return chunks
    }
    
    private fun splitStatements(content: String): List<String> {
        val statements = mutableListOf<String>()
        val currentStatement = StringBuilder()
        val lines = content.lines()
        var inBlockComment = false
        var pendingComments = StringBuilder()
        
        for (line in lines) {
            val trimmed = line.trim()
            
            // Handle block comments
            if (trimmed.startsWith("/*")) {
                inBlockComment = true
                pendingComments.append(line).append("\n")
                if (trimmed.contains("*/")) {
                    inBlockComment = false
                }
                continue
            }
            
            if (inBlockComment) {
                pendingComments.append(line).append("\n")
                if (trimmed.contains("*/")) {
                    inBlockComment = false
                }
                continue
            }
            
            // Handle line comments
            if (trimmed.startsWith("--")) {
                pendingComments.append(line).append("\n")
                continue
            }
            
            // Skip empty lines between statements
            if (trimmed.isEmpty() && currentStatement.isEmpty()) {
                continue
            }
            
            // Add pending comments to current statement
            if (pendingComments.isNotEmpty() && trimmed.isNotEmpty()) {
                currentStatement.append(pendingComments)
                pendingComments.clear()
            }
            
            currentStatement.append(line).append("\n")
            
            // Check for statement terminator
            if (line.trimEnd().endsWith(";")) {
                statements.add(currentStatement.toString().trim())
                currentStatement.clear()
            }
        }
        
        // Add remaining statement if any
        if (currentStatement.isNotEmpty()) {
            statements.add(currentStatement.toString().trim())
        }
        
        return statements
    }
    
    private fun extractLabel(statement: String): String {
        // Remove comments for parsing
        val cleanStatement = statement
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("--.*"), "")
            .trim()
        
        val upperStatement = cleanStatement.uppercase()
        
        // Extract statement type and table/object name
        val patterns = listOf(
            Regex("""^(CREATE\s+(?:TABLE|VIEW|INDEX|PROCEDURE|FUNCTION|TRIGGER))\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)""", RegexOption.IGNORE_CASE),
            Regex("""^(DROP\s+(?:TABLE|VIEW|INDEX|PROCEDURE|FUNCTION|TRIGGER))\s+(?:IF\s+EXISTS\s+)?(\w+)""", RegexOption.IGNORE_CASE),
            Regex("""^(ALTER\s+TABLE)\s+(\w+)""", RegexOption.IGNORE_CASE),
            Regex("""^(INSERT\s+INTO)\s+(\w+)""", RegexOption.IGNORE_CASE),
            Regex("""^(UPDATE)\s+(\w+)""", RegexOption.IGNORE_CASE),
            Regex("""^(DELETE\s+FROM)\s+(\w+)""", RegexOption.IGNORE_CASE),
            Regex("""^(SELECT).*?\s+FROM\s+(\w+)""", RegexOption.IGNORE_CASE),
            Regex("""^(GRANT|REVOKE)\s+""", RegexOption.IGNORE_CASE),
            Regex("""^(BEGIN|COMMIT|ROLLBACK)""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in patterns) {
            val match = pattern.find(cleanStatement)
            if (match != null) {
                val type = match.groupValues[1].replace(Regex("\\s+"), " ")
                val name = if (match.groupValues.size > 2) match.groupValues[2] else ""
                return if (name.isNotEmpty()) "$type $name" else type
            }
        }
        
        // Fallback: use first word
        val firstWord = cleanStatement.split(Regex("\\s+")).firstOrNull() ?: "SQL"
        return firstWord.take(20)
    }
    
    private fun createChunk(text: String, label: String, ordinal: Int, startLine: Int?, endLine: Int?): Chunk {
        return Chunk(
            id = 0,
            fileId = 0,
            ordinal = ordinal,
            kind = ChunkKind.SQL_STATEMENT,
            startLine = startLine,
            endLine = endLine,
            tokenEstimate = estimateTokens(text),
            content = text,
            summary = label,
            createdAt = Instant.now()
        )
    }
    
    private fun estimateTokens(text: String): Int = text.length / 4
}
