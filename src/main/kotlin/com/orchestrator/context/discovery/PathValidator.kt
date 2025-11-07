package com.orchestrator.context.discovery

import com.orchestrator.context.config.IndexingConfig
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.max

/** Centralizes all path-level validation and security checks for context indexing. */
class PathValidator(
    watchPaths: List<Path>,
    private val pathFilter: PathFilter,
    private val extensionFilter: ExtensionFilter,
    private val includePathsFilter: IncludePathsFilter,
    private val symlinkHandler: SymlinkHandler,
    private val indexingConfig: IndexingConfig
) {

    private val normalizedWatchRoots: List<Path> = watchPaths.map { it.toAbsolutePath().normalize() }
    private val maxFileSizeBytes: Long = max(0, indexingConfig.maxFileSizeMb) * ONE_MB
    private val skipFilter: SkipFilter = SkipFilter.fromPatterns(indexingConfig.skipPatterns)

    enum class Reason {
        PATH_TRAVERSAL,
        OUTSIDE_WATCH_PATH,
        NOT_IN_INCLUDE_PATHS,
        IGNORED_BY_PATTERN,
        EXTENSION_NOT_ALLOWED,
        SKIPPED_BY_PATTERN,
        BINARY_FILE,
        SYMLINK_NOT_ALLOWED,
        SYMLINK_ESCAPE,
        SYMLINK_LOOP_OR_BROKEN,
        SIZE_LIMIT_EXCEEDED,
        IO_ERROR
    }

    data class ValidationResult(val valid: Boolean, val code: Reason? = null, val message: String? = null) {
        companion object {
            val Valid = ValidationResult(true)
        }
        fun isValid(): Boolean = valid
    }

    fun validate(path: Path): ValidationResult {
        if (containsPathTraversal(path)) {
            return invalid(Reason.PATH_TRAVERSAL, "Path contains traversal segments: ${path}")
        }

        val absolute = path.toAbsolutePath().normalize()
        val extension = absolute.fileName?.toString()
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.US)
            ?: ""

        // SIMPLIFIED: Only check extension for files, always allow directories
        if (Files.isDirectory(absolute)) {
            return ValidationResult.Valid
        }

        if (!isAllowedExtension(absolute)) {
            return invalid(Reason.EXTENSION_NOT_ALLOWED, "File extension is not allowed: $absolute")
        }

        // Check skip patterns AFTER extension matching
        if (isSkippedByPattern(absolute)) {
            return invalid(Reason.SKIPPED_BY_PATTERN, "File matches skip pattern: $absolute")
        }

        return ValidationResult.Valid
    }

    fun isUnderWatchPaths(path: Path, watchPaths: List<Path>): Boolean {
        if (watchPaths.isEmpty()) return true
        return watchPaths.any { root ->
            val normalizedRoot = root.toAbsolutePath().normalize()
            path == normalizedRoot || path.startsWith(normalizedRoot)
        }
    }

    fun containsPathTraversal(path: Path): Boolean {
        for (segment in path) {
            if (segment.toString() == "..") return true
        }
        return false
    }

    fun isInIncludePaths(path: Path): Boolean = includePathsFilter.shouldInclude(path)

    fun isInIgnorePatterns(path: Path): Boolean = pathFilter.shouldIgnore(path)

    fun isAllowedExtension(path: Path): Boolean {
        if (!Files.exists(path)) return true
        if (!Files.isRegularFile(path)) return true
        return extensionFilter.shouldInclude(path)
    }

    fun isSkippedByPattern(path: Path): Boolean = skipFilter.shouldSkip(path)

    fun isBinary(path: Path): Boolean = BinaryDetector.isBinary(path)

    fun isSymlinkEscape(path: Path): Boolean {
        val target = symlinkHandler.resolveTarget(path) ?: return true
        return symlinkHandler.isEscape(target)
    }

    fun isWithinSizeLimit(path: Path): Boolean = checkSizeLimit(path).valid

    private fun checkSizeLimit(path: Path): ValidationResult {
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return ValidationResult.Valid
        }
        if (maxFileSizeBytes <= 0) return ValidationResult.Valid
        if (isSizeException(path)) return ValidationResult.Valid
        val size = try {
            Files.size(path)
        } catch (ex: IOException) {
            return invalid(Reason.IO_ERROR, "Unable to determine file size for $path: ${ex.message}")
        }
        if (size > maxFileSizeBytes) {
            val humanSize = humanReadable(size)
            val humanLimit = humanReadable(maxFileSizeBytes)
            return invalid(
                Reason.SIZE_LIMIT_EXCEEDED,
                "File size $humanSize exceeds limit $humanLimit for $path"
            )
        }
        return ValidationResult.Valid
    }

    private fun evaluateSymlink(link: Path): ValidationResult? {
        if (!indexingConfig.followSymlinks) {
            return invalid(Reason.SYMLINK_NOT_ALLOWED, "Symlink traversal disabled by configuration: $link")
        }

        val resolved = symlinkHandler.resolveTarget(link)
            ?: return invalid(Reason.SYMLINK_LOOP_OR_BROKEN, "Symlink cannot be resolved within ${indexingConfig.maxSymlinkDepth} hops: $link")

        if (symlinkHandler.isEscape(resolved)) {
            return invalid(Reason.SYMLINK_ESCAPE, "Symlink escapes watch roots: $link -> $resolved")
        }

        if (!symlinkHandler.shouldFollow(link)) {
            return invalid(Reason.SYMLINK_LOOP_OR_BROKEN, "Symlink loop detected for $link")
        }

        if (!Files.exists(resolved)) {
            return invalid(Reason.SYMLINK_LOOP_OR_BROKEN, "Symlink target does not exist: $resolved")
        }

        return null
    }

    private fun isSizeException(path: Path): Boolean {
        if (indexingConfig.sizeExceptions.isEmpty()) return false
        val absolute = path.toAbsolutePath().normalize().toString().lowercase(Locale.US)
        val fileName = path.fileName?.toString()?.lowercase(Locale.US)
        return indexingConfig.sizeExceptions.any { exception ->
            val normalized = exception.trim().lowercase(Locale.US)
            when {
                normalized.isEmpty() -> false
                fileName != null && fileName == normalized -> true
                absolute.endsWith(normalized) -> true
                normalized.startsWith('.') && fileName != null && fileName.endsWith(normalized) -> true
                else -> false
            }
        }
    }

    private fun invalid(code: Reason, message: String): ValidationResult = ValidationResult(false, code, message)

    private fun humanReadable(bytes: Long): String {
        if (bytes < ONE_MB) return String.format(Locale.US, "%.2f KB", bytes / 1024.0)
        val mb = bytes / ONE_MB.toDouble()
        return String.format(Locale.US, "%.2f MB", mb)
    }

    companion object {
        private const val ONE_MB: Long = 1024L * 1024L
        private val BINARY_ALLOWED_EXTENSIONS = setOf("doc", "docx", "pdf")
    }
}
