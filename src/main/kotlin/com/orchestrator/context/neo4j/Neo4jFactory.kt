package com.orchestrator.context.neo4j

import com.orchestrator.context.config.Neo4jConfig
import mu.KotlinLogging
import java.nio.file.Paths

private val log = KotlinLogging.logger {}

/**
 * Factory for creating Neo4j drivers based on configuration.
 * Supports both embedded (in-process) and server (external) modes.
 */
object Neo4jFactory {
    
    fun createDriver(config: Neo4jConfig): Neo4jDriverInterface? {
        if (!config.enabled) {
            log.info { "Neo4j is disabled" }
            return null
        }
        
        return try {
            when (config.mode.lowercase()) {
                "embedded" -> {
                    log.info { "Starting embedded Neo4j at ${config.dataDir}" }
                    EmbeddedNeo4jDriver(Paths.get(config.dataDir), config)
                }
                "server" -> {
                    log.info { "Connecting to Neo4j server at ${config.uri}" }
                    Neo4jDriver(config)
                }
                else -> {
                    log.warn { "Unknown Neo4j mode: ${config.mode}, defaulting to embedded" }
                    EmbeddedNeo4jDriver(Paths.get(config.dataDir), config)
                }
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to initialize Neo4j: ${e.message}" }
            null
        }
    }
}
