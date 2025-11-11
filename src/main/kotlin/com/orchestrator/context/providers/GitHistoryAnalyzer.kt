package com.orchestrator.context.providers

import com.orchestrator.context.domain.AuthorInfo
import com.orchestrator.context.domain.BlameInfo
import com.orchestrator.context.domain.CommitInfo
import com.orchestrator.utils.Logger
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.blame.BlameResult
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory
import kotlin.io.path.pathString

/**
 * Analyzes git commit history to provide temporal context for code changes.
 *
 * Uses JGit library to:
 * - Retrieve recent commits for files
 * - Get blame information (line-by-line authorship)
 * - Find co-changed files (files frequently modified together)
 *
 * Results are cached to improve performance.
 */
class GitHistoryAnalyzer(
    private val cacheEnabled: Boolean = true,
    private val maxCacheSize: Int = 1000
) {
    private val log = Logger.logger(this::class.qualifiedName!!)

    // Caches
    private val recentCommitsCache = ConcurrentHashMap<String, CacheEntry<List<CommitInfo>>>()
    private val blameCache = ConcurrentHashMap<String, CacheEntry<Map<Int, BlameInfo>>>()
    private val coChangedFilesCache = ConcurrentHashMap<String, CacheEntry<List<Path>>>()

    /**
     * Finds the git repository for the given path.
     * Searches upward from the path until .git directory is found.
     */
    private fun findRepository(path: Path): Repository? {
        var currentPath = if (path.isDirectory()) path else path.parent
        while (currentPath != null) {
            val gitDir = currentPath.resolve(".git")
            if (Files.exists(gitDir)) {
                return try {
                    FileRepositoryBuilder()
                        .setGitDir(gitDir.toFile())
                        .readEnvironment()
                        .findGitDir()
                        .build()
                } catch (e: Exception) {
                    log.warn("Failed to open git repository at $gitDir: ${e.message}")
                    null
                }
            }
            currentPath = currentPath.parent
        }
        return null
    }

    /**
     * Get recent commits affecting the given file path.
     *
     * @param path File path (relative or absolute)
     * @param limit Maximum number of commits to retrieve
     * @return List of commits, most recent first
     */
    fun getRecentCommits(path: Path, limit: Int = 20): List<CommitInfo> {
        val cacheKey = "${path.toAbsolutePath().normalize()}:$limit"

        if (cacheEnabled) {
            val cached = recentCommitsCache[cacheKey]
            if (cached != null && !cached.isExpired()) {
                log.debug("Cache hit for recent commits: $path")
                return cached.value
            }
        }

        val repository = findRepository(path) ?: run {
            log.warn("No git repository found for path: $path")
            return emptyList()
        }

        return try {
            Git(repository).use { git ->
                val repoRoot = repository.workTree.toPath()
                val relativePath = if (path.isAbsolute) {
                    repoRoot.relativize(path).pathString
                } else {
                    path.pathString
                }

                val commits = git.log()
                    .apply { if (relativePath.isNotEmpty()) addPath(relativePath) }
                    .setMaxCount(limit)
                    .call()
                    .map { revCommit -> revCommit.toCommitInfo(repository) }
                    .toList()

                if (cacheEnabled) {
                    evictOldestIfNeeded(recentCommitsCache)
                    recentCommitsCache[cacheKey] = CacheEntry(commits)
                }

                commits
            }
        } catch (e: Exception) {
            log.error("Failed to get recent commits for $path: ${e.message}", e)
            emptyList()
        } finally {
            repository.close()
        }
    }

    /**
     * Get blame information for the given file.
     * Returns a map of line number to blame information.
     *
     * @param path File path (relative or absolute)
     * @return Map of line number (1-based) to BlameInfo
     */
    fun getBlame(path: Path): Map<Int, BlameInfo> {
        val cacheKey = path.toAbsolutePath().normalize().pathString

        if (cacheEnabled) {
            val cached = blameCache[cacheKey]
            if (cached != null && !cached.isExpired()) {
                log.debug("Cache hit for blame: $path")
                return cached.value
            }
        }

        val repository = findRepository(path) ?: run {
            log.warn("No git repository found for path: $path")
            return emptyMap()
        }

        return try {
            Git(repository).use { git ->
                val repoRoot = repository.workTree.toPath()
                val relativePath = if (path.isAbsolute) {
                    repoRoot.relativize(path).pathString
                } else {
                    path.pathString
                }

                val blameResult: BlameResult? = git.blame()
                    .setFilePath(relativePath)
                    .call()

                if (blameResult == null) {
                    log.warn("Blame result is null for $path")
                    return emptyMap()
                }

                val blameMap = mutableMapOf<Int, BlameInfo>()
                val totalLines = blameResult.resultContents.size()

                for (lineIndex in 0 until totalLines) {
                    val commit = blameResult.getSourceCommit(lineIndex)
                    if (commit != null) {
                        val lineContent = blameResult.resultContents.getString(lineIndex)
                        blameMap[lineIndex + 1] = BlameInfo(
                            line = lineIndex + 1,
                            content = lineContent,
                            commit = commit.toCommitInfo(repository)
                        )
                    }
                }

                if (cacheEnabled) {
                    evictOldestIfNeeded(blameCache)
                    blameCache[cacheKey] = CacheEntry(blameMap)
                }

                blameMap
            }
        } catch (e: GitAPIException) {
            log.error("Failed to get blame for $path: ${e.message}", e)
            emptyMap()
        } catch (e: Exception) {
            log.error("Unexpected error getting blame for $path: ${e.message}", e)
            emptyMap()
        } finally {
            repository.close()
        }
    }

    /**
     * Find files that are frequently changed together with the given file.
     * Useful for identifying related code that should be reviewed together.
     *
     * @param path File path (relative or absolute)
     * @param limit Number of commits to analyze (default: 50)
     * @param minCoOccurrence Minimum number of times files must be changed together (default: 2)
     * @return List of co-changed file paths, sorted by frequency
     */
    fun findCoChangedFiles(
        path: Path,
        limit: Int = 50,
        minCoOccurrence: Int = 2
    ): List<Path> {
        val cacheKey = "${path.toAbsolutePath().normalize()}:$limit:$minCoOccurrence"

        if (cacheEnabled) {
            val cached = coChangedFilesCache[cacheKey]
            if (cached != null && !cached.isExpired()) {
                log.debug("Cache hit for co-changed files: $path")
                return cached.value
            }
        }

        val repository = findRepository(path) ?: run {
            log.warn("No git repository found for path: $path")
            return emptyList()
        }

        return try {
            Git(repository).use { git ->
                val repoRoot = repository.workTree.toPath()
                val relativePath = if (path.isAbsolute) {
                    repoRoot.relativize(path).pathString
                } else {
                    path.pathString
                }

                // Get commits affecting the target file
                val commits = git.log()
                    .apply { if (relativePath.isNotEmpty()) addPath(relativePath) }
                    .setMaxCount(limit)
                    .call()
                    .toList()

                // Count co-occurrences of other files
                val coChangeCounts = mutableMapOf<String, Int>()

                for (commit in commits) {
                    val changedFiles = getFilesChangedInCommit(repository, commit)
                    for (file in changedFiles) {
                        if (file != relativePath) {
                            coChangeCounts[file] = coChangeCounts.getOrDefault(file, 0) + 1
                        }
                    }
                }

                // Filter by minimum co-occurrence and convert to paths
                val coChangedFiles = coChangeCounts
                    .filter { it.value >= minCoOccurrence }
                    .entries
                    .sortedByDescending { it.value }
                    .map { repoRoot.resolve(it.key) }
                    .toList()

                if (cacheEnabled) {
                    evictOldestIfNeeded(coChangedFilesCache)
                    coChangedFilesCache[cacheKey] = CacheEntry(coChangedFiles)
                }

                coChangedFiles
            }
        } catch (e: Exception) {
            log.error("Failed to find co-changed files for $path: ${e.message}", e)
            emptyList()
        } finally {
            repository.close()
        }
    }

    /**
     * Clear all caches.
     */
    fun clearCache() {
        recentCommitsCache.clear()
        blameCache.clear()
        coChangedFilesCache.clear()
        log.debug("Cleared all caches")
    }

    /**
     * Get cache statistics.
     */
    fun getCacheStats(): Map<String, Any> {
        return mapOf(
            "recentCommitsCacheSize" to recentCommitsCache.size,
            "blameCacheSize" to blameCache.size,
            "coChangedFilesCacheSize" to coChangedFilesCache.size,
            "cacheEnabled" to cacheEnabled,
            "maxCacheSize" to maxCacheSize
        )
    }

    // Helper methods

    private fun RevCommit.toCommitInfo(repository: Repository): CommitInfo {
        val filesChanged = try {
            getFilesChangedInCommit(repository, this)
        } catch (e: Exception) {
            emptyList()
        }

        return CommitInfo(
            hash = name,
            shortHash = name.substring(0, 7),
            author = authorIdent.toAuthorInfo(),
            committer = committerIdent.toAuthorInfo(),
            message = fullMessage.trim(),
            shortMessage = shortMessage.trim(),
            timestamp = Instant.ofEpochSecond(commitTime.toLong()),
            filesChanged = filesChanged,
            additions = 0, // Would require diff parsing
            deletions = 0  // Would require diff parsing
        )
    }

    private fun PersonIdent.toAuthorInfo(): AuthorInfo {
        return AuthorInfo(
            name = name,
            email = emailAddress
        )
    }

    private fun getFilesChangedInCommit(repository: Repository, commit: RevCommit): List<String> {
        return try {
            Git(repository).use { git ->
                val parent = if (commit.parentCount > 0) commit.getParent(0) else null
                val oldTree = getTreeIterator(repository, parent?.toObjectId())
                val newTree = getTreeIterator(repository, commit.toObjectId())

                git.diff()
                    .setOldTree(oldTree)
                    .setNewTree(newTree)
                    .call()
                    .mapNotNull { diff ->
                        when (diff.changeType) {
                            DiffEntry.ChangeType.DELETE -> diff.oldPath
                            DiffEntry.ChangeType.RENAME -> diff.newPath
                            else -> diff.newPath
                        }
                    }
            }
        } catch (e: Exception) {
            log.warn("Failed to get files changed in commit ${commit.name}: ${e.message}")
            emptyList()
        }
    }

    private fun getTreeIterator(repository: Repository, objectId: ObjectId?): AbstractTreeIterator {
        return if (objectId == null) {
            EmptyTreeIterator()
        } else {
            RevWalk(repository).use { revWalk ->
                val tree = revWalk.parseCommit(objectId).tree
                val treeParser = CanonicalTreeParser()
                repository.newObjectReader().use { reader ->
                    treeParser.reset(reader, tree.id)
                }
                treeParser
            }
        }
    }

    private fun <T> evictOldestIfNeeded(cache: ConcurrentHashMap<String, CacheEntry<T>>) {
        if (cache.size >= maxCacheSize) {
            // Simple FIFO eviction - remove 10% of entries
            val toRemove = cache.keys.take(maxCacheSize / 10)
            toRemove.forEach { cache.remove(it) }
            log.debug("Evicted ${toRemove.size} entries from cache")
        }
    }

    private data class CacheEntry<T>(
        val value: T,
        val timestamp: Long = System.currentTimeMillis(),
        val ttlMs: Long = 5 * 60 * 1000 // 5 minutes default TTL
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > ttlMs
    }
}
