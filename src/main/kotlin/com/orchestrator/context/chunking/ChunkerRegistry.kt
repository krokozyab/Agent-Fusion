package com.orchestrator.context.chunking

import com.orchestrator.context.config.ChunkingConfig
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.min

interface ChunkerRegistry {
    fun getChunker(filePath: Path): Chunker
    fun getChunker(extension: String): Chunker
    fun getSupportedExtensions(): Set<String>
    fun isSupported(filePath: Path): Boolean
    fun isSupported(extension: String): Boolean
}

class ConfigurableChunkerRegistry(
    private val config: ChunkingConfig = ChunkingConfig()
) : ChunkerRegistry {

    private val overlapPercent: Int = if (config.overlapEnabled) {
        min(50, max(0, config.overlapPercent))
    } else {
        0
    }

    private val registry: Map<String, Chunker> = buildRegistry()

    private fun buildRegistry(): Map<String, Chunker> = mapOf(
        "md" to MarkdownChunker(maxTokens = config.markdown.maxTokens, overlapPercent = overlapPercent),
        "markdown" to MarkdownChunker(maxTokens = config.markdown.maxTokens, overlapPercent = overlapPercent),
        // Code languages use AST chunking via tree-sitter (TreeSitterChunker), wrapped in the
        // ThreadLocal adapter because tree-sitter parsers are not thread-safe. Each falls back to its
        // former heuristic chunker when a parse yields nothing usable.
        "py" to CachingSimpleChunkerAdapter {
            TreeSitterChunker(LanguageSpecs.PYTHON, maxTokens = config.python.maxTokens, overlapPercent = overlapPercent)
        },
        "go" to CachingSimpleChunkerAdapter {
            TreeSitterChunker(LanguageSpecs.GO, maxTokens = config.go.maxTokens, overlapPercent = overlapPercent)
        },
        "ts" to CachingSimpleChunkerAdapter {
            TreeSitterChunker(LanguageSpecs.TYPESCRIPT, maxTokens = config.typescript.maxTokens, overlapPercent = overlapPercent)
        },
        "tsx" to CachingSimpleChunkerAdapter {
            TreeSitterChunker(LanguageSpecs.TYPESCRIPT, maxTokens = config.typescript.maxTokens, overlapPercent = overlapPercent)
        },
        "js" to CachingSimpleChunkerAdapter {
            TreeSitterChunker(LanguageSpecs.JAVASCRIPT, maxTokens = config.typescript.maxTokens, overlapPercent = overlapPercent)
        },
        "jsx" to CachingSimpleChunkerAdapter {
            TreeSitterChunker(LanguageSpecs.JAVASCRIPT, maxTokens = config.typescript.maxTokens, overlapPercent = overlapPercent)
        },
        "java" to CachingSimpleChunkerAdapter {
            TreeSitterChunker(LanguageSpecs.JAVA, overlapPercent = overlapPercent)
        },
        "cs" to CachingSimpleChunkerAdapter {
            TreeSitterChunker(LanguageSpecs.CSHARP, overlapPercent = overlapPercent)
        },
        "kt" to CachingSimpleChunkerAdapter {
            TreeSitterChunker(LanguageSpecs.KOTLIN, overlapPercent = overlapPercent)
        },
        "kts" to CachingSimpleChunkerAdapter {
            TreeSitterChunker(LanguageSpecs.KOTLIN, overlapPercent = overlapPercent)
        },
        "yaml" to CachingSimpleChunkerAdapter { YamlChunker(overlapPercent = overlapPercent) },
        "yml" to CachingSimpleChunkerAdapter { YamlChunker(overlapPercent = overlapPercent) },
        "doc" to WordChunker(overlapPercent = overlapPercent),
        "docx" to WordChunker(overlapPercent = overlapPercent),
        "pdf" to PdfChunker(overlapPercent = overlapPercent),
        "json" to CachingSimpleChunkerAdapter { JsonChunker(overlapPercent = overlapPercent) },
        "sql" to CachingSimpleChunkerAdapter { SqlChunker(overlapPercent = overlapPercent) },
        // Oracle PL/SQL source file extensions (package spec/body, procedures, functions, triggers,
        // types) — route them to SqlChunker instead of the plain-text fallback.
        "pls" to CachingSimpleChunkerAdapter { SqlChunker(overlapPercent = overlapPercent) },
        "plsql" to CachingSimpleChunkerAdapter { SqlChunker(overlapPercent = overlapPercent) },
        "pks" to CachingSimpleChunkerAdapter { SqlChunker(overlapPercent = overlapPercent) },
        "pkb" to CachingSimpleChunkerAdapter { SqlChunker(overlapPercent = overlapPercent) },
        "prc" to CachingSimpleChunkerAdapter { SqlChunker(overlapPercent = overlapPercent) },
        "fnc" to CachingSimpleChunkerAdapter { SqlChunker(overlapPercent = overlapPercent) },
        "trg" to CachingSimpleChunkerAdapter { SqlChunker(overlapPercent = overlapPercent) },
        "tps" to CachingSimpleChunkerAdapter { SqlChunker(overlapPercent = overlapPercent) },
        "tpb" to CachingSimpleChunkerAdapter { SqlChunker(overlapPercent = overlapPercent) }
    )

    override fun getChunker(filePath: Path): Chunker {
        val extension = filePath.fileName.toString()
            .substringAfterLast('.', "")
            .lowercase()
        return registry[extension] ?: PlainTextChunker(overlapPercent = overlapPercent)
    }

    override fun getChunker(extension: String): Chunker {
        return registry[extension.lowercase().removePrefix(".")] ?: PlainTextChunker(overlapPercent = overlapPercent)
    }

    override fun getSupportedExtensions(): Set<String> {
        return registry.keys
    }

    override fun isSupported(filePath: Path): Boolean {
        val extension = filePath.fileName.toString()
            .substringAfterLast('.', "")
            .lowercase()
        return registry.containsKey(extension)
    }

    override fun isSupported(extension: String): Boolean {
        return registry.containsKey(extension.lowercase().removePrefix("."))
    }
}

private class CachingSimpleChunkerAdapter(
    private val factory: () -> SimpleChunker
) : Chunker {
    // Per-thread delegate. The underlying parsers (e.g. JavaParser, SnakeYAML Yaml) are NOT
    // thread-safe, and BatchIndexer invokes chunk() concurrently from many worker threads.
    // A single shared instance would race and silently produce corrupted or empty parses.
    // ThreadLocal gives each worker its own instance while still amortizing construction
    // across the files that worker processes.
    private val threadLocal = ThreadLocal.withInitial(factory)

    private fun delegate(): SimpleChunker = threadLocal.get()

    override val strategy: ChunkingStrategy by lazy {
        val name = delegate()::class.simpleName ?: "unknown"
        ChunkingStrategy(id = name, displayName = name)
    }

    override fun chunk(content: String, filePath: String, language: String) =
        delegate().chunk(content, filePath)

    override fun estimateTokens(text: String) = text.length / 4
}

interface SimpleChunker {
    fun chunk(content: String, filePath: String): List<com.orchestrator.context.domain.Chunk>
}
