package com.orchestrator.context.domain

import java.time.Instant

/**
 * Represents a git commit with essential metadata.
 */
data class CommitInfo(
    val hash: String,
    val shortHash: String,
    val author: AuthorInfo,
    val committer: AuthorInfo,
    val message: String,
    val shortMessage: String,
    val timestamp: Instant,
    val filesChanged: List<String> = emptyList(),
    val additions: Int = 0,
    val deletions: Int = 0
)

/**
 * Represents author/committer information.
 */
data class AuthorInfo(
    val name: String,
    val email: String
)

/**
 * Represents blame information for a line in a file.
 */
data class BlameInfo(
    val line: Int,
    val content: String,
    val commit: CommitInfo
)
