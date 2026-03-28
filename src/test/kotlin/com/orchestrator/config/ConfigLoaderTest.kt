package com.orchestrator.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigLoaderTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `loadAll should parse web port from simple TOML config`() {
        val toml = tempDir.resolve("fusionagent.toml")
        toml.toFile().writeText("""
            [orchestrator.server]
            host = "127.0.0.1"
            port = 3000
            transport = "HTTP"

            [web]
            host = "0.0.0.0"
            port = 9999
            staticPath = "static"

            [web.cors]
            enabled = true
            allowedOrigins = ["http://localhost:9999"]

            [context]
            enabled = false

            [agents.claude-code]
            type = "CLAUDE_CODE"
            name = "Claude"
        """.trimIndent())

        val config = ConfigLoader.loadAll(tomlPath = toml)

        assertNotNull(config.web)
        assertEquals(9999, config.web.port, "Web port should be read from TOML")
        assertEquals("0.0.0.0", config.web.host)
    }

    @Test
    fun `loadAll should parse all sections from complex TOML with glob keys`() {
        val watchDir = tempDir.resolve("project").also { it.toFile().mkdirs() }
        val toml = tempDir.resolve("fusionagent.toml")
        toml.toFile().writeText("""
            [orchestrator.server]
            host = "10.0.0.1"
            port = 4000
            transport = "HTTP"

            [web]
            host = "127.0.0.1"
            port = 8082
            staticPath = "assets"
            autoLaunchBrowser = false

            [web.cors]
            enabled = false
            allowedOrigins = ["http://localhost:8082", "http://10.0.0.1:8082"]

            [context]
            enabled = true

            [context.storage]
            db_path = "./context.duckdb"

            [context.watcher]
            enabled = true
            debounce_ms = 1000
            watch_paths = ["${watchDir.toAbsolutePath()}"]
            deletion_sweep_interval_ms = 120000
            ignore_patterns = [".git/", "build/", "node_modules/"]
            use_gitignore = true
            use_contextignore = true

            [context.indexing]
            batch_size = 512
            allowed_extensions = [".kt", ".java", ".py", ".ts", ".md"]
            skip_patterns = ["*.test.ts", "*.min.js"]
            max_file_size_mb = 100
            warn_file_size_mb = 5
            size_exceptions = []
            follow_symlinks = false
            max_symlink_depth = 3
            binary_detection = "all"
            binary_threshold = 30

            [context.embedding]
            model = "sentence-transformers/all-MiniLM-L6-v2"
            batch_size = 64
            normalize = true
            cache_enabled = false

            [context.chunking]
            overlap_enabled = true
            overlap_percent = 20

            [context.chunking.markdown]
            max_tokens = 800
            split_by_headings = true
            preserve_code_blocks = true

            [context.chunking.python]
            max_tokens = 500
            split_by_function = true
            overlap_percent = 10
            preserve_docstrings = true

            [context.chunking.kotlin]
            max_tokens = 500
            split_by_class = true
            split_by_function = true
            preserve_kdoc = true

            [context.query]
            default_k = 15
            mmr_lambda = 0.6
            min_score_threshold = 0.2
            rerank_enabled = true
            use_optimizer_in_tool = false
            neighbor_window = 1
            second_stage_rerank_enabled = false
            second_stage_top_n = 50
            second_stage_blend_weight = 0.4
            query_expansion_enabled = true
            hyde_enabled = false
            max_expansion_terms = 5

            [context.query.synonyms]

            [context.query.boosts.file_type_penalties]
            pdf = 0.4
            doc = 0.5
            docx = 0.5
            md = 0.8

            [context.query.boosts.file_pattern_penalties]
            "**/docs/**" = 0.6
            "**/README*" = 0.8

            [context.query.boosts.chunk_kind_boosts]
            CODE_CLASS = 1.4
            CODE_FUNCTION = 1.3
            PARAGRAPH = 0.6

            [context.query.graph]
            enabled = true
            max_depth = 3
            decay_factor = 0.8
            max_graph_results = 15
            default_link_score = 0.9
            min_propagated_score = 0.05

            [context.budget]
            default_max_tokens = 2000
            reserve_for_prompt = 600
            warn_threshold_percent = 75

            [context.providers.full_text]
            enabled = true
            weight = 0.2

            [context.providers.semantic]
            enabled = true
            weight = 0.5

            [context.providers.symbol]
            enabled = true
            weight = 0.3
            index_ast = true

            [context.providers.git_history]
            enabled = false
            weight = 0.2
            max_commits = 200

            [context.providers.exact_match]
            enabled = true
            weight = 0.1

            [context.providers.hybrid]
            enabled = false
            weight = 0.5
            combines = ["semantic", "full_text", "symbol"]
            fusion_strategy = "rrf"

            [context.metrics]
            enabled = true
            track_latency = true
            track_token_usage = true
            track_cache_hits = true
            export_interval_minutes = 10

            [context.bootstrap]
            enabled = true
            parallel_workers = 4
            batch_size = 256
            priority_extensions = [".kt", ".py"]
            max_initial_files = 0
            fail_fast = false
            show_progress = true
            progress_interval_seconds = 15

            [agents.claude-code]
            type = "CLAUDE_CODE"
            name = "Claude"

            [agents.codex-cli]
            type = "CODEX_CLI"
            name = "Codex"

            [agents.gemini]
            type = "GEMINI"
            name = "Gemini"
        """.trimIndent())

        val config = ConfigLoader.loadAll(tomlPath = toml)

        // --- Orchestrator server ---
        assertEquals("10.0.0.1", config.orchestrator.server.host)
        assertEquals(4000, config.orchestrator.server.port)
        assertEquals(Transport.HTTP, config.orchestrator.server.transport)

        // --- Web ---
        assertEquals("127.0.0.1", config.web.host)
        assertEquals(8082, config.web.port)
        assertEquals("assets", config.web.staticPath)
        assertEquals(false, config.web.autoLaunchBrowser)
        assertEquals(false, config.web.corsEnabled)
        assertNotNull(config.web.corsAllowedOrigins)
        assertEquals(2, config.web.corsAllowedOrigins!!.size)
        assertTrue(config.web.corsAllowedOrigins!!.contains("http://localhost:8082"))
        assertTrue(config.web.corsAllowedOrigins!!.contains("http://10.0.0.1:8082"))

        // --- Context ---
        assertTrue(config.context.enabled)

        // Context watcher
        assertEquals(true, config.context.watcher.enabled)
        assertEquals(1000L, config.context.watcher.debounceMs)
        assertTrue(config.context.watcher.watchPaths.any { it.contains("project") })

        // Context indexing
        assertTrue(config.context.indexing.allowedExtensions.contains(".kt"))
        assertTrue(config.context.indexing.allowedExtensions.contains(".py"))
        assertEquals(100, config.context.indexing.maxFileSizeMb)

        // Context embedding
        assertEquals(64, config.context.embedding.batchSize)
        assertEquals(true, config.context.embedding.normalize)
        assertEquals(false, config.context.embedding.cacheEnabled)

        // Context chunking
        assertEquals(true, config.context.chunking.overlapEnabled)
        assertEquals(20, config.context.chunking.overlapPercent)

        // Context query
        assertEquals(15, config.context.query.defaultK)
        assertEquals(0.6, config.context.query.mmrLambda)
        assertEquals(0.2, config.context.query.minScoreThreshold)
        assertEquals(true, config.context.query.rerankEnabled)
        assertEquals(1, config.context.query.neighborWindow)
        assertEquals(true, config.context.query.queryExpansionEnabled)
        assertEquals(5, config.context.query.maxExpansionTerms)

        // Context query boosts
        assertTrue(config.context.query.boosts.fileTypePenalties.containsKey("pdf"))
        assertEquals(0.4, config.context.query.boosts.fileTypePenalties["pdf"])
        assertTrue(config.context.query.boosts.chunkKindBoosts.containsKey("CODE_CLASS"))
        assertEquals(1.4, config.context.query.boosts.chunkKindBoosts["CODE_CLASS"])
        // file_pattern_penalties with glob keys (e.g. "**/docs/**") — verify they're parsed
        assertTrue(config.context.query.boosts.filePatternPenalties.isNotEmpty(),
            "filePatternPenalties should not be empty, got: ${config.context.query.boosts.filePatternPenalties}")
        // Verify the actual glob key is present (toml4j parses quoted keys correctly)
        val patternKeys = config.context.query.boosts.filePatternPenalties.keys
        assertTrue(patternKeys.any { it.contains("docs") },
            "filePatternPenalties should contain a docs glob pattern, got keys: $patternKeys")

        // Context query graph
        assertEquals(true, config.context.query.graph.enabled)
        assertEquals(3, config.context.query.graph.maxDepth)
        assertEquals(0.8, config.context.query.graph.decayFactor)
        assertEquals(15, config.context.query.graph.maxGraphResults)

        // Context budget
        assertEquals(2000, config.context.budget.defaultMaxTokens)
        assertEquals(600, config.context.budget.reserveForPrompt)
        assertEquals(75, config.context.budget.warnThresholdPercent)

        // Context providers
        val providers = config.context.providers
        assertTrue(providers.containsKey("full_text"))
        assertEquals(true, providers["full_text"]!!.enabled)
        assertEquals(0.2, providers["full_text"]!!.weight)
        assertTrue(providers.containsKey("semantic"))
        assertEquals(0.5, providers["semantic"]!!.weight)
        assertTrue(providers.containsKey("exact_match"))
        assertEquals(true, providers["exact_match"]!!.enabled)

        // Context metrics
        assertEquals(true, config.context.metrics.enabled)
        assertEquals(10, config.context.metrics.exportIntervalMinutes)

        // Context bootstrap
        assertEquals(4, config.context.bootstrap.parallelWorkers)
        assertEquals(256, config.context.bootstrap.batchSize)
        assertEquals(15, config.context.bootstrap.progressIntervalSeconds)

        // --- Agents ---
        assertEquals(3, config.agents.size)
        val agentIds = config.agents.map { it.id.value }
        assertTrue(agentIds.contains("claude-code"))
        assertTrue(agentIds.contains("codex-cli"))
        assertTrue(agentIds.contains("gemini"))

        val claude = config.agents.first { it.id.value == "claude-code" }
        assertEquals(com.orchestrator.domain.AgentType.CLAUDE_CODE, claude.type)
        assertEquals("Claude", claude.config.name)
    }
}
