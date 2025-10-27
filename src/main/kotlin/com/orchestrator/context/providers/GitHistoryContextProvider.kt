package com.orchestrator.context.providers

import com.orchestrator.context.domain.*
import com.orchestrator.context.storage.ContextDatabase
import com.orchestrator.utils.Logger
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.pathString
import kotlin.math.max

/**
 * Context provider that retrieves temporal context from git history.
 *
 * Provides:
 * - Recent commits affecting queried files
 * - Co-changed files (files frequently modified together)
 * - File authorship and change patterns
 *
 * Useful for understanding:
 * - Recent changes to code
 * - Related files that should be reviewed together
 * - Historical context for debugging
 */
class GitHistoryContextProvider(
    private val gitAnalyzer: GitHistoryAnalyzer = GitHistoryAnalyzer(),
    private val maxResults: Int = 20,
    private val commitLimit: Int = 10,
    private val coChangeLimit: Int = 5
) : ContextProvider {

    private val log = Logger.logger(this::class.qualifiedName!!)

    override val id: String = "git_history"
    override val type: ContextProviderType = ContextProviderType.GIT_HISTORY

    override suspend fun getContext(
        query: String,
        scope: ContextScope,
        budget: TokenBudget
    ): List<ContextSnippet> {
        // Extract file paths from query
        val paths = extractPaths(query, scope)
        if (paths.isEmpty()) {
            log.debug("No file paths found in query: $query")
            return emptyList()
        }

        val candidates = mutableListOf<GitContextCandidate>()

        // For each path, get git history and co-changed files
        for (path in paths) {
            // Get recent commits for this file
            val commits = gitAnalyzer.getRecentCommits(path, limit = commitLimit)
            if (commits.isNotEmpty()) {
                // Add commit context
                commits.forEachIndexed { index, commit ->
                    val relevance = calculateCommitRelevance(index, commits.size)
                    candidates += createCommitCandidate(path, commit, relevance)
                }

                // Get co-changed files
                val coChangedFiles = gitAnalyzer.findCoChangedFiles(
                    path = path,
                    limit = commitLimit * 2,
                    minCoOccurrence = 2
                ).take(coChangeLimit)

                // Add co-changed file context
                coChangedFiles.forEach { coChangedPath ->
                    val recentCommit = gitAnalyzer.getRecentCommits(coChangedPath, limit = 1).firstOrNull()
                    if (recentCommit != null) {
                        candidates += createCoChangedCandidate(path, coChangedPath, recentCommit)
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            log.debug("No git history found for paths: $paths")
            return emptyList()
        }

        // Get chunks from database for the candidate paths
        val snippets = mutableListOf<ContextSnippet>()
        var tokensUsed = 0
        val tokenBudget = budget.availableForSnippets.coerceAtLeast(0)

        // Sort by relevance
        val ordered = candidates
            .sortedByDescending { it.score }
            .take(maxResults)

        for (candidate in ordered) {
            val chunk = findChunkForPath(candidate.filePath, scope)
            if (chunk != null) {
                val tokensNeeded = chunk.tokenCount ?: (chunk.content.length / 4)
                if (tokenBudget > 0 && tokensUsed + tokensNeeded > tokenBudget) continue

                tokensUsed += tokensNeeded
                snippets += createSnippet(chunk, candidate)
            }
        }

        log.debug("Returned ${snippets.size} git history snippets (${tokensUsed} tokens)")
        return snippets
    }

    /**
     * Extract file paths from query and scope.
     * Looks for:
     * - Explicit paths in query (e.g., "src/main/File.kt")
     * - Scope paths
     * - File names that can be resolved
     */
    private fun extractPaths(query: String, scope: ContextScope): List<Path> {
        val paths = mutableSetOf<Path>()

        // Add scope paths
        scope.paths.forEach { pathStr ->
            runCatching { paths.add(Paths.get(pathStr)) }
                .onFailure { log.debug("Invalid scope path: $pathStr") }
        }

        // Extract path-like patterns from query
        // Matches patterns like: src/main/File.kt, ./path/to/file.js, path/file.py
        val pathRegex = Regex("""(?:\.{0,2}/)?(?:[a-zA-Z0-9_-]+/)*[a-zA-Z0-9_-]+\.[a-zA-Z]{1,5}""")
        pathRegex.findAll(query).forEach { match ->
            runCatching {
                val path = Paths.get(match.value)
                paths.add(path)
                log.debug("Extracted path from query: ${match.value}")
            }.onFailure { log.debug("Invalid path in query: ${match.value}") }
        }

        // Extract simple file names (e.g., "File.kt")
        val fileNameRegex = Regex("""\b([A-Z][a-zA-Z0-9_]*\.[a-z]{1,5})\b""")
        fileNameRegex.findAll(query).forEach { match ->
            val fileName = match.groupValues[1]
            // Try to find this file in the database
            val foundPath = findPathByFileName(fileName, scope)
            if (foundPath != null) {
                paths.add(foundPath)
                log.debug("Resolved filename to path: $fileName -> $foundPath")
            }
        }

        return paths.toList()
    }

    /**
     * Find a path in the database by filename and return absolute path if possible.
     */
    private fun findPathByFileName(fileName: String, scope: ContextScope): Path? {
        val sql = buildString {
            append("SELECT rel_path FROM file_state WHERE rel_path LIKE ? AND is_deleted = FALSE")
            if (scope.languages.isNotEmpty()) {
                val placeholders = scope.languages.joinToString(",") { "?" }
                append(" AND language IN ($placeholders)")
            }
            append(" LIMIT 1")
        }

        val relPath = ContextDatabase.withConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, "%$fileName")
                var paramIndex = 2
                scope.languages.forEach { ps.setString(paramIndex++, it) }

                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        rs.getString("rel_path")
                    } else {
                        null
                    }
                }
            }
        } ?: return null

        // Try to find the git repository root by looking for .git directory
        // Start from current directory and work up
        var current = Paths.get("").toAbsolutePath()
        while (current != null) {
            val gitDir = current.resolve(".git")
            if (java.nio.file.Files.exists(gitDir)) {
                // Found git root, resolve relative path from here
                return current.resolve(relPath)
            }
            current = current.parent
        }

        // If no git root found, return the relative path as-is
        return Paths.get(relPath)
    }

    /**
     * Find chunk for a given path in the database.
     */
    private fun findChunkForPath(path: Path, scope: ContextScope): ChunkInfo? {
        // Try both the full path and just the filename
        val pathVariants = listOf(
            path.pathString,
            path.fileName?.pathString ?: path.pathString
        ).distinct()

        val sql = buildString {
            append("""
                SELECT c.chunk_id, c.content, c.token_count, c.kind, c.start_line, c.end_line,
                       f.rel_path, f.language
                FROM chunks c
                JOIN file_state f ON f.file_id = c.file_id
                WHERE (f.rel_path = ? OR f.rel_path = ?) AND f.is_deleted = FALSE
            """.trimIndent())
            if (scope.languages.isNotEmpty()) {
                val placeholders = scope.languages.joinToString(",") { "?" }
                append(" AND f.language IN ($placeholders)")
            }
            append(" ORDER BY c.ordinal LIMIT 1")
        }

        return ContextDatabase.withConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, pathVariants[0])
                ps.setString(2, pathVariants.getOrElse(1) { pathVariants[0] })
                var paramIndex = 3
                scope.languages.forEach { ps.setString(paramIndex++, it) }

                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        ChunkInfo(
                            chunkId = rs.getLong("chunk_id"),
                            content = rs.getString("content"),
                            tokenCount = rs.getInt("token_count").takeIf { !rs.wasNull() },
                            kind = rs.getString("kind")?.let {
                                runCatching { ChunkKind.valueOf(it) }.getOrNull()
                            } ?: ChunkKind.CODE_BLOCK,
                            startLine = rs.getInt("start_line"),
                            endLine = rs.getInt("end_line"),
                            relPath = rs.getString("rel_path"),
                            language = rs.getString("language")
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }

    /**
     * Calculate relevance score for a commit based on its position in the history.
     * More recent commits are more relevant.
     */
    private fun calculateCommitRelevance(index: Int, total: Int): Double {
        if (total == 0) return 0.0
        // Most recent commit = 1.0, oldest = 0.5
        return 1.0 - (index.toDouble() / (total * 2.0))
    }

    /**
     * Create a candidate for a commit.
     */
    private fun createCommitCandidate(
        path: Path,
        commit: CommitInfo,
        relevance: Double
    ): GitContextCandidate {
        return GitContextCandidate(
            filePath = path,
            score = relevance,
            metadata = mapOf(
                "type" to "commit",
                "commit_hash" to commit.hash,
                "commit_short_hash" to commit.shortHash,
                "author" to commit.author.name,
                "author_email" to commit.author.email,
                "message" to commit.shortMessage,
                "timestamp" to commit.timestamp.toString(),
                "files_changed_count" to commit.filesChanged.size.toString()
            )
        )
    }

    /**
     * Create a candidate for a co-changed file.
     */
    private fun createCoChangedCandidate(
        sourcePath: Path,
        coChangedPath: Path,
        commit: CommitInfo
    ): GitContextCandidate {
        return GitContextCandidate(
            filePath = coChangedPath,
            score = 0.7, // Co-changed files have slightly lower relevance
            metadata = mapOf(
                "type" to "co-changed",
                "source_file" to sourcePath.pathString,
                "co_changed_with" to coChangedPath.pathString,
                "last_commit_hash" to commit.shortHash,
                "last_author" to commit.author.name,
                "last_change" to commit.timestamp.toString()
            )
        )
    }

    /**
     * Create a ContextSnippet from chunk and candidate data.
     */
    private fun createSnippet(chunk: ChunkInfo, candidate: GitContextCandidate): ContextSnippet {
        val label = when (candidate.metadata["type"]) {
            "commit" -> {
                val shortHash = candidate.metadata["commit_short_hash"] ?: ""
                val message = candidate.metadata["message"] ?: ""
                "$shortHash: $message"
            }
            "co-changed" -> {
                val sourceFile = candidate.metadata["source_file"] ?: ""
                "Co-changed with $sourceFile"
            }
            else -> chunk.relPath
        }

        val metadata = mutableMapOf(
            "provider" to id,
            "sources" to id,
            "chunk_id" to chunk.chunkId.toString(),
            "score" to "%.3f".format(candidate.score)
        )
        metadata.putAll(candidate.metadata)

        return ContextSnippet(
            chunkId = chunk.chunkId,
            score = candidate.score.coerceIn(0.0, 1.0),
            filePath = chunk.relPath,
            label = label,
            kind = chunk.kind,
            text = chunk.content,
            language = chunk.language,
            offsets = chunk.startLine..chunk.endLine,
            metadata = metadata
        )
    }

    private data class GitContextCandidate(
        val filePath: Path,
        val score: Double,
        val metadata: Map<String, String>
    )

    private data class ChunkInfo(
        val chunkId: Long,
        val content: String,
        val tokenCount: Int?,
        val kind: ChunkKind,
        val startLine: Int,
        val endLine: Int,
        val relPath: String,
        val language: String?
    )
}
