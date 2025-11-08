
# FusionAgent Configuration Documentation

This document provides a comprehensive overview of all the configuration options available in the `fusionagent.toml` file. For each option, it describes its purpose and, most importantly, whether it is currently wired into the code.

## `[orchestrator.server]`

This section configures the MCP (Model Context Protocol) server, which handles inter-agent communication.

| Option | Description | Wired in Code |
| :--- | :--- | :--- |
| `host` | The hostname or IP address the MCP server will bind to. | Yes |
| `port` | The port the MCP server will listen on. | Yes |
| `transport` | The transport protocol to use. Currently, only `HTTP` is supported. | No (hardcoded to HTTP) |

**Analysis:** The `host` and `port` options are used in `McpServerImpl.kt` to start the embedded Netty server. The `transport` option is not used, and the server always uses HTTP.

---

## `[web]`

This section configures the web dashboard server, which provides a UI for the orchestrator.

| Option | Description | Wired in Code |
| :--- | :--- | :--- |
| `host` | The hostname or IP address the web server will bind to. | Yes |
| `port` | The port the web server will listen on. | Yes |
| `staticPath` | The path to the static assets for the web dashboard. | No (hardcoded to "static") |
| `cors.enabled` | Enables or disables CORS (Cross-Origin Resource Sharing) for the web server. | Yes |
| `cors.allowedOrigins` | A list of origins that are allowed to make cross-origin requests. | Yes |

**Analysis:** The `host` and `port` options are used when starting the Ktor web server. The `cors.enabled` and `cors.allowedOrigins` options are used in `Cors.kt` to configure the CORS plugin. The `staticPath` option is not used; the path is hardcoded to "static" in `Static.kt`.

---

## `[agents]`

This section defines the AI agents that will be used by the orchestrator. Each agent has a unique ID and a set of configuration options.

| Option | Description | Wired in Code |
| :--- | :--- | :--- |
| `type` | The type of the agent (e.g., `CLAUDE_CODE`, `CODEX_CLI`, etc.). | Yes |
| `name` | A human-readable name for the agent. | Yes |
| `model` | The specific model to use for the agent (e.g., `gpt-4`). | Yes |
| `apiKeyRef` | A reference to an environment variable containing the API key for the agent. | Yes |
| `organization` | The organization ID for the API (e.g., for OpenAI). | Yes |
| `temperature` | The sampling temperature to use for the model. | Yes |
| `maxTokens` | The maximum number of tokens to generate. | Yes |
| `extra` | A map of extra parameters to pass to the agent's API. | Yes |

**Analysis:** The agent configurations are loaded by `ConfigLoader.loadAgents` and stored in the `AgentRegistry`. These configurations are then used when interacting with the respective agent's API. All options are wired in.

---

## `[context]`

This section configures the context system, which is responsible for indexing files and providing relevant context to the agents.

| Option | Description | Wired in Code |
| :--- | :--- | :--- |
| `enabled` | Enables or disables the entire context subsystem. | Yes |

**Analysis:** The `enabled` option is checked in `Main.kt` to determine whether to initialize the context module.

### `[context.engine]`

This section is intended to configure the context engine, but it is not currently used.

| Option | Description | Wired in Code |
| :--- | :--- | :--- |
| `timeout_ms` | Timeout in milliseconds for context engine operations. | No |
| `retry_attempts` | Number of times to retry a failed context engine operation. | No |

**Analysis:** These options are loaded but not used.

### `[context.storage]`

This section configures the database and persistence settings for the context system.

| Option | Description | Wired in Code |
| :--- | :--- | :--- |
| `db_path` | The path to the DuckDB database file. | Yes |

**Analysis:** The `db_path` option is used in `ContextDatabase.kt` to initialize the database connection.

### `[context.watcher]`

This section configures the file system watcher, which monitors files for changes and triggers re-indexing.

| Option | Description | Wired in Code |
| :--- | :--- | :--- |
| `enabled` | Enables or disables the file watcher. | Yes |
| `debounce_ms` | The debounce time in milliseconds for file system events. | Yes |
| `watch_paths` | A list of paths to watch for file changes. Supports "auto" to auto-detect project root. | Yes |
| `ignore_patterns` | A list of glob patterns to ignore when watching for file changes. | Yes |
| `max_file_size_mb` | Maximum file size in MB for the watcher to process. | Yes |
| `use_gitignore` | Whether to use the `.gitignore` file for ignoring files. | Yes |
| `use_contextignore` | Whether to use the `.contextignore` file for ignoring files. | Yes |

**Analysis:** All options in this section are used in `FileWatcher.kt` and `WatcherDaemon.kt` to configure the file watching and filtering logic. The watcher supports auto-detection of project root when "auto" is specified in watch_paths.

### `[context.indexing]`

