package com.orchestrator.context.chunking

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import java.time.Instant
import java.util.Locale

class SqlChunker(
    private val maxTokens: Int = 600,
    private val overlapPercent: Int = 15
) : SimpleChunker {

    private val routineStartRegex = Regex("""\bCREATE\s+(?:FUNCTION|PROCEDURE|TRIGGER)\b""", RegexOption.IGNORE_CASE)
    private val beginRegex = Regex("""\bBEGIN\b""", RegexOption.IGNORE_CASE)
    private val endRegex = Regex("""\bEND\b""", RegexOption.IGNORE_CASE)
    
    /** A SQL statement together with its 1-based inclusive line span in the source file. */
    private data class SqlStatement(val text: String, val startLine: Int, val endLine: Int)

    override fun chunk(content: String, filePath: String): List<Chunk> {
        if (content.isBlank()) return emptyList()

        val chunks = mutableListOf<Chunk>()
        val statements = splitStatements(content)

        statements.forEachIndexed { index, statement ->
            val trimmed = statement.text.trim()
            if (trimmed.isNotEmpty()) {
                val label = extractLabel(trimmed)
                chunks.add(createChunk(statement.text, label, index, statement.startLine, statement.endLine))
            }
        }

        return OverlapProcessor.addOverlap(chunks, overlapPercent, ::estimateTokens)
    }

    private fun splitStatements(content: String): List<SqlStatement> {
        val statements = mutableListOf<SqlStatement>()
        val currentStatement = StringBuilder()
        val lines = content.lines()
        var inBlockComment = false
        val pendingComments = StringBuilder()
        var insideRoutine = false
        var routineDepth = 0
        // Track the source line span of the statement being assembled. `stmtStartLine` is the first
        // line that contributes to it (leading comment or code); `pendingStartLine` anchors comments
        // that may precede the code. Both are 1-based; -1 means "not set yet".
        var stmtStartLine = -1
        var pendingStartLine = -1

        lines.forEachIndexed { index, line ->
            val lineNumber = index + 1
            val trimmed = line.trim()
            val upperTrimmed = trimmed.uppercase(Locale.US)

            // Handle block comments
            if (trimmed.startsWith("/*")) {
                inBlockComment = true
                if (pendingStartLine == -1) pendingStartLine = lineNumber
                pendingComments.append(line).append("\n")
                if (trimmed.contains("*/")) {
                    inBlockComment = false
                }
                return@forEachIndexed
            }

            if (inBlockComment) {
                pendingComments.append(line).append("\n")
                if (trimmed.contains("*/")) {
                    inBlockComment = false
                }
                return@forEachIndexed
            }

            // Handle line comments
            if (trimmed.startsWith("--")) {
                if (pendingStartLine == -1) pendingStartLine = lineNumber
                pendingComments.append(line).append("\n")
                return@forEachIndexed
            }

            // Skip empty lines between statements
            if (trimmed.isEmpty() && currentStatement.isEmpty()) {
                return@forEachIndexed
            }

            // Add pending comments to current statement
            if (pendingComments.isNotEmpty() && trimmed.isNotEmpty()) {
                if (stmtStartLine == -1) stmtStartLine = if (pendingStartLine != -1) pendingStartLine else lineNumber
                currentStatement.append(pendingComments)
                pendingComments.setLength(0)
                pendingStartLine = -1
            }
            if (stmtStartLine == -1) stmtStartLine = lineNumber

            currentStatement.append(line).append("\n")

            if (!insideRoutine && routineStartRegex.containsMatchIn(upperTrimmed)) {
                insideRoutine = true
                routineDepth = 0
            }

            if (insideRoutine) {
                if (beginRegex.containsMatchIn(upperTrimmed)) {
                    routineDepth += 1
                }
                if (endRegex.containsMatchIn(upperTrimmed)) {
                    routineDepth = (routineDepth - 1).coerceAtLeast(0)
                }
            }

            // Check for statement terminator
            val lineEndsWithSemicolon = line.trimEnd().endsWith(";")
            val shouldTerminate = when {
                !insideRoutine -> lineEndsWithSemicolon
                else -> lineEndsWithSemicolon && routineDepth == 0 && endRegex.containsMatchIn(upperTrimmed)
            }

            if (shouldTerminate) {
                statements.add(SqlStatement(currentStatement.toString().trim(), stmtStartLine, lineNumber))
                currentStatement.setLength(0)
                pendingComments.setLength(0)
                stmtStartLine = -1
                pendingStartLine = -1
                if (insideRoutine) {
                    insideRoutine = false
                    routineDepth = 0
                }
            }
        }

        // Add remaining statement if any
        if (currentStatement.isNotEmpty()) {
            val start = if (stmtStartLine != -1) stmtStartLine else 1
            statements.add(SqlStatement(currentStatement.toString().trim(), start, lines.size))
        }

        return statements
    }
    
    private fun extractLabel(statement: String): String {
        // Remove comments for parsing
        val cleanStatement = statement
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("--.*"), "")
            .trim()
        
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
                val type = match.groupValues[1]
                    .replace(Regex("\\s+"), " ")
                    .uppercase(Locale.US)
                val name = if (match.groupValues.size > 2) match.groupValues[2] else ""
                return if (name.isNotEmpty()) "$type $name" else type
            }
        }

        // Fallback: use first word
        val firstWord = cleanStatement.split(Regex("\\s+")).firstOrNull() ?: "SQL"
        return firstWord.take(20)
    }
    
    private fun createChunk(text: String, label: String, ordinal: Int, startLine: Int?, endLine: Int?): Chunk {
        // start_line/end_line are nullable in the schema. SQL statements are contiguous slices of
        // the source, so splitStatements now supplies real 1-based line spans (previously these
        // were coerced to 1, making every statement claim line 1 and corrupting DiffResolver).
        val path = ChunkPaths.path(ChunkKind.SQL_STATEMENT, label)
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
            createdAt = Instant.now(),
            chunkPath = path
        )
    }
    
    private fun estimateTokens(text: String): Int = text.length / 4
}
