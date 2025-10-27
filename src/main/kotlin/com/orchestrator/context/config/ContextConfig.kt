package com.orchestrator.context.config

/**
 * Configuration model covering the context subsystem. Defaults align with the
 * reference `context.toml` schema described in the architecture documentation.
 */
data class ContextConfig(
    val enabled: Boolean = true,
    val mode: DeploymentMode = DeploymentMode.EMBEDDED,
    val fallbackEnabled: Boolean = true,
    val engine: EngineConfig = EngineConfig(),
    val storage: StorageConfig = StorageConfig(),
    val watcher: WatcherConfig = WatcherConfig(),
    val indexing: IndexingConfig = IndexingConfig(),
    val embedding: EmbeddingConfig = EmbeddingConfig(),
    val chunking: ChunkingConfig = ChunkingConfig(),
    val query: QueryConfig = QueryConfig(),
    val budget: BudgetConfig = BudgetConfig(),
    val providers: Map<String, ProviderConfig> = ProviderConfig.defaults(),
    val metrics: MetricsConfig = MetricsConfig(),
    val bootstrap: BootstrapConfig = BootstrapConfig(),
    val security: SecurityConfig = SecurityConfig()
) {
    /** Returns only the providers that are enabled. */
    val enabledProviders: Map<String, ProviderConfig>
        get() = providers.filterValues { it.enabled }
}

enum class DeploymentMode { EMBEDDED, STANDALONE, HYBRID }

data class EngineConfig(
    val host: String = "localhost",
    val port: Int = 9090,
    val timeoutMs: Long = 10_000,
    val retryAttempts: Int = 3
)

data class StorageConfig(
    val dbPath: String = "./context.duckdb",
    val backupEnabled: Boolean = false,
    val backupIntervalHours: Int = 24
)

data class WatcherConfig(
    val enabled: Boolean = true,
    val debounceMs: Long = 500,
    val watchPaths: List<String> = listOf("auto"),
    val ignorePatterns: List<String> = listOf(
        ".git",
        "node_modules",
        "build",
        "dist",
        ".venv",
        "target"
    ),
    val maxFileSizeMb: Int = 5,
    val useGitignore: Boolean = true,
    val useContextignore: Boolean = true
)

enum class BinaryDetectionMode { EXTENSION, MIME, CONTENT, ALL }

data class IndexingConfig(
    val allowedExtensions: List<String> = listOf(
        ".kt",
        ".kts",
        ".java",
        ".py",
        ".ts",
        ".tsx",
        ".md",
        ".yaml",
        ".yml",
        ".json",
        ".sql",
        ".doc",
        ".docx",
        ".pdf"
    ),
    val blockedExtensions: List<String> = emptyList(),
    val maxFileSizeMb: Int = 5,
    val warnFileSizeMb: Int = 2,
    val sizeExceptions: List<String> = emptyList(),
    val followSymlinks: Boolean = false,
    val maxSymlinkDepth: Int = 3,
    val binaryDetection: BinaryDetectionMode = BinaryDetectionMode.ALL,
    val binaryThreshold: Int = 30
)

data class EmbeddingConfig(
    val model: String = "sentence-transformers/all-MiniLM-L6-v2",
    val dimension: Int = 384,
    val batchSize: Int = 128,
    val normalize: Boolean = true,
    val cacheEnabled: Boolean = true
)

data class ChunkingConfig(
    val markdown: MarkdownChunkingConfig = MarkdownChunkingConfig(),
    val python: PythonChunkingConfig = PythonChunkingConfig(),
    val kotlin: KotlinChunkingConfig = KotlinChunkingConfig(),
    val typescript: TypeScriptChunkingConfig = TypeScriptChunkingConfig()
) {
    data class MarkdownChunkingConfig(
        val maxTokens: Int = 400,
        val splitByHeadings: Boolean = true,
        val preserveCodeBlocks: Boolean = true
    )

    data class PythonChunkingConfig(
        val maxTokens: Int = 600,
        val splitByFunction: Boolean = true,
        val overlapPercent: Int = 15,
        val preserveDocstrings: Boolean = true
    )

    data class KotlinChunkingConfig(
        val maxTokens: Int = 600,
        val splitByClass: Boolean = true,
        val splitByFunction: Boolean = true,
        val preserveKdoc: Boolean = true
    )

    data class TypeScriptChunkingConfig(
        val maxTokens: Int = 600,
        val splitByExport: Boolean = true,
        val preserveJsdoc: Boolean = true
    )
}

data class QueryConfig(
    val defaultK: Int = 12,
    val mmrLambda: Double = 0.5,
    val minScoreThreshold: Double = 0.3,
    val rerankEnabled: Boolean = true
)

data class BudgetConfig(
    val defaultMaxTokens: Int = 1_500,
    val reserveForPrompt: Int = 500,
    val warnThresholdPercent: Int = 80
)

data class ProviderConfig(
    val enabled: Boolean = true,
    val weight: Double = 1.0,
    val indexAst: Boolean? = null,
    val maxCommits: Int? = null,
    val combines: List<String> = emptyList(),
    val fusionStrategy: String? = null
) {
    companion object {
        fun defaults(): Map<String, ProviderConfig> = mapOf(
            "semantic" to ProviderConfig(weight = 0.6),
            "symbol" to ProviderConfig(weight = 0.3, indexAst = true),
            "full_text" to ProviderConfig(weight = 0.1),
            "git_history" to ProviderConfig(weight = 0.2, maxCommits = 100),
            "hybrid" to ProviderConfig(
                weight = 0.5,
                combines = listOf("semantic", "symbol", "git_history"),
                fusionStrategy = "rrf"
            )
        )
    }
}

data class MetricsConfig(
    val enabled: Boolean = true,
    val trackLatency: Boolean = true,
    val trackTokenUsage: Boolean = true,
    val trackCacheHits: Boolean = true,
    val exportIntervalMinutes: Int = 5
)

data class BootstrapConfig(
    val enabled: Boolean = true,
    val parallelWorkers: Int = 7,
    val batchSize: Int = 128,
    val priorityExtensions: List<String> = listOf(
        ".kt",
        ".py",
        ".ts",
        ".java",
        ".md"
    ),
    val maxInitialFiles: Int = 0,
    val failFast: Boolean = false,
    val showProgress: Boolean = true,
    val progressIntervalSeconds: Int = 30
)

data class SecurityConfig(
    val scrubSecrets: Boolean = true,
    val secretPatterns: List<String> = listOf(
        "password\\s*=\\s*['\"]?.*['\"]?",
        "api[_-]?key\\s*=\\s*['\"]?.*['\"]?",
        "token\\s*=\\s*['\"]?.*['\"]?"
    ),
    val encryptDb: Boolean = false
)
