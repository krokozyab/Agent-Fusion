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
    val neo4j: Neo4jConfig = Neo4jConfig(),
    val watcher: WatcherConfig = WatcherConfig(),
    val indexing: IndexingConfig = IndexingConfig(),
    val embedding: EmbeddingConfig = EmbeddingConfig(),
    val chunking: ChunkingConfig = ChunkingConfig(),
    val query: QueryConfig = QueryConfig(),
    val budget: BudgetConfig = BudgetConfig(),
    val providers: Map<String, ProviderConfig> = ProviderConfig.defaults(),
    val metrics: MetricsConfig = MetricsConfig(),
    val bootstrap: BootstrapConfig = BootstrapConfig(),
    val security: SecurityConfig = SecurityConfig(),
    val structuralWeight: Double = 0.0,
    val useStructuredOutput: Boolean = false
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
    val dbPath: String = "./context.duckdb"
)

data class Neo4jConfig(
    val enabled: Boolean = false,
    val mode: String = "embedded",  // "embedded" or "server"
    val dataDir: String = "./data/neo4j",  // For embedded mode
    val uri: String = "bolt://localhost:7687",  // For server mode
    val username: String = "neo4j",
    val password: String = "password",
    val database: String = "neo4j",
    val maxConnectionPoolSize: Int = 50,
    val connectionTimeoutMs: Long = 30000
)

data class WatcherConfig(
    val enabled: Boolean = true,
    val debounceMs: Long = 500,
    val watchPaths: List<String> = listOf("auto"),
    // Optional: If set, ONLY these paths will be indexed (allowlist)
    // Supports both relative paths (relative to project root) and absolute paths
    // Relative: "src/", "lib/" -> resolved relative to project root
    // Absolute: "/absolute/path/to/project/" -> used as-is
    // Relative outside root: "../sibling-project/" -> resolved from project root
    // If empty, all paths except ignorePatterns will be indexed (blacklist mode)
    val includePaths: List<String> = emptyList(),
    val ignorePatterns: List<String> = listOf(
        ".git",
        "node_modules",
        "build",
        "dist",
        ".venv",
        "target"
    ),
    val useGitignore: Boolean = true,
    val useContextignore: Boolean = true,
    val deletionSweepIntervalMs: Long = 60_000
)

enum class BinaryDetectionMode { EXTENSION, MIME, CONTENT, ALL }

