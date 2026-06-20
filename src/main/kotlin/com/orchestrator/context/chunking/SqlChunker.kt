package com.orchestrator.context.chunking

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import java.time.Instant
import java.util.Locale

class SqlChunker(
    private val maxTokens: Int = 600,
    private val overlapPercent: Int = 15
) : SimpleChunker {

    // A PL/SQL routine start. Crucially tolerates `OR REPLACE` and `[NON]EDITIONABLE` (which sit
    // between CREATE and the object keyword in virtually all real Oracle DDL) and adds PACKAGE [BODY]
    // and TYPE [BODY]. The previous `CREATE\s+(FUNCTION|PROCEDURE|TRIGGER)` never matched
    // `CREATE OR REPLACE PROCEDURE ...`, so Oracle bodies were split on every internal `;`.
    private val routineStartRegex = Regex(
        """\bCREATE\s+(?:OR\s+REPLACE\s+)?(?:(?:NON)?EDITIONABLE\s+)?(?:PACKAGE(?:\s+BODY)?|TYPE(?:\s+BODY)?|PROCEDURE|FUNCTION|TRIGGER)\b""",
        RegexOption.IGNORE_CASE
    )
    // PACKAGE/TYPE [BODY] need different termination: their inner sub-program ENDs return depth to 0
    // while still inside the package, so the `;`+END heuristic mis-fires. These terminate only on the
    // SQL*Plus `/` (or EOF).
    private val packageStartRegex = Regex(
        """\bCREATE\s+(?:OR\s+REPLACE\s+)?(?:(?:NON)?EDITIONABLE\s+)?(?:PACKAGE|TYPE)\b""",
        RegexOption.IGNORE_CASE
    )
    // Anonymous PL/SQL block opener (script-style `DECLARE ...` / bare `BEGIN ...`). A `BEGIN` that
    // ends with `;` on the same line is a transaction-control statement, not a block, so the caller
    // guards on that.
    private val anonBlockStartRegex = Regex("""^(?:DECLARE|BEGIN)\b""", RegexOption.IGNORE_CASE)
    private val beginRegex = Regex("""\bBEGIN\b""", RegexOption.IGNORE_CASE)
    // A block-closing END. Excludes `END IF` / `END LOOP` / `END CASE`, which close control
    // structures (no matching BEGIN) and would otherwise drive the nesting depth negative.
    private val blockEndRegex = Regex("""\bEND\b(?!\s+(?:IF|LOOP|CASE)\b)""", RegexOption.IGNORE_CASE)
    
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
        var routineIsPackage = false
        // Track the source line span of the statement being assembled. `stmtStartLine` is the first
        // line that contributes to it (leading comment or code); `pendingStartLine` anchors comments
        // that may precede the code. Both are 1-based; -1 means "not set yet".
        var stmtStartLine = -1
        var pendingStartLine = -1
        // 1-based line number of the last line actually appended to currentStatement, so a `/`
        // terminator (which is itself dropped) can close the statement at the real last content line.
        var lastAppendedLine = -1

        fun resetRoutineState() {
            insideRoutine = false
            routineDepth = 0
            routineIsPackage = false
        }

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

            // SQL*Plus slash terminator: a line containing only `/` ends the current PL/SQL block
            // (the canonical, unambiguous terminator for packages/types/anonymous blocks). The `/`
            // itself is a directive, not part of the object, so it is dropped from the chunk.
            if (trimmed == "/") {
                if (currentStatement.isNotEmpty()) {
                    val start = if (stmtStartLine != -1) stmtStartLine else lineNumber
                    val end = if (lastAppendedLine != -1) lastAppendedLine else lineNumber
                    statements.add(SqlStatement(currentStatement.toString().trim(), start, end.coerceAtLeast(start)))
                    currentStatement.setLength(0)
                    pendingComments.setLength(0)
                    stmtStartLine = -1
                    pendingStartLine = -1
                    resetRoutineState()
                }
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
            lastAppendedLine = lineNumber

            val lineEndsWithSemicolon = line.trimEnd().endsWith(";")

            if (!insideRoutine) {
                if (routineStartRegex.containsMatchIn(upperTrimmed)) {
                    insideRoutine = true
                    routineDepth = 0
                    routineIsPackage = packageStartRegex.containsMatchIn(upperTrimmed)
                } else if (anonBlockStartRegex.containsMatchIn(upperTrimmed) && !lineEndsWithSemicolon) {
                    // Anonymous block (DECLARE.../BEGIN...). A `BEGIN;` is transaction control, not a
                    // block, and is excluded by the !endsWithSemicolon guard.
                    insideRoutine = true
                    routineDepth = 0
                    routineIsPackage = false
                }
            }

            if (insideRoutine) {
                if (beginRegex.containsMatchIn(upperTrimmed)) {
                    routineDepth += 1
                }
                if (blockEndRegex.containsMatchIn(upperTrimmed)) {
                    routineDepth = (routineDepth - 1).coerceAtLeast(0)
                }
            }

            // Check for statement terminator.
            val shouldTerminate = when {
                !insideRoutine -> lineEndsWithSemicolon
                // Packages/types terminate only on `/` (handled above) or EOF: their inner ENDs
                // legitimately return depth to 0 while still inside the package.
                routineIsPackage -> false
                else -> lineEndsWithSemicolon && routineDepth == 0 && blockEndRegex.containsMatchIn(upperTrimmed)
            }

            if (shouldTerminate) {
                statements.add(SqlStatement(currentStatement.toString().trim(), stmtStartLine, lineNumber))
                currentStatement.setLength(0)
                pendingComments.setLength(0)
                stmtStartLine = -1
                pendingStartLine = -1
                resetRoutineState()
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
        
        // PL/SQL CREATE with optional OR REPLACE / [NON]EDITIONABLE, incl. PACKAGE/TYPE [BODY].
        // Handled first so `CREATE OR REPLACE PACKAGE BODY pkg` labels as "CREATE PACKAGE BODY pkg"
        // rather than falling through to the first-word fallback. Oracle identifiers may contain
        // `$`/`#` and may be quoted.
        val plsqlCreate = Regex(
            """^CREATE\s+(?:OR\s+REPLACE\s+)?(?:(?:NON)?EDITIONABLE\s+)?(PACKAGE\s+BODY|PACKAGE|TYPE\s+BODY|TYPE|PROCEDURE|FUNCTION|TRIGGER)\s+(?:IF\s+NOT\s+EXISTS\s+)?("?[\w$#.]+"?)""",
            RegexOption.IGNORE_CASE
        )
        plsqlCreate.find(cleanStatement)?.let { m ->
            val kind = m.groupValues[1].replace(Regex("\\s+"), " ").uppercase(Locale.US)
            val name = m.groupValues[2].trim('"')
            return "CREATE $kind $name"
        }

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
