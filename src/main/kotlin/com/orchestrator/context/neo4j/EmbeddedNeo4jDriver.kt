package com.orchestrator.context.neo4j

import com.orchestrator.context.config.Neo4jConfig
import mu.KotlinLogging
import org.neo4j.dbms.api.DatabaseManagementService
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder
import org.neo4j.driver.Session
import org.neo4j.driver.internal.InternalSession
import org.neo4j.graphdb.GraphDatabaseService
import org.neo4j.graphdb.Transaction
import java.nio.file.Path

private val log = KotlinLogging.logger {}

/**
 * Embedded Neo4j driver that runs in-process (no separate server needed).
 * Automatically starts/stops with the application.
 */
class EmbeddedNeo4jDriver(
    private val dataDir: Path,
    private val config: Neo4jConfig
) : Neo4jDriverInterface {

    private val managementService: DatabaseManagementService
    private val database: GraphDatabaseService

    init {
        // Normalize the path to avoid Neo4j's "not a normalized path" error
        val normalizedDir = dataDir.toAbsolutePath().normalize()
        log.info { "Starting embedded Neo4j at $normalizedDir" }

        managementService = DatabaseManagementServiceBuilder(normalizedDir)
            .build()
        
        database = managementService.database(config.database)
        
        log.info { "Embedded Neo4j started successfully" }
    }
    
    override fun session(): Session {
        throw UnsupportedOperationException("Embedded Neo4j uses executeInTransaction() instead of Bolt sessions")
    }

    override fun <T> executeInTransaction(
        query: String,
        parameters: Map<String, *>,
        block: (Map<String, Any>) -> T
    ): List<T> {
        return database.beginTx().use { tx ->
            try {
                val result = tx.execute(query, parameters)
                val results = mutableListOf<T>()
                while (result.hasNext()) {
                    val record = result.next()
                    results.add(block(record))
                }
                tx.commit()
                results
            } catch (e: Exception) {
                tx.rollback()
                throw e
            }
        }
    }

    fun transaction(): Transaction = database.beginTx()

    fun <T> executeInTransactionNative(block: (Transaction) -> T): T {
        return transaction().use { tx ->
            val result = block(tx)
            tx.commit()
            result
        }
    }

    override fun close() {
        log.info { "Shutting down embedded Neo4j" }
        managementService.shutdown()
    }
}
