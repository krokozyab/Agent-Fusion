package com.orchestrator.context.tools

import com.orchestrator.config.ConfigLoader
import com.orchestrator.context.bootstrap.BootstrapProgress
import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.storage.ContextDatabase
import com.orchestrator.mcp.tools.RebuildContextTool
import com.orchestrator.modules.context.ContextModule
import com.orchestrator.context.discovery.DirectoryScanner
import com.orchestrator.context.discovery.PathFilter
import com.orchestrator.context.discovery.ExtensionFilter
import com.orchestrator.context.discovery.PathValidator
import com.orchestrator.context.discovery.SymlinkHandler
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * Lightweight CLI entrypoint to trigger a full context rebuild using the configured settings.
 * Useful when the web dashboard is unavailable or when running from automation.
 */
fun main(args: Array<String>) = runBlocking {
    val validateOnly = args.contains("--validate")
    val async = args.contains("--async")
    val appConfig = ConfigLoader.loadAll()
    val contextConfig: ContextConfig = appConfig.context

    println("[RebuildContextCli] Using database at ${contextConfig.storage.dbPath}")

    ContextModule.configure(contextConfig)
    ContextDatabase.initialize(contextConfig.storage)

    val tool = RebuildContextTool(contextConfig)
    if (validateOnly) {
        val projectRoot = Path.of(".").toAbsolutePath().normalize()
        val filter = PathFilter.fromSources(
            root = projectRoot,
            configPatterns = contextConfig.watcher.ignorePatterns,
            includeGitignore = contextConfig.watcher.useGitignore,
            includeContextignore = contextConfig.watcher.useContextignore,
            includeDockerignore = true
        )
        val extensionFilter = ExtensionFilter.fromConfig(
            allowlist = contextConfig.indexing.allowedExtensions,
            blocklist = contextConfig.indexing.blockedExtensions
        )
        val validator = PathValidator(
            watchPaths = listOf(projectRoot),
            pathFilter = filter,
            extensionFilter = extensionFilter,
            symlinkHandler = SymlinkHandler(listOf(projectRoot), contextConfig.indexing),
            indexingConfig = contextConfig.indexing
        )
        val scanner = DirectoryScanner(validator)
        val scanned = scanner.scan(listOf(projectRoot))
        println("[RebuildContextCli] DirectoryScanner would index ${scanned.size} files")
        val output = projectRoot.resolve("build/scanner-list.txt")
        output.parent?.toFile()?.mkdirs()
        output.toFile().printWriter().use { writer ->
            scanned
                .sortedBy { projectRoot.relativize(it).toString() }
                .forEach { path ->
                    writer.println(projectRoot.relativize(path))
                }
        }
        println("[RebuildContextCli] Wrote full listing to ${output}")
        scanned.take(10).forEach { println("  - ${projectRoot.relativize(it)}") }
    }
    val result = tool.execute(
        RebuildContextTool.Params(
            confirm = !validateOnly,
            async = async,
            validateOnly = validateOnly,
            parallelism = contextConfig.bootstrap.parallelWorkers
        ),
        onProgress = ::logProgress
    )

    println("[RebuildContextCli] Status: ${result.status} - ${result.message ?: "completed"}")
    if (result.totalFiles != null) {
        println("[RebuildContextCli] Total files counted: ${result.totalFiles}")
    }
    println(
        "[RebuildContextCli] Processed=${result.processedFiles ?: "?"} " +
            "Successful=${result.successfulFiles ?: "?"} Failed=${result.failedFiles ?: "?"}"
    )

    ContextDatabase.shutdown()
}

private fun logProgress(progress: BootstrapProgress) {
    val percent = if (progress.totalFiles > 0) {
        (progress.processedFiles * 100) / progress.totalFiles
    } else {
        0
    }
    println(
        "[RebuildContextCli] Progress: ${progress.processedFiles}/${progress.totalFiles} " +
            "(${percent}%) last='${progress.lastProcessedFile}'"
    )
}
