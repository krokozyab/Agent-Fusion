package com.orchestrator.context.neo4j

import com.orchestrator.context.config.Neo4jConfig
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Config
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.Session
import org.neo4j.driver.SessionConfig
import java.util.concurrent.TimeUnit

/**
 * Neo4j connection manager for graph database operations.
 * Manages driver lifecycle and session creation.
 */
class Neo4jDriver(private val config: Neo4jConfig) : Neo4jDriverInterface {
    private val driver: Driver

    init {
        driver = GraphDatabase.driver(
            config.uri,
            AuthTokens.basic(config.username, config.password),
            Config.builder()
                .withMaxConnectionPoolSize(config.maxConnectionPoolSize)
                .withConnectionTimeout(config.connectionTimeoutMs, TimeUnit.MILLISECONDS)
                .build()
        )
    }

    override fun session(): Session = driver.session(SessionConfig.forDatabase(config.database))

    override fun <T> executeInTransaction(
        query: String,
        parameters: Map<String, *>,
        block: (Map<String, Any>) -> T
    ): List<T> {
        return driver.session().use { session ->
            session.writeTransaction { tx ->
                val result = tx.run(query, parameters)
                val results = mutableListOf<T>()
                while (result.hasNext()) {
                    val record = result.next()
                    results.add(block(record.asMap()))
                }
                results
            }
        }
    }

    override fun close() = driver.close()
}
