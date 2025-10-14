package com.orchestrator.context.indexing

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Collects metadata used to determine whether a file has changed since the last indexing pass.
 */
object FileMetadataExtractor {

    private val extensionLanguageMap = mapOf(
        "c" to "c",
        "cc" to "cpp",
        "cpp" to "cpp",
        "cxx" to "cpp",
        "h" to "c",
        "hh" to "cpp",
        "hpp" to "cpp",
        "hxx" to "cpp",
        "cs" to "csharp",
        "cfg" to "config",
        "conf" to "config",
        "css" to "css",
        "go" to "go",
        "groovy" to "groovy",
        "gradle" to "groovy",
        "html" to "html",
        "htm" to "html",
        "java" to "java",
        "js" to "javascript",
        "jsx" to "javascript",
        "json" to "json",
        "kt" to "kotlin",
        "kts" to "kotlin",
        "lua" to "lua",
        "md" to "markdown",
        "markdown" to "markdown",
        "properties" to "properties",
        "php" to "php",
        "pl" to "perl",
        "ps1" to "powershell",
        "py" to "python",
        "rb" to "ruby",
        "rs" to "rust",
        "scala" to "scala",
        "scss" to "scss",
        "sh" to "shell",
        "bash" to "shell",
        "sql" to "sql",
        "swift" to "swift",
        "ts" to "typescript",
        "tsx" to "typescript",
        "toml" to "toml",
        "txt" to "text",
        "yaml" to "yaml",
        "yml" to "yaml",
        "xml" to "xml"
    )

    /**
     * Extracts a normalized metadata snapshot for the given file.
     */
    fun extractMetadata(path: Path): FileMetadata {
        require(Files.exists(path)) { "Path does not exist: $path" }
        require(Files.isRegularFile(path)) { "Path must be a regular file: $path" }

        val sizeBytes = Files.size(path)
        val modifiedTimeNs = Files.getLastModifiedTime(path).to(TimeUnit.NANOSECONDS)
        val hashHex = FileHasher.hex(FileHasher.computeHash(path))
        val mimeType = detectMimeType(path)
        val language = detectLanguage(path, mimeType)

        return FileMetadata(
            sizeBytes = sizeBytes,
            modifiedTimeNs = modifiedTimeNs,
            contentHash = hashHex,
            language = language,
            mimeType = mimeType
        )
    }

    private fun detectMimeType(path: Path): String? = runCatching {
        Files.probeContentType(path)?.lowercase(Locale.US)
    }.getOrNull()

    private fun detectLanguage(path: Path, mimeType: String?): String? {
        val name = path.fileName?.toString() ?: return null
        val lowerName = name.lowercase(Locale.US)

        // Special case for well-known filenames that lack an extension.
        if (lowerName == "dockerfile") return "dockerfile"

        val dotIndex = lowerName.lastIndexOf('.')
        if (dotIndex != -1 && dotIndex < lowerName.lastIndex) {
            val extension = lowerName.substring(dotIndex + 1)
            extensionLanguageMap[extension]?.let { return it }
        }

        if (mimeType == null) {
            return null
        }

        if (mimeType.startsWith("text/")) {
            val subtype = mimeType.substringAfter("text/")
            return when (subtype) {
                "x-python", "python" -> "python"
                "x-java-source", "java" -> "java"
                "x-c", "c" -> "c"
                "x-c++", "x-cpp", "cpp" -> "cpp"
                "x-csharp" -> "csharp"
                "x-scala" -> "scala"
                "x-go", "go" -> "go"
                "x-kotlin", "kotlin" -> "kotlin"
                "x-rustsrc" -> "rust"
                "x-markdown", "markdown" -> "markdown"
                "x-yaml", "yaml" -> "yaml"
                "x-sh", "x-shellscript", "shell", "sh" -> "shell"
                "javascript", "x-javascript" -> "javascript"
                "html" -> "html"
                "css" -> "css"
                "plain" -> "text"
                else -> subtype
            }
        }

        return when (mimeType) {
            "application/json", "application/ld+json" -> "json"
            "application/xml" -> "xml"
            "application/x-yaml", "application/yaml" -> "yaml"
            "application/javascript", "application/x-javascript" -> "javascript"
            "application/x-shellscript", "application/x-sh" -> "shell"
            else -> null
        }
    }
}

data class FileMetadata(
    val sizeBytes: Long,
    val modifiedTimeNs: Long,
    val contentHash: String,
    val language: String?,
    val mimeType: String?
)
