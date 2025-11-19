package com.orchestrator.context.neo4j

data class StructuralMatch(
    val nodeType: String,
    val nodeId: String,
    val name: String,
    val filePath: String,
    val startLine: Int?,
    val endLine: Int?,
    val score: Double
)

class Neo4jQueryProvider(private val driver: Neo4jDriverInterface) {

    fun findClassesByName(query: String, limit: Int = 10): List<StructuralMatch> {
        return driver.executeInTransaction(
            """
            MATCH (f:File)-[:CONTAINS_CLASS]->(c:Class)
            WHERE c.name CONTAINS ${'$'}query OR c.qualifiedName CONTAINS ${'$'}query
            RETURN 'Class' as nodeType, c.id as nodeId, c.name as name,
                   f.path as filePath, c.startLine as startLine, c.endLine as endLine
            LIMIT ${'$'}limit
            """.trimIndent(),
            mapOf("query" to query, "limit" to limit)
        ) { record ->
            StructuralMatch(
                nodeType = record["nodeType"] as String,
                nodeId = record["nodeId"] as String,
                name = record["name"] as String,
                filePath = record["filePath"] as String,
                startLine = record["startLine"] as? Int,
                endLine = record["endLine"] as? Int,
                score = 1.0
            )
        }
    }

    fun findMethodsByName(query: String, limit: Int = 10): List<StructuralMatch> {
        return driver.executeInTransaction(
            """
            MATCH (f:File)-[:CONTAINS_CLASS]->(c:Class)-[:HAS_METHOD]->(m:Method)
            WHERE m.name CONTAINS ${'$'}query
            RETURN 'Method' as nodeType, m.id as nodeId, m.name as name,
                   f.path as filePath, m.startLine as startLine, m.endLine as endLine
            LIMIT ${'$'}limit
            """.trimIndent(),
            mapOf("query" to query, "limit" to limit)
        ) { record ->
            StructuralMatch(
                nodeType = record["nodeType"] as String,
                nodeId = record["nodeId"] as String,
                name = record["name"] as String,
                filePath = record["filePath"] as String,
                startLine = record["startLine"] as? Int,
                endLine = record["endLine"] as? Int,
                score = 1.0
            )
        }
    }

    fun findFunctionsByName(query: String, limit: Int = 10): List<StructuralMatch> {
        return driver.executeInTransaction(
            """
            MATCH (f:File)-[:CONTAINS_FUNCTION]->(fn:Function)
            WHERE fn.name CONTAINS ${'$'}query
            RETURN 'Function' as nodeType, fn.id as nodeId, fn.name as name,
                   f.path as filePath, fn.startLine as startLine, fn.endLine as endLine
            LIMIT ${'$'}limit
            """.trimIndent(),
            mapOf("query" to query, "limit" to limit)
        ) { record ->
            StructuralMatch(
                nodeType = record["nodeType"] as String,
                nodeId = record["nodeId"] as String,
                name = record["name"] as String,
                filePath = record["filePath"] as String,
                startLine = record["startLine"] as? Int,
                endLine = record["endLine"] as? Int,
                score = 1.0
            )
        }
    }

    fun findSectionsByTitle(query: String, limit: Int = 10): List<StructuralMatch> {
        return driver.executeInTransaction(
            """
            MATCH (d:Document)-[:HAS_SECTION]->(s:Section)
            WHERE s.title CONTAINS ${'$'}query
            RETURN 'Section' as nodeType, s.id as nodeId, s.title as name,
                   d.path as filePath, s.startLine as startLine, s.endLine as endLine
            LIMIT ${'$'}limit
            """.trimIndent(),
            mapOf("query" to query, "limit" to limit)
        ) { record ->
            StructuralMatch(
                nodeType = record["nodeType"] as String,
                nodeId = record["nodeId"] as String,
                name = (record["name"] as? String) ?: "",
                filePath = record["filePath"] as String,
                startLine = record["startLine"] as? Int,
                endLine = record["endLine"] as? Int,
                score = 1.0
            )
        }
    }

    fun findAllStructure(query: String, limit: Int = 20): List<StructuralMatch> {
        val results = mutableListOf<StructuralMatch>()
        results.addAll(findClassesByName(query, limit / 4))
        results.addAll(findMethodsByName(query, limit / 4))
        results.addAll(findFunctionsByName(query, limit / 4))
        results.addAll(findSectionsByTitle(query, limit / 4))
        return results.sortedByDescending { it.score }.take(limit)
    }