data class IndexingConfig(
    val allowedExtensions: List<String> = listOf(
        ".kt",
        ".kts",
        ".java",
        ".go",
        ".py",
        ".ts",
        ".tsx",
        ".js",
        ".jsx",
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
    // Skip patterns: exclude specific files even if they match allowed extensions
    // Examples: "*.min.js", "*.test.js", "*.spec.ts", "**/dist/**"
    // These patterns are applied AFTER extension filtering
    val skipPatterns: List<String> = emptyList(),
    val maxFileSizeMb: Int = 10,
    val warnFileSizeMb: Int = 2,
    val sizeExceptions: List<String> = emptyList(),
    val followSymlinks: Boolean = false,
    val maxSymlinkDepth: Int = 3,
    val binaryDetection: BinaryDetectionMode = BinaryDetectionMode.ALL,
    val binaryThreshold: Int = 30
)

data class EmbeddingConfig(
    val model: String = "sentence-transformers/all-MiniLM-L6-v2",
    val modelPath: String? = null,
    val dimension: Int = 384,
    val batchSize: Int = 128,
    val normalize: Boolean = true,
    val cacheEnabled: Boolean = true
)

data class ChunkingConfig(
    val overlapEnabled: Boolean = false,
    val overlapPercent: Int = 15,
    val markdown: MarkdownChunkingConfig = MarkdownChunkingConfig(),
    val python: PythonChunkingConfig = PythonChunkingConfig(),
    val kotlin: KotlinChunkingConfig = KotlinChunkingConfig(),
    val go: GoChunkingConfig = GoChunkingConfig(),
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

    data class GoChunkingConfig(
        val maxTokens: Int = 600
    )

    data class TypeScriptChunkingConfig(
        val maxTokens: Int = 600,
        val splitByExport: Boolean = true,
        val preserveJsdoc: Boolean = true
    )
}

data class QueryConfig(
    val defaultK: Int = 12,
    val semanticCandidateLimit: Int = 256,
    val rrfK: Int = 60,
    val mmrLambda: Double = 0.5,
    val minScoreThreshold: Double = 0.3,
    val rerankEnabled: Boolean = true,
    val useOptimizerInTool: Boolean = true,
    val neighborWindow: Int = 1,
    val embeddingCacheSize: Int = 1000,
    val boosts: BoostConfig = BoostConfig(),
    val secondStageRerankEnabled: Boolean = false,
    val secondStageTopN: Int = 80,
    val secondStageBlendWeight: Double = 0.35,
    val queryExpansionEnabled: Boolean = false,
    val hydeEnabled: Boolean = false,
    val maxExpansionTerms: Int = 8,
    val synonyms: Map<String, List<String>> = defaultQuerySynonyms(),
    val graph: GraphConfig = GraphConfig()
)

private fun defaultQuerySynonyms(): Map<String, List<String>> = mapOf(
    "login" to listOf("authentication", "auth", "credentials", "token"),
    "auth" to listOf("authentication", "login", "token", "credentials"),
    "password" to listOf("credential", "secret", "auth"),
    "rep" to listOf("report", "reporting"),
    "db" to listOf("database", "sql", "query"),
    "bug" to listOf("defect", "issue", "error", "failure"),
    "fix" to listOf("patch", "resolve", "correct"),
    "api" to listOf("endpoint", "http", "rest", "request", "response")
)

data class BoostConfig(
    val pathPrefixes: Map<String, Double> = mapOf(
        "src/main" to 1.05,
        "src/test" to 0.95,
        "vendor" to 0.90,
        "node_modules" to 0.80,
        "build" to 0.85,
        "dist" to 0.85
    ),
    val languages: Map<String, Double> = mapOf(
        "kotlin" to 1.02,
        "java" to 1.02,
        "python" to 1.02,
        "typescript" to 1.01,
        "javascript" to 1.01,
        "rust" to 1.02,
        "go" to 1.02,
        "c" to 1.00,
        "cpp" to 1.00,
        "csharp" to 1.00,
        "ruby" to 1.00,
        "php" to 1.00,
        "swift" to 1.00,
        "scala" to 1.00,
        "markdown" to 1.00,
        "json" to 0.95,
        "yaml" to 0.95,
        "xml" to 0.95,
        "toml" to 0.95,
        "sql" to 0.98,
        "shell" to 0.97,
        "dockerfile" to 0.97,
        "html" to 0.93,
        "css" to 0.93,
        "text" to 0.90,
        "document" to 0.92
    ),
    // File type penalties: Reduce scores for documentation/binary files that pollute code search
    // Values < 1.0 = penalty (e.g., 0.5 = 50% penalty), values > 1.0 = boost
    // Applied based on file extension
    val fileTypePenalties: Map<String, Double> = mapOf(
        "pdf" to 0.5,      // PDFs often dominate search results
        "doc" to 0.5,      // Word documents
        "docx" to 0.5,     // Word documents
        "txt" to 0.7,      // Plain text
        "md" to 0.7,       // Markdown docs (slight penalty, still useful)
        "rst" to 0.7,      // ReStructuredText
        "log" to 0.6,      // Log files
        "csv" to 0.8       // Data files
    ),
    // File pattern penalties: Reduce scores for specific path patterns
    // Supports glob patterns (e.g., "**/docs/**" matches docs directories anywhere)
    val filePatternPenalties: Map<String, Double> = mapOf(
        "**/docs/**" to 0.6,       // Documentation directories
        "**/documentation/**" to 0.6,
        "**/README*" to 0.8,        // READMEs less penalty (still valuable)
        "**/CHANGELOG*" to 0.7,
        "**/api-spec*" to 0.9       // API specs should stay visible
    ),
    // Chunk kind boosts: Boost or penalize based on chunk type
    // Values > 1.0 = boost code chunks, values < 1.0 = penalize documentation
    val chunkKindBoosts: Map<String, Double> = mapOf(
        "CODE_CLASS" to 1.3,           // Strongly favor class definitions
        "CODE_FUNCTION" to 1.3,        // Strongly favor function definitions
        "CODE_METHOD" to 1.3,          // Strongly favor method definitions
        "CODE_INTERFACE" to 1.2,       // Favor interfaces
        "CODE_ENUM" to 1.2,            // Favor enums
        "CODE_STRUCT" to 1.2,          // Favor structs
        "CODE_BLOCK" to 1.1,           // Slight boost for code blocks
        "DOCUMENTATION" to 0.6,        // Penalize documentation chunks
        "TEXT_PARAGRAPH" to 0.7,       // Penalize text paragraphs
        "COMMENT" to 0.8,              // Light penalty for comments
        "COMMIT_MESSAGE" to 0.85,      // Keep commit intent discoverable but below core code
        "MARKDOWN_SECTION" to 0.7      // Penalize markdown sections
    )
)

data class GraphConfig(
    val enabled: Boolean = false,
    val maxDepth: Int = 1,
    val decayFactor: Double = 0.7,
    val maxGraphResults: Int = 10,
    val defaultLinkScore: Double = 0.8,
    val minPropagatedScore: Double = 0.1
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
            "exact_match" to ProviderConfig(weight = 0.15),
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
        ".go",
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
