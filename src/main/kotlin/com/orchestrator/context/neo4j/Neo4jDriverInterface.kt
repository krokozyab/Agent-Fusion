package com.orchestrator.context.neo4j

import org.neo4j.driver.Session

interface Neo4jDriverInterface : AutoCloseable {
    fun session(): Session

    /**
     * Execute a Cypher query in a transaction.
     * Handles both Bolt (server) and embedded modes transparently.
     */
    fun <T> executeInTransaction(query: String, parameters: Map<String, *> = emptyMap<String, Any>(), block: (Map<String, Any>) -> T): List<T>
}
