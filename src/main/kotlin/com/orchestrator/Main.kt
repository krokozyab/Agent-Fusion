package com.orchestrator

import com.orchestrator.config.ConfigLoader
import com.orchestrator.core.AgentRegistry
import com.orchestrator.core.EventBus
import com.orchestrator.mcp.McpServerImpl
import com.orchestrator.modules.context.ContextModule
import com.orchestrator.modules.metrics.MetricsModule
import com.orchestrator.context.storage.ContextDatabase
import com.orchestrator.context.watcher.WatcherDaemon
import com.orchestrator.context.watcher.WatcherRegistry
import com.orchestrator.context.indexing.IncrementalIndexer
import com.orchestrator.context.indexing.ChangeDetector
import com.orchestrator.context.indexing.BatchIndexer
import com.orchestrator.context.indexing.FileIndexer
import com.orchestrator.context.embedding.LocalEmbedder
import com.orchestrator.storage.Database
import com.orchestrator.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

private val log = Logger.logger("com.orchestrator.Main")

class Main {
    private var mcpServer: McpServerImpl? = null
    private var metricsModule: MetricsModule? = null
    private var watcherDaemon: WatcherDaemon? = null
    private var webServerModule: com.orchestrator.web.WebServerModule? = null
    private val watcherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    fun start(args: Array<String>) {
        val cliArgs = parseArgs(args)
        
        log.info("Starting Codex to Claude Orchestrator...")
        
        try {
            // Load configuration
            log.info("Loading configuration...")
            val config = loadConfiguration(cliArgs)
            log.info("Configuration loaded: server=${config.orchestrator.server.host}:${config.orchestrator.server.port}")

            // Configure context module
            ContextModule.configure(config.context)
            log.info("Context module configured: enabled=${config.context.enabled}, mode=${config.context.mode}")

            // Initialize context database
            log.info("Initializing context database...")
            ContextDatabase.initialize(config.context.storage)
            log.info("Context database initialized at ${config.context.storage.dbPath}")

            // Initialize and start file watcher
            if (config.context.watcher.enabled) {
                log.info("Initializing file watcher...")
                val watcher = initializeWatcher(config)
                watcherDaemon = watcher
                WatcherRegistry.register(watcher)

                val indexStatus = ContextModule.getIndexStatus()
                val hasExistingIndex = indexStatus.totalFiles > 0
                watcher.start(skipStartupScan = hasExistingIndex)
                log.info("File watcher started, monitoring ${config.context.watcher.watchPaths}")
            } else {
                log.info("File watcher disabled by configuration")
            }

            // Initialize database
            log.info("Initializing database...")
            initializeDatabase()
            log.info("Database initialized successfully")

            // Load active tasks from database
            log.info("Loading active tasks from database...")
            val activeTasksCount = loadActiveTasks()
            log.info("Loaded $activeTasksCount active task(s) from database")
            
            // Initialize agent registry
            log.info("Loading agent registry...")
            val agentRegistry = initializeAgentRegistry(cliArgs)
            log.info("Loaded ${agentRegistry.getAllAgents().size} agents")
            
            // Initialize event bus
            log.info("Initializing event bus...")
            val eventBus = EventBus.global
            
            // Initialize metrics module
            log.info("Initializing metrics module...")
            val metrics = initializeMetrics(eventBus)
            metricsModule = metrics
            log.info("Metrics module initialized")

            // Start web dashboard server
            runCatching {
                log.info("Starting web dashboard server on ${config.web.host}:${config.web.port} (autoLaunchBrowser=${config.web.autoLaunchBrowser})")
                val webServer = com.orchestrator.web.WebServer.create(config.web)
                webServer.start()
                webServerModule = webServer
                log.info("Web dashboard server started")
            }.onFailure { throwable ->
                log.warn("Failed to start web dashboard server: ${throwable.message}", throwable)
            }
            
            // Start MCP server
            log.info("Starting MCP server...")
            val server = McpServerImpl(config.orchestrator, agentRegistry)
            server.start()
            mcpServer = server
            log.info("MCP server started on ${config.orchestrator.server.host}:${config.orchestrator.server.port}")
            
            // Setup shutdown hook
            setupShutdownHook()
            
            log.info("Orchestrator started successfully")
            log.info("Press Ctrl+C to stop")
            
        } catch (e: Exception) {
            log.error("Failed to start orchestrator: ${e.message}", e)
            shutdown()
            exitProcess(1)
        }
    }
    