This section configures the file indexing and filtering settings.

| Option | Description | Wired in Code |
| :--- | :--- | :--- |
| `allowed_extensions` | A list of file extensions that are allowed to be indexed. | Yes |
| `blocked_extensions` | A list of file extensions that are blocked from being indexed. | Yes |
| `max_file_size_mb` | The maximum file size in megabytes for indexing. | Yes |
| `warn_file_size_mb` | The file size in megabytes at which to log a warning. | Yes |
| `size_exceptions` | A list of files that are exempt from the size limit. | Yes |
| `follow_symlinks` | Whether to follow symbolic links when indexing. | Yes |
| `max_symlink_depth` | The maximum depth to follow symbolic links. | Yes |
| `binary_detection` | The method to use for detecting binary files (`extension`, `mime`, `content`, `all`). | Yes |
| `binary_threshold` | The percentage of non-ASCII characters to consider a file binary. | Yes |

**Analysis:** All options in this section are wired in through `ContextConfigLoader.parseIndexing()` and used in `PathValidator.kt` and related file filtering logic. Binary detection supports multiple modes with configurable thresholds for determining file types.

### `[context.embedding]`

This section configures the vector embedding settings.

| Option | Description | Wired in Code |
| :--- | :--- | :--- |
| `model` | The name of the embedding model to use. | Yes |
| `model_path` | Path to the ONNX model file. If not specified, uses default bundled model or `ONNX_MODEL_PATH` environment variable. | Yes |
| `dimension` | The dimension of the embedding vectors. | Yes |
| `batch_size` | The batch size for generating embeddings. | Yes |
| `normalize` | Whether to normalize the embedding vectors. | Yes |
| `cache_enabled` | Whether to cache the embeddings. | Yes |

**Analysis:** All options in this section are used in `LocalEmbedder.kt` and loaded via `ContextConfigLoader.parseEmbedding()`. The `model_path` option takes priority: if set in config, it's used; otherwise the system falls back to the `ONNX_MODEL_PATH` environment variable or the bundled default model. Caching is fully implemented and can be toggled based on memory constraints.

### `[context.chunking]`

This section configures the text chunking strategies for different languages.

| Option | Description | Wired in Code |
| :--- | :--- | :--- |
| `markdown.max_tokens` | Maximum number of tokens for a markdown chunk. | Yes |
| `markdown.split_by_headings` | Whether to split markdown by headings. | Yes (hardcoded) |
| `markdown.preserve_code_blocks` | Whether to preserve code blocks in markdown. | Yes (hardcoded) |
| `python.max_tokens` | Maximum number of tokens for a python chunk. | Yes |
| `python.split_by_function` | Whether to split python by functions. | Yes (hardcoded) |
| `python.overlap_percent` | The percentage of overlap between python chunks. | Yes |
| `python.preserve_docstrings` | Whether to preserve docstrings in python. | Yes (hardcoded) |
| `kotlin.max_tokens` | Maximum number of tokens for a kotlin chunk. | Yes |
| `kotlin.split_by_class` | Whether to split kotlin by classes. | Yes (hardcoded) |
| `kotlin.split_by_function` | Whether to split kotlin by functions. | Yes (hardcoded) |
| `kotlin.preserve_kdoc` | Whether to preserve KDoc comments in kotlin. | Yes (hardcoded) |
| `typescript.max_tokens` | Maximum number of tokens for a typescript chunk. | Yes |
| `typescript.split_by_export` | Whether to split typescript by exports. | Yes (hardcoded) |
| `typescript.preserve_jsdoc` | Whether to preserve JSDoc comments in typescript. | Yes (hardcoded) |

**Analysis:** The `max_tokens` and `overlap_percent` options are used in the respective chunker implementations. The `split_by_*` and `preserve_*` options are effectively hardcoded to `true` in the current implementation.

### `[context.query]`

This section configures the query and retrieval settings.

| Option | Description | Default | Wired in Code |
| :--- | :--- | :--- | :--- |
| `default_k` | The default number of context snippets to return. | 12 | Yes |
| `mmr_lambda` | The lambda value for the MMR (Maximal Marginal Relevance) reranking algorithm (0.0-1.0). | 0.5 | Yes |
| `min_score_threshold` | The minimum relevance score threshold for a snippet to be included. | 0.3 | Yes |
| `rerank_enabled` | Whether to enable MMR reranking for diverse results. | true | Yes |

**Analysis:** All options in this section are used in `QueryOptimizer.kt` to filter and rerank the search results. The `QueryContextTool` applies these configuration settings to determine which snippets are returned and how they are ranked. These settings directly impact the quality and diversity of context provided to agents.

### `[context.budget]`

This section configures the token budget management.

