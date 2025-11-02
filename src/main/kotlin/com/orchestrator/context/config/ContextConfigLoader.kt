package com.orchestrator.context.config

import com.moandjiezana.toml.Toml
import com.orchestrator.context.bootstrap.ProjectConfigValidator
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Loads context configuration from a TOML file and validates the resulting model.
 *
 * The loader performs environment variable substitution ("${VAR}") before parsing
 * and falls back to default values when the file is missing or individual settings
 * are omitted.
 */
object ContextConfigLoader {
    private val envVarRegex = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}")

    /**
     * Load configuration from [path] (defaults to `fusionagent.toml`). When the
     * file is absent a default [ContextConfig] is returned. Validation errors are
     * reported with descriptive messages.
     */
    fun load(
        path: Path = Path.of("fusionagent.toml"),
        env: Map<String, String> = System.getenv()
    ): ContextConfig {
        val file = path.toFile()
        val baseDir = Path.of(".").toAbsolutePath().normalize()

        if (!file.exists()) {
            val defaults = ContextConfig()
            validate(defaults, baseDir)
            return defaults
        }

        val content = file.readText()
        val expanded = content.expandEnv(env)
        val toml = try {
            Toml().read(expanded)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse ${file.path}: ${e.message}", e)
        }

        val defaults = ContextConfig()
        val contextTable = toml.getTable("context")
        val ignoreTable = toml.getTable("ignore")
        val config = if (contextTable == null) {
            defaults.copy(ignore = parseIgnore(ignoreTable, env))
        } else {
            ContextConfig(
                enabled = contextTable.getBoolean("enabled") ?: defaults.enabled,
                mode = parseMode(contextTable.getString("mode"), defaults.mode),
                fallbackEnabled = contextTable.getBoolean("fallback_enabled") ?: defaults.fallbackEnabled,
                engine = parseEngine(contextTable.getTable("engine"), env),
                storage = parseStorage(contextTable.getTable("storage"), env),
                watcher = parseWatcher(contextTable.getTable("watcher"), env),
                indexing = parseIndexing(contextTable.getTable("indexing"), env),
                embedding = parseEmbedding(contextTable.getTable("embedding"), env),
                chunking = parseChunking(contextTable.getTable("chunking")),
                query = parseQuery(contextTable.getTable("query")),
                budget = parseBudget(contextTable.getTable("budget")),
                providers = parseProviders(contextTable.getTable("providers"), env),
                metrics = parseMetrics(contextTable.getTable("metrics")),
                bootstrap = parseBootstrap(contextTable.getTable("bootstrap"), env),
                security = parseSecurity(contextTable.getTable("security"), env),
                ignore = parseIgnore(ignoreTable, env)
            )
        }

        validate(config, baseDir)
        return config
    }

    private fun parseMode(raw: String?, defaultMode: DeploymentMode): DeploymentMode {
        if (raw == null) return defaultMode
        return DeploymentMode.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "Invalid context.mode '$raw'. Allowed: ${DeploymentMode.entries.joinToString(", ") { it.name.lowercase() }}"
            )
    }

    private fun parseEngine(table: Toml?, env: Map<String, String>): EngineConfig {
        val defaults = EngineConfig()
        if (table == null) return defaults
        return EngineConfig(
            host = table.getString("host")?.expandEnv(env) ?: defaults.host,
            port = table.getLong("port")?.toInt() ?: defaults.port,
            timeoutMs = table.getLong("timeout_ms") ?: defaults.timeoutMs,
            retryAttempts = table.getLong("retry_attempts")?.toInt() ?: defaults.retryAttempts
        )
    }

    private fun parseStorage(table: Toml?, env: Map<String, String>): StorageConfig {
        val defaults = StorageConfig()
        if (table == null) return defaults
        return StorageConfig(
            dbPath = table.getString("db_path")?.expandEnv(env) ?: defaults.dbPath
        )
    }

    private fun parseWatcher(table: Toml?, env: Map<String, String>): WatcherConfig {
        val defaults = WatcherConfig()
        if (table == null) return defaults
        val watchPaths = table.getList<Any>("watch_paths")?.map { it.toString().expandEnv(env) }
        val includePaths = table.getList<Any>("include_paths")?.map { it.toString().expandEnv(env) }
        val ignorePatterns = table.getList<Any>("ignore_patterns")?.map { it.toString().expandEnv(env) }
        return WatcherConfig(
            enabled = table.getBoolean("enabled") ?: defaults.enabled,
            debounceMs = table.getLong("debounce_ms") ?: defaults.debounceMs,
            watchPaths = when {
                watchPaths == null || watchPaths.isEmpty() -> defaults.watchPaths
                else -> watchPaths
            },
            includePaths = includePaths ?: defaults.includePaths,
            ignorePatterns = ignorePatterns ?: defaults.ignorePatterns,
            useGitignore = table.getBoolean("use_gitignore") ?: defaults.useGitignore,
            useContextignore = table.getBoolean("use_contextignore") ?: defaults.useContextignore
        )
    }

    private fun parseIndexing(table: Toml?, env: Map<String, String>): IndexingConfig {
        val defaults = IndexingConfig()
        if (table == null) return defaults
        val allowed = table.getList<String>("allowed_extensions")?.map { it.expandEnv(env) }

        val sizeExceptions = table.getList<String>("size_exceptions")?.map { it.expandEnv(env) }
        val detection = table.getString("binary_detection")?.let { value ->
            runCatching { BinaryDetectionMode.valueOf(value.trim().uppercase()) }.getOrNull()
        }

        return IndexingConfig(
            allowedExtensions = allowed ?: defaults.allowedExtensions,
            maxFileSizeMb = table.getLong("max_file_size_mb")?.toInt() ?: defaults.maxFileSizeMb,
            warnFileSizeMb = table.getLong("warn_file_size_mb")?.toInt() ?: defaults.warnFileSizeMb,
            sizeExceptions = sizeExceptions ?: defaults.sizeExceptions,
            followSymlinks = table.getBoolean("follow_symlinks") ?: defaults.followSymlinks,
            maxSymlinkDepth = table.getLong("max_symlink_depth")?.toInt() ?: defaults.maxSymlinkDepth,
            binaryDetection = detection ?: defaults.binaryDetection,
            binaryThreshold = table.getLong("binary_threshold")?.toInt() ?: defaults.binaryThreshold
        )
    }

    private fun parseEmbedding(table: Toml?, env: Map<String, String>): EmbeddingConfig {
        val defaults = EmbeddingConfig()
        if (table == null) return defaults
        return EmbeddingConfig(
            model = table.getString("model")?.expandEnv(env) ?: defaults.model,
            dimension = table.getLong("dimension")?.toInt() ?: defaults.dimension,
            batchSize = table.getLong("batch_size")?.toInt() ?: defaults.batchSize,
            normalize = table.getBoolean("normalize") ?: defaults.normalize,
            cacheEnabled = table.getBoolean("cache_enabled") ?: defaults.cacheEnabled
        )
    }

    private fun parseChunking(table: Toml?): ChunkingConfig {
        val defaults = ChunkingConfig()
        if (table == null) return defaults
        return ChunkingConfig(
            markdown = table.getTable("markdown")?.let {
                ChunkingConfig.MarkdownChunkingConfig(
                    maxTokens = it.getLong("max_tokens")?.toInt() ?: defaults.markdown.maxTokens,
                    splitByHeadings = it.getBoolean("split_by_headings") ?: defaults.markdown.splitByHeadings,
                    preserveCodeBlocks = it.getBoolean("preserve_code_blocks") ?: defaults.markdown.preserveCodeBlocks
                )
            } ?: defaults.markdown,
            python = table.getTable("python")?.let {
                ChunkingConfig.PythonChunkingConfig(
                    maxTokens = it.getLong("max_tokens")?.toInt() ?: defaults.python.maxTokens,
                    splitByFunction = it.getBoolean("split_by_function") ?: defaults.python.splitByFunction,
                    overlapPercent = it.getLong("overlap_percent")?.toInt() ?: defaults.python.overlapPercent,
                    preserveDocstrings = it.getBoolean("preserve_docstrings") ?: defaults.python.preserveDocstrings
                )
            } ?: defaults.python,
            kotlin = table.getTable("kotlin")?.let {
                ChunkingConfig.KotlinChunkingConfig(
                    maxTokens = it.getLong("max_tokens")?.toInt() ?: defaults.kotlin.maxTokens,
                    splitByClass = it.getBoolean("split_by_class") ?: defaults.kotlin.splitByClass,
                    splitByFunction = it.getBoolean("split_by_function") ?: defaults.kotlin.splitByFunction,
                    preserveKdoc = it.getBoolean("preserve_kdoc") ?: defaults.kotlin.preserveKdoc
                )
            } ?: defaults.kotlin,
            typescript = table.getTable("typescript")?.let {
                ChunkingConfig.TypeScriptChunkingConfig(
                    maxTokens = it.getLong("max_tokens")?.toInt() ?: defaults.typescript.maxTokens,
                    splitByExport = it.getBoolean("split_by_export") ?: defaults.typescript.splitByExport,
                    preserveJsdoc = it.getBoolean("preserve_jsdoc") ?: defaults.typescript.preserveJsdoc
                )
            } ?: defaults.typescript
        )
    }

    private fun parseQuery(table: Toml?): QueryConfig {
        val defaults = QueryConfig()
        if (table == null) return defaults
        return QueryConfig(
            defaultK = table.getLong("default_k")?.toInt() ?: defaults.defaultK,
            mmrLambda = table.getDouble("mmr_lambda") ?: defaults.mmrLambda,
            minScoreThreshold = table.getDouble("min_score_threshold") ?: defaults.minScoreThreshold,
            rerankEnabled = table.getBoolean("rerank_enabled") ?: defaults.rerankEnabled
        )
    }

    private fun parseBudget(table: Toml?): BudgetConfig {
        val defaults = BudgetConfig()
        if (table == null) return defaults
        return BudgetConfig(
            defaultMaxTokens = table.getLong("default_max_tokens")?.toInt() ?: defaults.defaultMaxTokens,
            reserveForPrompt = table.getLong("reserve_for_prompt")?.toInt() ?: defaults.reserveForPrompt,
            warnThresholdPercent = table.getLong("warn_threshold_percent")?.toInt() ?: defaults.warnThresholdPercent
        )
    }

    private fun parseProviders(table: Toml?, env: Map<String, String>): Map<String, ProviderConfig> {
        val defaults = ProviderConfig.defaults().toMutableMap()
        if (table == null) return defaults
        for (key in table.toMap().keys) {
            val providerTable = table.getTable(key) ?: continue
            val base = defaults[key] ?: ProviderConfig()
            defaults[key] = ProviderConfig(
                enabled = providerTable.getBoolean("enabled") ?: base.enabled,
                weight = providerTable.getDouble("weight") ?: base.weight,
                indexAst = providerTable.getBoolean("index_ast") ?: base.indexAst,
                maxCommits = providerTable.getLong("max_commits")?.toInt() ?: base.maxCommits,
                combines = providerTable.getList<Any>("combines")?.map { it.toString().expandEnv(env) }
                    ?: base.combines,
                fusionStrategy = providerTable.getString("fusion_strategy")?.expandEnv(env) ?: base.fusionStrategy
            )
        }
        return defaults
    }

    private fun parseMetrics(table: Toml?): MetricsConfig {
        val defaults = MetricsConfig()
        if (table == null) return defaults
        return MetricsConfig(
            enabled = table.getBoolean("enabled") ?: defaults.enabled,
            trackLatency = table.getBoolean("track_latency") ?: defaults.trackLatency,
            trackTokenUsage = table.getBoolean("track_token_usage") ?: defaults.trackTokenUsage,
            trackCacheHits = table.getBoolean("track_cache_hits") ?: defaults.trackCacheHits,
            exportIntervalMinutes = table.getLong("export_interval_minutes")?.toInt() ?: defaults.exportIntervalMinutes
        )
    }

    private fun parseBootstrap(table: Toml?, env: Map<String, String>): BootstrapConfig {
        val defaults = BootstrapConfig()
        if (table == null) return defaults
        return BootstrapConfig(
            enabled = table.getBoolean("enabled") ?: defaults.enabled,
            parallelWorkers = table.getLong("parallel_workers")?.toInt() ?: defaults.parallelWorkers,
            batchSize = table.getLong("batch_size")?.toInt() ?: defaults.batchSize,
            priorityExtensions = table.getList<Any>("priority_extensions")?.map { it.toString().expandEnv(env) }
                ?: defaults.priorityExtensions,
            maxInitialFiles = table.getLong("max_initial_files")?.toInt() ?: defaults.maxInitialFiles,
            failFast = table.getBoolean("fail_fast") ?: defaults.failFast,
            showProgress = table.getBoolean("show_progress") ?: defaults.showProgress,
            progressIntervalSeconds = table.getLong("progress_interval_seconds")?.toInt()
                ?: defaults.progressIntervalSeconds
        )
    }

    private fun parseSecurity(table: Toml?, env: Map<String, String>): SecurityConfig {
        val defaults = SecurityConfig()
        if (table == null) return defaults
        val patterns = table.getList<Any>("secret_patterns")?.map { it.toString().expandEnv(env) }
        return SecurityConfig(
            scrubSecrets = table.getBoolean("scrub_secrets") ?: defaults.scrubSecrets,
            secretPatterns = patterns ?: defaults.secretPatterns,
            encryptDb = table.getBoolean("encrypt_db") ?: defaults.encryptDb
        )
    }

    private fun parseIgnore(table: Toml?, env: Map<String, String>): IgnoreConfig {
        val defaults = IgnoreConfig()
        if (table == null) return defaults
        val patterns = table.getList<Any>("patterns")?.map { it.toString().expandEnv(env) }
        return IgnoreConfig(
            patterns = patterns ?: defaults.patterns
        )
    }

    private fun validate(config: ContextConfig, baseDir: Path) {
        val errors = mutableListOf<String>()

        val watchPaths = config.watcher.watchPaths
        if (watchPaths.isEmpty()) {
            errors += "watcher.watch_paths must contain at least one entry or \"auto\""
        }
        val dangerousRoots = setOf("/", "/etc", "/sys", "/proc")
        for (raw in watchPaths) {
            if (raw.equals("auto", ignoreCase = true)) {
                continue
            }
            if (raw.isBlank()) {
                errors += "watcher.watch_paths contains a blank entry"
                continue
            }
            val resolved = resolvePath(raw, baseDir)
            val normalized = resolved.normalize()
            if (!Files.exists(normalized)) {
                errors += "watcher path '$raw' (resolved to '$normalized') does not exist"
            }
            if (Files.exists(normalized) && !Files.isDirectory(normalized)) {
                errors += "watcher path '$raw' must reference a directory"
            }
            val dangerous = normalized.toAbsolutePath().toString()
            if (dangerousRoots.any { root -> dangerous == root || dangerous.startsWith("$root/") }) {
                errors += "watcher path '$raw' points to restricted directory '$dangerous'"
            }
        }

        // Validate include_paths (optional allowlist)
        for (raw in config.watcher.includePaths) {
            if (raw.isBlank()) {
                errors += "watcher.include_paths contains a blank entry"
                continue
            }
            val resolved = resolvePath(raw, baseDir)
            val normalized = resolved.normalize()
            if (!Files.exists(normalized)) {
                errors += "watcher include_path '$raw' (resolved to '$normalized') does not exist"
            }
            if (Files.exists(normalized) && !Files.isDirectory(normalized)) {
                errors += "watcher include_path '$raw' must reference a directory"
            }
            val dangerous = normalized.toAbsolutePath().toString()
            if (dangerousRoots.any { root -> dangerous == root || dangerous.startsWith("$root/") }) {
                errors += "watcher include_path '$raw' points to restricted directory '$dangerous'"
            }
        }

        fun List<String>.validateExtensions(label: String) {
            forEach { ext ->
                if (!ext.startsWith('.')) {
                    errors += "$label entry '$ext' must start with '.'"
                }
                if (ext.count { it == '.' } > 1 && !ext.contains('*')) {
                    errors += "$label entry '$ext' is invalid"
                }
            }
        }

        config.indexing.allowedExtensions.validateExtensions("indexing.allowed_extensions")

        if (config.indexing.maxFileSizeMb <= 0) {
            errors += "indexing.max_file_size_mb must be greater than 0"
        }
        if (config.indexing.warnFileSizeMb <= 0) {
            errors += "indexing.warn_file_size_mb must be greater than 0"
        }
        if (config.indexing.warnFileSizeMb > config.indexing.maxFileSizeMb) {
            errors += "indexing.warn_file_size_mb cannot exceed max_file_size_mb"
        }
        if (config.embedding.dimension <= 0) {
            errors += "embedding.dimension must be greater than 0"
        }
        if (config.embedding.batchSize <= 0) {
            errors += "embedding.batch_size must be greater than 0"
        }
        if (config.query.defaultK <= 0) {
            errors += "query.default_k must be greater than 0"
        }
        if (config.query.mmrLambda !in 0.0..1.0) {
            errors += "query.mmr_lambda must be in [0.0, 1.0]"
        }
        if (config.query.minScoreThreshold !in 0.0..1.0) {
            errors += "query.min_score_threshold must be in [0.0, 1.0]"
        }
        if (config.budget.defaultMaxTokens <= 0) {
            errors += "budget.default_max_tokens must be greater than 0"
        }
        if (config.budget.reserveForPrompt < 0) {
            errors += "budget.reserve_for_prompt cannot be negative"
        }
        if (config.budget.reserveForPrompt > config.budget.defaultMaxTokens) {
            errors += "budget.reserve_for_prompt cannot exceed default_max_tokens"
        }
        if (config.metrics.exportIntervalMinutes <= 0) {
            errors += "metrics.export_interval_minutes must be greater than 0"
        }
        if (config.bootstrap.parallelWorkers <= 0) {
            errors += "bootstrap.parallel_workers must be greater than 0"
        }
        if (config.bootstrap.batchSize <= 0) {
            errors += "bootstrap.batch_size must be greater than 0"
        }
        if (config.bootstrap.progressIntervalSeconds <= 0) {
            errors += "bootstrap.progress_interval_seconds must be greater than 0"
        }
        if (config.bootstrap.maxInitialFiles < 0) {
            errors += "bootstrap.max_initial_files cannot be negative"
        }
        if (config.security.secretPatterns.any { it.isBlank() }) {
            errors += "security.secret_patterns cannot contain blank entries"
        }
        config.providers.forEach { (id, provider) ->
            if (provider.weight <= 0.0) {
                errors += "providers.$id.weight must be greater than 0"
            }
            provider.combines.forEach { combined ->
                if (combined !in config.providers.keys) {
                    errors += "providers.$id.combines references unknown provider '$combined'"
                }
            }
        }

        // Additional validation from ProjectConfigValidator
        val projectValidator = ProjectConfigValidator()
        val validationResult = projectValidator.validate(config)
        if (!validationResult.isValid) {
            errors.addAll(validationResult.errors)
        }

        if (errors.isNotEmpty()) {
            val message = buildString {
                appendLine("Invalid context configuration:")
                errors.forEach { appendLine(" - $it") }
            }
            throw IllegalArgumentException(message.trim())
        }
    }

    private fun resolvePath(raw: String, baseDir: Path): Path {
        val candidate = Paths.get(raw)
        return if (candidate.isAbsolute) candidate else baseDir.resolve(candidate)
    }

    private fun String.expandEnv(env: Map<String, String>): String =
        this.replace(envVarRegex) { match ->
            val key = match.groupValues[1]
            env[key] ?: match.value
        }
}