    private fun parseArgs(args: Array<String>): CliArgs {
        var configPath: String? = null
        var agentsPath: String? = null
        var contextPath: String? = null
        var help = false
        
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "-c", "--config" -> {
                    if (i + 1 < args.size) {
                        configPath = args[++i]
                    } else {
                        throw IllegalArgumentException("Missing value for ${args[i]}")
                    }
                }
                "-a", "--agents" -> {
                    if (i + 1 < args.size) {
                        agentsPath = args[++i]
                    } else {
                        throw IllegalArgumentException("Missing value for ${args[i]}")
                    }
                }
                "-x", "--context-config" -> {
                    if (i + 1 < args.size) {
                        contextPath = args[++i]
                    } else {
                        throw IllegalArgumentException("Missing value for ${args[i]}")
                    }
                }
                "-h", "--help" -> help = true
                else -> throw IllegalArgumentException("Unknown argument: ${args[i]}")
            }
            i++
        }
        
        if (help) {
            printHelp()
            exitProcess(0)
        }
        
        return CliArgs(configPath, agentsPath, contextPath)
    }
    
    private fun printHelp() {
        println("""
            Codex to Claude Orchestrator
            
            Usage: orchestrator [options]
            
            Options:
              -c, --config <path>        Path to application.conf (default: classpath resource)
              -a, --agents <path>        Path to agents.toml (default: config/agents.toml)
              -x, --context-config <path> Path to context.toml (default: config/context.toml)
              -h, --help                 Show this help message
            
            Environment Variables:
              ORCHESTRATOR_HOST     Server host (default: localhost)
              ORCHESTRATOR_PORT     Server port (default: 8080)
              DATABASE_PATH         Database file path (default: data/orchestrator.duckdb)
        """.trimIndent())
    }
    
    private fun loadConfiguration(args: CliArgs): ConfigLoader.ApplicationConfig {
        return try {
            val tomlPath = args.agentsPath?.let { Path.of(it) } ?: Path.of("fusionagent.toml")
            ConfigLoader.loadAll(args.configPath, tomlPath)
        } catch (e: Exception) {
            log.error("Failed to load configuration: ${e.message}")
            throw e
        }
    }

    
    private fun initializeDatabase() {
        try {
            Database.withConnection { /* trigger initialization */ }
            if (!Database.isHealthy()) {
                throw IllegalStateException("Database health check failed")
            }
        } catch (e: Exception) {
            log.error("Failed to initialize database: ${e.message}")
            throw e
        }
    }

    private fun loadActiveTasks(): Int {
        return try {
            val taskRepository = com.orchestrator.storage.repositories.TaskRepository
            val activeTasks = taskRepository.findAllActive()

            // Restore StateMachine state for active tasks
            val stateMachine = com.orchestrator.core.StateMachine
            activeTasks.forEach { task ->
                // Record current state in StateMachine
                if (task.status != com.orchestrator.domain.TaskStatus.PENDING) {
                    // For non-PENDING tasks, record the transition from PENDING to current status
                    // This helps maintain transition history
                    stateMachine.transition(
                        task.id,
                        com.orchestrator.domain.TaskStatus.PENDING,
                        task.status,
                        mapOf("restored" to "true", "restoredAt" to java.time.Instant.now().toString())
                    )
                }
            }

            log.info("Restored StateMachine state for ${activeTasks.size} active tasks")
            activeTasks.size
        } catch (e: Exception) {
            log.error("Failed to load active tasks: ${e.message}", e)
            0
        }
    }
    
    private fun initializeAgentRegistry(args: CliArgs): AgentRegistry {
        return try {
            val agentsPath = args.agentsPath?.let { Path.of(it) } ?: Path.of("config/agents.toml")
            AgentRegistry.fromConfig(agentsPath)
        } catch (e: Exception) {
            log.error("Failed to initialize agent registry: ${e.message}")
            throw e
        }
    }
    
    private fun initializeMetrics(eventBus: EventBus): MetricsModule {
        return try {
            val metrics = MetricsModule.global
            metrics.setEventBus(eventBus)
            metrics.configureAlerts()
            metrics.start()
            metrics
        } catch (e: Exception) {
            log.error("Failed to initialize metrics module: ${e.message}")
            throw e
        }
    }

    private fun initializeWatcher(config: ConfigLoader.ApplicationConfig): WatcherDaemon {
        return try {
            val projectRoot = Paths.get("").toAbsolutePath()
            val embedder = LocalEmbedder(
                modelPath = null, // Will use default path
                modelName = config.context.embedding.model,
                dimension = config.context.embedding.dimension,
                normalize = config.context.embedding.normalize,
                maxBatchSize = config.context.embedding.batchSize
            )
            val fileIndexer = FileIndexer(
                embedder = embedder,
                projectRoot = projectRoot,
                embeddingBatchSize = config.context.embedding.batchSize,
                maxFileSizeMb = config.context.indexing.maxFileSizeMb,
                warnFileSizeMb = config.context.indexing.warnFileSizeMb
            )
            val changeDetector = ChangeDetector(projectRoot)
            val batchIndexer = BatchIndexer(fileIndexer)
            val incrementalIndexer = IncrementalIndexer(changeDetector, batchIndexer)

            WatcherDaemon(
                scope = watcherScope,
                projectRoot = projectRoot,
                watcherConfig = config.context.watcher,
                indexingConfig = config.context.indexing,
                incrementalIndexer = incrementalIndexer,
                onUpdate = { result ->
                    log.info(
                        "File watcher indexed: new=${result.newCount}, modified=${result.modifiedCount}, " +
                        "deleted=${result.deletedCount}, failures=${result.indexingFailures + result.deletionFailures}"
                    )
                },
                onError = { error ->
                    log.error("File watcher error: ${error.message}", error)
                }
            )
        } catch (e: Exception) {
            log.error("Failed to initialize file watcher: ${e.message}")
            throw e
        }
    }
    
    private fun setupShutdownHook() {
        Runtime.getRuntime().addShutdownHook(Thread {
            log.info("Shutdown signal received")
            shutdown()
        })
    }
    
    private fun shutdown() {
        log.info("Shutting down orchestrator...")

        try {
            mcpServer?.stop()
            log.info("MCP server stopped")
        } catch (e: Exception) {
            log.error("Error stopping MCP server: ${e.message}")
        }

        try {
            metricsModule?.stop()
            log.info("Metrics module stopped")
        } catch (e: Exception) {
            log.error("Error stopping metrics module: ${e.message}")
        }

        try {
            webServerModule?.stop()
            log.info("Web dashboard server stopped")
        } catch (e: Exception) {
            log.error("Error stopping web dashboard server: ${e.message}")
        }

        try {
            watcherDaemon?.let { daemon ->
                WatcherRegistry.unregister(daemon)
                daemon.stop()
            }
            log.info("File watcher stopped")
        } catch (e: Exception) {
            log.error("Error stopping file watcher: ${e.message}")
        }

        try {
            ContextDatabase.shutdown()
            log.info("Context database closed")
        } catch (e: Exception) {
            log.error("Error closing context database: ${e.message}")
        }

        try {
            Database.shutdown()
            log.info("Database connection closed")
        } catch (e: Exception) {
            log.error("Error closing database: ${e.message}")
        }

        log.info("Orchestrator shutdown complete")
    }
    
    private data class CliArgs(
        val configPath: String?,
        val agentsPath: String?,
        val contextPath: String?
    )
}

fun main(args: Array<String>) {
    val app = Main()
    app.start(args)
    
    // Keep main thread alive
    Thread.currentThread().join()
}