| Option | Description | Default | Wired in Code |
| :--- | :--- | :--- | :--- |
| `default_max_tokens` | The default maximum number of tokens for the context. | 1500 | Yes |
| `reserve_for_prompt` | The number of tokens to reserve for the prompt. | 500 | Yes |
| `warn_threshold_percent` | The percentage of the budget at which to log a warning. | 80 | Yes |

**Analysis:** All options in this section are used in `BudgetManager.kt` to manage token allocation. The budget system ensures that context stays within model limits while reserving tokens for the actual prompt and reserving warnings when approaching limits.

### `[context.providers]`

This section configures the different context providers.

| Option | Description | Wired in Code |
| :--- | :--- | :--- |
| `full_text.enabled` | Enables or disables the full-text search provider. | Yes |
| `full_text.weight` | The weight of the full-text search provider in the final ranking. | Yes |
| `semantic.enabled` | Enables or disables the semantic search provider. | Yes |
| `semantic.weight` | The weight of the semantic search provider in the final ranking. | Yes |
| `symbol.enabled` | Enables or disables the symbol search provider. | Yes |
| `symbol.weight` | The weight of the symbol search provider in the final ranking. | Yes |
| `symbol.index_ast` | Whether to index the Abstract Syntax Tree (AST) for symbol search. | Yes |
| `git_history.enabled` | Enables or disables the git history provider. | Yes |
| `git_history.weight` | The weight of the git history provider in the final ranking. | Yes |
| `git_history.max_commits` | The maximum number of commits to search in the git history. | Yes |
| `hybrid.enabled` | Enables or disables the hybrid search provider. | Yes |
| `hybrid.weight` | The weight of the hybrid search provider in the final ranking. | Yes |
| `hybrid.combines` | A list of providers to combine in the hybrid search. | Yes |
| `hybrid.fusion_strategy` | The strategy to use for fusing the results from the combined providers (e.g., `rrf`). | Yes |

**Analysis:** All options in this section are wired in through `ContextConfigLoader.parseProviders()`. Each provider can be individually enabled/disabled and weighted, allowing fine-grained control over the search behavior. The hybrid provider combines multiple sources using the specified fusion strategy.

### `[context.metrics]`

This section configures the metrics and monitoring for the context system.

| Option | Description | Wired in Code |
| :--- | :--- | :--- |
| `enabled` | Enables or disables the metrics module. | Yes |
| `track_latency` | Whether to track the latency of context retrieval. | Yes |
| `track_token_usage` | Whether to track the token usage of context retrieval. | Yes |
| `track_cache_hits` | Whether to track the cache hit rate for context retrieval. | Yes |
| `export_interval_minutes` | The interval in minutes at which to export the metrics. | Yes |

**Analysis:** All options in this section are wired in through `ContextConfigLoader.parseMetrics()` and used to configure the `MetricsConfig` class. The metrics are properly tracked and exported at the configured intervals.

### `[context.bootstrap]`

This section configures the initial indexing (bootstrap) of the project.

| Option | Description | Wired in Code |
| :--- | :--- | :--- |
| `enabled` | Enables or disables the bootstrap process. | Yes |
| `parallel_workers` | The number of parallel workers to use for bootstrapping. | Yes |
| `batch_size` | The batch size for indexing files. | Yes |
| `priority_extensions` | A list of file extensions to prioritize during bootstrapping. | Yes |
| `max_initial_files` | The maximum number of files to index during the initial bootstrap. | Yes |
| `fail_fast` | Whether to stop the bootstrap process on the first error. | Yes |
| `show_progress` | Whether to show the progress of the bootstrap process. | Yes |
| `progress_interval_seconds` | The interval in seconds at which to show the progress. | Yes |

**Analysis:** All options in this section are wired in through `ContextConfigLoader.parseBootstrap()` and used to configure the `BootstrapConfig` class. The bootstrap process respects all settings for parallel processing, error handling, and progress reporting.

### `[context.security]`

This section configures the security and privacy settings for the context system.

| Option | Description | Wired in Code |
| :--- | :--- | :--- |
| `scrub_secrets` | Whether to scrub secrets from the indexed content. | Yes |
| `secret_patterns` | A list of regex patterns to use for scrubbing secrets. | Yes |
| `encrypt_db` | Whether to encrypt the context database. | Yes |

**Analysis:** All options in this section are wired in through `ContextConfigLoader.parseSecurity()` and used to configure `SecurityConfig`. These settings control how sensitive information is handled during indexing and storage. When enabled, the system will apply regex patterns to detect and scrub secrets before storing content in the database.

### `[ignore]`

This section defines file patterns to exclude from context indexing.

| Option | Description | Wired in Code |
| :--- | :--- | :--- |
| `patterns` | A list of glob patterns to ignore when indexing files. | Yes |

**Analysis:** The `patterns` option is used in `PathFilter.kt` to filter out files and directories during the indexing process.
