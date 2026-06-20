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

    /** A labelled, line-located piece of a statement (used to break a package into its members). */
    private data class LabeledPiece(val label: String, val text: String, val startLine: Int, val endLine: Int)

    // A package/type member sub-program declaration, optionally prefixed by type-method modifiers.
    private val memberStartRegex = Regex(
        """^\s*(?:(?:MEMBER|STATIC|MAP|ORDER|FINAL|OVERRIDING|CONSTRUCTOR)\s+)*(PROCEDURE|FUNCTION)\s+("?[\w${'$'}#]+"?)""",
        RegexOption.IGNORE_CASE
    )
    // `IS`/`AS` opens a sub-program body — its presence (before a `;`) marks a definition vs a
    // forward declaration. Bounds how far the signature scan looks ahead.
    private val isAsKeywordRegex = Regex("""\b(?:IS|AS)\b""", RegexOption.IGNORE_CASE)
    private val MAX_SIGNATURE_LINES = 60

    override fun chunk(content: String, filePath: String): List<Chunk> {
        if (content.isBlank()) return emptyList()

        val chunks = mutableListOf<Chunk>()
        val statements = splitStatements(content)
        var ordinal = 0

        statements.forEach { statement ->
            if (statement.text.isBlank()) return@forEach
            // A PACKAGE/TYPE [BODY] arrives as a single statement; break it into member sub-programs
            // so a large package isn't one giant chunk (e.g. a 700KB package body → one embedding).
            val pieces = if (packageStartRegex.containsMatchIn(statement.text.trimStart())) {
                splitPackageMembers(statement)
            } else {
                listOf(LabeledPiece(extractLabel(statement.text.trim()), statement.text, statement.startLine, statement.endLine))
            }
            pieces.forEach { piece ->
                if (piece.text.isNotBlank()) {
                    chunks.add(createChunk(piece.text, piece.label, ordinal++, piece.startLine, piece.endLine))
                }
            }
        }

        return OverlapProcessor.addOverlap(chunks, overlapPercent, ::estimateTokens)
    }

    /**
     * Break a PACKAGE / PACKAGE BODY (or TYPE BODY) into a header chunk plus one chunk per member.
     *
     * Crucially distinguishes a sub-program *definition* (`PROCEDURE name(...) IS ... BEGIN ... END
     * name;` — has a body) from a *forward declaration* (`PROCEDURE name(...);` — ends at `;`, no
     * body). In a package body Oracle lists forward declarations first, then the bodies; treating a
     * forward declaration as its own member produced tiny mislabelled chunks and made the symbol for
     * a procedure point at its forward declaration instead of its real body.
     *
     * Package body: one chunk per *definition* (start → just before the next definition); forward
     * declarations and package-level globals fold into the header. Package spec (no definitions):
     * one chunk per declaration so the public API stays individually searchable.
     */
    private fun splitPackageMembers(statement: SqlStatement): List<LabeledPiece> {
        val lines = statement.text.split("\n")
        val packageLabel = extractLabel(statement.text.trim())
        val base = statement.startLine

        // Top-level (BEGIN/END depth 0) sub-program *definitions* — forward declarations excluded.
        val defStarts = mutableListOf<Pair<Int, String>>()
        var depth = 0
        for ((i, line) in lines.withIndex()) {
            val upper = line.uppercase(Locale.US)
            if (depth == 0) {
                val m = memberStartRegex.find(line)
                if (m != null && isDefinitionAt(lines, i)) {
                    val kind = m.groupValues[1].uppercase(Locale.US)
                    val name = m.groupValues[2].trim('"')
                    defStarts += i to "$kind $name"
                }
            }
            if (beginRegex.containsMatchIn(upper)) depth += 1
            if (blockEndRegex.containsMatchIn(upper)) depth = (depth - 1).coerceAtLeast(0)
        }

        if (defStarts.isEmpty()) {
            // No bodies → package spec (or a thin package): split each declaration into its own chunk.
            return splitDeclarations(lines, base, packageLabel, statement)
        }

        val pieces = mutableListOf<LabeledPiece>()

        // Header: package declaration, globals and forward declarations, up to the first definition.
        val firstDef = defStarts.first().first
        if (firstDef > 0) {
            val headerText = lines.subList(0, firstDef).joinToString("\n").trimEnd()
            if (headerText.isNotBlank()) {
                pieces += LabeledPiece(packageLabel, headerText, base, base + firstDef - 1)
            }
        }

        // Each definition is one chunk: its start line to just before the next definition.
        for ((k, def) in defStarts.withIndex()) {
            val startIdx = def.first
            val endIdx = if (k + 1 < defStarts.size) defStarts[k + 1].first - 1 else lines.lastIndex
            val text = lines.subList(startIdx, endIdx + 1).joinToString("\n").trimEnd()
            if (text.isNotBlank()) {
                pieces += LabeledPiece(def.second, text, base + startIdx, base + endIdx)
            }
        }

        return pieces
    }

    /** Split a package spec into one chunk per declared member (no bodies present). */
    private fun splitDeclarations(
        lines: List<String>,
        base: Int,
        packageLabel: String,
        statement: SqlStatement
    ): List<LabeledPiece> {
        val starts = mutableListOf<Pair<Int, String>>()
        var depth = 0
        for ((i, line) in lines.withIndex()) {
            val upper = line.uppercase(Locale.US)
            if (depth == 0) {
                memberStartRegex.find(line)?.let { m ->
                    starts += i to "${m.groupValues[1].uppercase(Locale.US)} ${m.groupValues[2].trim('"')}"
                }
            }
            if (beginRegex.containsMatchIn(upper)) depth += 1
            if (blockEndRegex.containsMatchIn(upper)) depth = (depth - 1).coerceAtLeast(0)
        }
        if (starts.isEmpty()) {
            return listOf(LabeledPiece(packageLabel, statement.text, statement.startLine, statement.endLine))
        }
        val pieces = mutableListOf<LabeledPiece>()
        val firstStart = starts.first().first
        if (firstStart > 0) {
            val headerText = lines.subList(0, firstStart).joinToString("\n").trimEnd()
            if (headerText.isNotBlank()) pieces += LabeledPiece(packageLabel, headerText, base, base + firstStart - 1)
        }
        for ((k, start) in starts.withIndex()) {
            val startIdx = start.first
            val endIdx = if (k + 1 < starts.size) starts[k + 1].first - 1 else lines.lastIndex
            val text = lines.subList(startIdx, endIdx + 1).joinToString("\n").trimEnd()
            if (text.isNotBlank()) pieces += LabeledPiece(start.second, text, base + startIdx, base + endIdx)
        }
        return pieces
    }

    /**
     * True if the sub-program at [startIdx] is a *definition* (has an `IS`/`AS` body) rather than a
     * *forward declaration* (the signature ends at `;` before any `IS`/`AS`). Scans the signature
     * forward a bounded number of lines: an `IS`/`AS` keyword ⇒ definition; a `;` first ⇒ declaration.
     */
    private fun isDefinitionAt(lines: List<String>, startIdx: Int): Boolean {
        val limit = minOf(startIdx + MAX_SIGNATURE_LINES, lines.size)
        for (j in startIdx until limit) {
            val code = lines[j].substringBefore("--")
            if (isAsKeywordRegex.containsMatchIn(code)) return true
            if (code.trimEnd().endsWith(";")) return false
        }
        return true // default to definition: keep the body as its own member rather than lose it
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