    /**
     * Get chunk IDs with their relationship depth from a node.
     * Returns chunks with depth information for relationship-based ranking.
     * Handles both document and code structure hierarchies:
     *
     * Document hierarchy:
     * - Direct chunks (depth 1): Section -[:HAS_CHUNK]-> Chunk
     * - Chunks through Paragraphs (depth 2): Section -[:HAS_PARAGRAPH]-> Paragraph -[:HAS_CHUNK]-> Chunk
     *
     * Code hierarchy:
     * - Direct chunks (depth 1): Class/Function -[:HAS_CHUNK]-> Chunk
     * - Chunks through Methods (depth 2): Class -[:HAS_METHOD]-> Method -[:HAS_CHUNK]-> Chunk
     */
    fun getChunkIdsWithDepth(nodeType: String, nodeId: String): Map<Long, Int> {
        val results = mutableMapOf<Long, Int>()

        // Get direct chunks (depth 1) - works for all node types
        driver.executeInTransaction(
            """
            MATCH (n {id: ${'$'}nodeId})-[:HAS_CHUNK]->(ch:Chunk)
            RETURN DISTINCT ch.id as chunkId
            """.trimIndent(),
            mapOf("nodeId" to nodeId)
        ) { record ->
            val chunkId = (record["chunkId"] as? Number)?.toLong()
            if (chunkId != null && chunkId > 0) {
                results[chunkId] = 1  // Depth 1 - direct relationship
            }
        }

        // Get chunks through Paragraphs (depth 2) - for document sections
        driver.executeInTransaction(
            """
            MATCH (n {id: ${'$'}nodeId})-[:HAS_PARAGRAPH]->(p:Paragraph)-[:HAS_CHUNK]->(ch:Chunk)
            RETURN DISTINCT ch.id as chunkId
            """.trimIndent(),
            mapOf("nodeId" to nodeId)
        ) { record ->
            val chunkId = (record["chunkId"] as? Number)?.toLong()
            if (chunkId != null && chunkId > 0 && !results.containsKey(chunkId)) {
                results[chunkId] = 2  // Depth 2 - through paragraph
            }
        }

        // Get chunks through Methods (depth 2) - for code classes
        driver.executeInTransaction(
            """
            MATCH (n {id: ${'$'}nodeId})-[:HAS_METHOD]->(m:Method)-[:HAS_CHUNK]->(ch:Chunk)
            RETURN DISTINCT ch.id as chunkId
            """.trimIndent(),
            mapOf("nodeId" to nodeId)
        ) { record ->
            val chunkId = (record["chunkId"] as? Number)?.toLong()
            if (chunkId != null && chunkId > 0 && !results.containsKey(chunkId)) {
                results[chunkId] = 2  // Depth 2 - through method
            }
        }

        return results
    }

    fun getChunkIdsForNode(nodeType: String, nodeId: String): List<Long> {
        // Query chunks connected to a node via relationship traversal.
        // Handles both document and code structures:
        //
        // Document structure:
        // 1. Direct: Section -[:HAS_CHUNK]-> Chunk
        // 2. Through Paragraph: Section -[:HAS_PARAGRAPH]-> Paragraph -[:HAS_CHUNK]-> Chunk
        //
        // Code structure:
        // 1. Direct: Class/Function -[:HAS_CHUNK]-> Chunk
        // 2. Through Method: Class -[:HAS_METHOD]-> Method -[:HAS_CHUNK]-> Chunk
        val results = mutableListOf<Long>()

        // Get direct chunks
        driver.executeInTransaction(
            """
            MATCH (n {id: ${'$'}nodeId})-[:HAS_CHUNK]->(ch:Chunk)
            RETURN DISTINCT ch.id as chunkId
            """.trimIndent(),
            mapOf("nodeId" to nodeId)
        ) { record ->
            val chunkId = (record["chunkId"] as? Number)?.toLong()
            if (chunkId != null && chunkId > 0) {
                results.add(chunkId)
            }
        }

        // Get chunks through Paragraphs (for document structure)
        driver.executeInTransaction(
            """
            MATCH (n {id: ${'$'}nodeId})-[:HAS_PARAGRAPH]->(p:Paragraph)-[:HAS_CHUNK]->(ch:Chunk)
            RETURN DISTINCT ch.id as chunkId
            """.trimIndent(),
            mapOf("nodeId" to nodeId)
        ) { record ->
            val chunkId = (record["chunkId"] as? Number)?.toLong()
            if (chunkId != null && chunkId > 0 && !results.contains(chunkId)) {
                results.add(chunkId)
            }
        }

        // Get chunks through Methods (for code structure)
        driver.executeInTransaction(
            """
            MATCH (n {id: ${'$'}nodeId})-[:HAS_METHOD]->(m:Method)-[:HAS_CHUNK]->(ch:Chunk)
            RETURN DISTINCT ch.id as chunkId
            """.trimIndent(),
            mapOf("nodeId" to nodeId)
        ) { record ->
            val chunkId = (record["chunkId"] as? Number)?.toLong()
            if (chunkId != null && chunkId > 0 && !results.contains(chunkId)) {
                results.add(chunkId)
            }
        }

        return results
    }

    fun getChunkIdsForStructure(query: String, limit: Int = 100): List<Long> {
        val matches = findAllStructure(query, limit)
        return matches.flatMap { match ->
            getChunkIdsForNode(match.nodeType, match.nodeId)
        }.distinct()
    }

    /**
     * Calculate score based on relationship depth in graph traversal.
     * Closer relationships (shorter paths) result in higher scores:
     * - Depth 0 (direct match): score = 1.0
     * - Depth 1 (one hop away): score = 0.9
     * - Depth 2 (two hops away): score = 0.7
     *
     * This is used by Neo4jContextProvider to rank chunks based on how
     * closely they're related to the query match through the graph hierarchy.
     */
    fun getScoreForDepth(depth: Int): Double = when (depth) {
        0 -> 1.0
        1 -> 0.9
        2 -> 0.7
        else -> 0.5
    }
}
