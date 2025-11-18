package com.orchestrator.context.watcher

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.indexing.IncrementalIndexer
import com.orchestrator.context.neo4j.UnifiedSynchronousIndexer
import com.orchestrator.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.Closeable
import java.nio.file.Path

/**
 * Unified file watcher that coordinates file system changes with both Neo4j and DuckDB indexing.
 * Routes file events to appropriate indexers based on file type and Neo4j enablement.
 */
class UnifiedFileWatcher(
    private val scope: CoroutineScope,
    private val fileWatcher: FileWatcher,
    private val incrementalIndexer: IncrementalIndexer,
    private val unifiedIndexer: UnifiedSynchronousIndexer?,
    private val config: ContextConfig
) : Closeable {

    private val log = Logger.logger("com.orchestrator.context.watcher.UnifiedFileWatcher")
    private var eventJob: Job? = null
    private val neo4jEnabled = config.neo4j?.enabled == true

    fun start() {
        fileWatcher.start()
        eventJob = scope.launch {
            fileWatcher.events.collect { event ->
                handleEvent(event)
            }
        }
        log.info("UnifiedFileWatcher started (Neo4j: {})", if (neo4jEnabled) "enabled" else "disabled")
    }

    private suspend fun handleEvent(event: FileWatchEvent) {
        when (event.kind) {
            FileWatchEvent.Kind.CREATED, FileWatchEvent.Kind.MODIFIED -> handleCreateOrModify(event)
            FileWatchEvent.Kind.DELETED -> handleDelete(event)
            FileWatchEvent.Kind.OVERFLOW -> handleOverflow(event)
        }
    }

    private suspend fun handleCreateOrModify(event: FileWatchEvent) {
        if (event.isDirectory) return

        try {
            val result = incrementalIndexer.updateAsync(listOf(event.path))
            log.debug("Indexed {} via IncrementalIndexer: {} new, {} modified", 
                event.path, result.newCount, result.modifiedCount)
        } catch (e: Exception) {
            log.error("Failed to index {}: {}", event.path, e.message, e)
        }
    }

    private suspend fun handleDelete(event: FileWatchEvent) {
        if (event.isDirectory) return

        try {
            incrementalIndexer.updateAsync(listOf(event.path))
            log.debug("Deleted {} via IncrementalIndexer", event.path)
        } catch (e: Exception) {
            log.error("Failed to delete {}: {}", event.path, e.message, e)
        }
    }

    private fun handleOverflow(event: FileWatchEvent) {
        log.warn("File watcher overflow detected for root: {}. Consider full rescan.", event.root)
    }

    override fun close() {
        eventJob?.cancel()
        fileWatcher.close()
        log.info("UnifiedFileWatcher stopped")
    }

    companion object {
        fun create(
            scope: CoroutineScope,
            fileWatcher: FileWatcher,
            incrementalIndexer: IncrementalIndexer,
            unifiedIndexer: UnifiedSynchronousIndexer?,
            config: ContextConfig
        ): UnifiedFileWatcher {
            return UnifiedFileWatcher(scope, fileWatcher, incrementalIndexer, unifiedIndexer, config)
        }
    }
}
