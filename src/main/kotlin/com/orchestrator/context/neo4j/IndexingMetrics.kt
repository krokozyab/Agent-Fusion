package com.orchestrator.context.neo4j

import org.neo4j.driver.Session
import java.time.Instant

/**
 * Tracks indexing metrics for Neo4j and DuckDB dual-storage system.
 */
class IndexingMetrics(
    private val neo4jDriver: Neo4jDriver
) {
    
    suspend fun getDocumentIndexingStatus(): DocumentIndexStatus {
        return neo4jDriver.session().use { session ->
            val stats = getNodeCounts(session)
            
            DocumentIndexStatus(
                totalDocuments = stats["Document"] ?: 0,
                totalSections = stats["Section"] ?: 0,
                totalParagraphs = stats["Paragraph"] ?: 0,
                totalCodeFiles = stats["File"] ?: 0,
                totalClasses = stats["Class"] ?: 0,
                totalMethods = stats["Method"] ?: 0,
                totalFunctions = stats["Function"] ?: 0,
                totalChunkLinks = getChunkLinkCount(session),
                orphanedChunks = getOrphanedChunkCount(session),
                lastUpdated = Instant.now()
            )
        }
    }
    
    suspend fun getIndexingHealth(): IndexHealth {
        return neo4jDriver.session().use { session ->
            val connected = checkConnection(session)
            val stats = getNodeCounts(session)
            val orphans = getOrphanedChunkCount(session)
            
            val totalNodes = stats.values.sum()
            val hasOrphans = orphans > 0
            
            val status = when {
                !connected -> HealthStatus.CRITICAL
                hasOrphans -> HealthStatus.DEGRADED
                totalNodes == 0 -> HealthStatus.DEGRADED
                else -> HealthStatus.HEALTHY
            }
            
            IndexHealth(
                status = status,
                connected = connected,
                totalNodes = totalNodes,
                orphanedChunks = orphans,
                message = when (status) {
                    HealthStatus.HEALTHY -> "All systems operational"
                    HealthStatus.DEGRADED -> if (hasOrphans) "Found $orphans orphaned chunks" else "No data indexed"
                    HealthStatus.CRITICAL -> "Neo4j connection failed"
                }
            )
        }
    }
    
    private fun getNodeCounts(session: Session): Map<String, Int> {
        val labels = listOf("Document", "Section", "Paragraph", "File", "Class", "Method", "Function", "Chunk")
        return labels.associateWith { label ->
            session.run("MATCH (n:$label) RETURN count(n) as count")
                .single()
                .get("count")
                .asInt()
        }
    }
    
    private fun getChunkLinkCount(session: Session): Int {
        return session.run(
            """
            MATCH ()-[:HAS_CHUNK]->(c:Chunk)
            RETURN count(DISTINCT c) as count
            """.trimIndent()
        ).single().get("count").asInt()
    }
    
    private fun getOrphanedChunkCount(session: Session): Int {
        return session.run(
            """
            MATCH (c:Chunk)
            WHERE NOT exists((c)<-[:HAS_CHUNK]-())
            RETURN count(c) as count
            """.trimIndent()
        ).single().get("count").asInt()
    }
    
    private fun checkConnection(session: Session): Boolean {
        return runCatching {
            session.run("RETURN 1").consume()
            true
        }.getOrElse { false }
    }
}

data class DocumentIndexStatus(
    val totalDocuments: Int,
    val totalSections: Int,
    val totalParagraphs: Int,
    val totalCodeFiles: Int,
    val totalClasses: Int,
    val totalMethods: Int,
    val totalFunctions: Int,
    val totalChunkLinks: Int,
    val orphanedChunks: Int,
    val lastUpdated: Instant
)

data class IndexHealth(
    val status: HealthStatus,
    val connected: Boolean,
    val totalNodes: Int,
    val orphanedChunks: Int,
    val message: String
)

enum class HealthStatus {
    HEALTHY,
    DEGRADED,
    CRITICAL
}
