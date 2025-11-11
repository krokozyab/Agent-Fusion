package com.orchestrator.context.bootstrap

import com.orchestrator.context.config.BootstrapConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** Assigns stable priority rankings to candidate files for bootstrap indexing. */
object FilePrioritizer {

    fun prioritize(files: List<Path>, config: BootstrapConfig): List<Path> {
        if (files.isEmpty()) return emptyList()
        val priorities = files.mapIndexed { index, path ->
            Triple(index, path, priorityFor(path, config))
        }
        return priorities
            .sortedWith(compareBy({ it.third.first }, { it.third.second }, { it.first }))
            .map { it.second }
    }

    private fun priorityFor(path: Path, config: BootstrapConfig): Pair<Int, Long> {
        val size = safeSize(path)
        val sizeOrder = sizePriority(size)
        if (sizeOrder.first == PRIORITY_OVERSIZED || sizeOrder.first == PRIORITY_SMALL) {
            return sizeOrder
        }
        val name = path.fileName?.toString() ?: ""
        val extension = extractExtension(name)

        val extensionPriority = priorityByExtension(extension, name, config)
        if (extensionPriority != null) {
            return extensionPriority to (size ?: Long.MAX_VALUE)
        }

        return sizeOrder
    }

    private fun priorityByExtension(extension: String?, name: String, config: BootstrapConfig): Int? {
        val normalizedExt = extension?.lowercase(Locale.US)

        if (normalizedExt != null && matchesPriorityExtension(normalizedExt, config)) {
            return PRIORITY_SMALL
        }

        val specialName = name.lowercase(Locale.US)
        val directMatchPriority = SPECIAL_NAMES[specialName]
        if (directMatchPriority != null) return directMatchPriority

        return when (normalizedExt) {
            null -> null
            in SOURCE_EXTENSIONS -> 2
            in DOC_EXTENSIONS -> 3
            in CONFIG_EXTENSIONS -> 4
            in DATA_EXTENSIONS -> 5
            in WEB_EXTENSIONS -> 6
            in SCRIPT_EXTENSIONS -> 7
            in NOTEBOOK_EXTENSIONS -> 8
            else -> null
        }
    }

    private fun sizePriority(size: Long?): Pair<Int, Long> {
        val limitAwareSize = size ?: Long.MAX_VALUE
        return when {
            size != null && size > 2 * MB -> PRIORITY_OVERSIZED to limitAwareSize
            size != null && size < 10 * KB -> PRIORITY_SMALL to limitAwareSize
            size != null && size <= 256 * KB -> 9 to limitAwareSize
            size != null -> 10 to limitAwareSize
            else -> DEFAULT_PRIORITY to limitAwareSize
        }
    }

    private fun extractExtension(name: String): String? {
        val idx = name.lastIndexOf('.')
        if (idx == -1 || idx == name.length - 1) return null
        return name.substring(idx).lowercase(Locale.US)
    }

    private fun safeSize(path: Path): Long? = try {
        Files.size(path)
    } catch (_: Exception) {
        null
    }

    private const val KB = 1024L
    private const val MB = 1024L * 1024L
    private const val PRIORITY_SMALL = 1
    private const val DEFAULT_PRIORITY = 11

    private val SOURCE_EXTENSIONS = setOf(
        ".kt", ".kts", ".py", ".ts", ".js", ".jsx", ".tsx", ".java", ".go", ".rs",
        ".c", ".cpp", ".cc", ".h", ".hpp", ".cs", ".swift", ".v", ".f90",
        ".rb", ".php", ".scala", ".pl", ".clj"
    )

    private val DOC_EXTENSIONS = setOf(
        ".md", ".rst", ".adoc", ".txt", ".docx", ".pdf", ".tex", ".org"
    )

    private val CONFIG_EXTENSIONS = setOf(
        ".yaml", ".toml", ".json", ".ini", ".env", ".cfg", ".conf", ".xml", ".properties"
    )

    private val DATA_EXTENSIONS = setOf(
        ".sql", ".ddl", ".avsc", ".proto", ".thrift", ".graphql", ".xsd", ".csv",
        ".parquet", ".orc", ".arrow", ".tsv"
    )

    private val WEB_EXTENSIONS = setOf(
        ".html", ".htm", ".css", ".scss", ".sass", ".less", ".svg", ".xml", ".vue", ".svelte"
    )

    private val SCRIPT_EXTENSIONS = setOf(
        ".sh", ".bash", ".zsh", ".ps1", ".cmd", ".bat", ".make", ".mk", ".dockerfile",
        ".compose", ".yml", ".gradle", ".maven", ".nushell"
    )

    private val NOTEBOOK_EXTENSIONS = setOf(
        ".ipynb"
    )

    private val SPECIAL_NAMES = mapOf(
        "dockerfile" to 7,
        "makefile" to 7
    )

    private const val PRIORITY_OVERSIZED = 11

    private fun matchesPriorityExtension(extension: String, config: BootstrapConfig): Boolean {
        val normalizedTarget = canonicalizeExtension(extension)
        return config.priorityExtensions.any { canonicalizeExtension(it) == normalizedTarget }
    }

    private fun canonicalizeExtension(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return trimmed
        val normalized = trimmed.lowercase(Locale.US)
        return if (normalized.startsWith('.')) normalized else ".${normalized}"
    }
}
