package com.orchestrator.context.neo4j

import org.neo4j.driver.Values

data class StructuralMatch(
    val nodeType: String,
    val nodeId: String,
    val name: String,
    val filePath: String,
    val startLine: Int?,
    val endLine: Int?,
    val score: Double
)

class Neo4jQueryProvider(private val driver: Neo4jDriver) {
    
    fun findClassesByName(query: String, limit: Int = 10): List<StructuralMatch> {
        driver.session().use { session ->
            val result = session.readTransaction { tx ->
                tx.run(
                    """
                    MATCH (f:File)-[:CONTAINS_CLASS]->(c:Class)
                    WHERE c.name CONTAINS ${'$'}query OR c.qualifiedName CONTAINS ${'$'}query
                    RETURN 'Class' as nodeType, c.id as nodeId, c.name as name, 
                           f.path as filePath, c.startLine as startLine, c.endLine as endLine
                    LIMIT ${'$'}limit
                    """.trimIndent(),
                    Values.parameters("query", query, "limit", limit)
                )
            }
            
            return result.list { record ->
                StructuralMatch(
                    nodeType = record.get("nodeType").asString(),
                    nodeId = record.get("nodeId").asString(),
                    name = record.get("name").asString(),
                    filePath = record.get("filePath").asString(),
                    startLine = record.get("startLine").asInt(),
                    endLine = record.get("endLine").asInt(),
                    score = 1.0
                )
            }
        }
    }
    
    fun findMethodsByName(query: String, limit: Int = 10): List<StructuralMatch> {
        driver.session().use { session ->
            val result = session.readTransaction { tx ->
                tx.run(
                    """
                    MATCH (f:File)-[:CONTAINS_CLASS]->(c:Class)-[:HAS_METHOD]->(m:Method)
                    WHERE m.name CONTAINS ${'$'}query
                    RETURN 'Method' as nodeType, m.id as nodeId, m.name as name,
                           f.path as filePath, m.startLine as startLine, m.endLine as endLine
                    LIMIT ${'$'}limit
                    """.trimIndent(),
                    Values.parameters("query", query, "limit", limit)
                )
            }
            
            return result.list { record ->
                StructuralMatch(
                    nodeType = record.get("nodeType").asString(),
                    nodeId = record.get("nodeId").asString(),
                    name = record.get("name").asString(),
                    filePath = record.get("filePath").asString(),
                    startLine = record.get("startLine").asInt(),
                    endLine = record.get("endLine").asInt(),
                    score = 1.0
                )
            }
        }
    }
    
    fun findFunctionsByName(query: String, limit: Int = 10): List<StructuralMatch> {
        driver.session().use { session ->
            val result = session.readTransaction { tx ->
                tx.run(
                    """
                    MATCH (f:File)-[:CONTAINS_FUNCTION]->(fn:Function)
                    WHERE fn.name CONTAINS ${'$'}query
                    RETURN 'Function' as nodeType, fn.id as nodeId, fn.name as name,
                           f.path as filePath, fn.startLine as startLine, fn.endLine as endLine
                    LIMIT ${'$'}limit
                    """.trimIndent(),
                    Values.parameters("query", query, "limit", limit)
                )
            }
            
            return result.list { record ->
                StructuralMatch(
                    nodeType = record.get("nodeType").asString(),
                    nodeId = record.get("nodeId").asString(),
                    name = record.get("name").asString(),
                    filePath = record.get("filePath").asString(),
                    startLine = record.get("startLine").asInt(),
                    endLine = record.get("endLine").asInt(),
                    score = 1.0
                )
            }
        }
    }
    
    fun findSectionsByTitle(query: String, limit: Int = 10): List<StructuralMatch> {
        driver.session().use { session ->
            val result = session.readTransaction { tx ->
                tx.run(
                    """
                    MATCH (d:Document)-[:HAS_SECTION]->(s:Section)
                    WHERE s.title CONTAINS ${'$'}query
                    RETURN 'Section' as nodeType, s.id as nodeId, s.title as name,
                           d.path as filePath, s.startLine as startLine, s.endLine as endLine
                    LIMIT ${'$'}limit
                    """.trimIndent(),
                    Values.parameters("query", query, "limit", limit)
                )
            }
            
            return result.list { record ->
                StructuralMatch(
                    nodeType = record.get("nodeType").asString(),
                    nodeId = record.get("nodeId").asString(),
                    name = record.get("name").asString(""),
                    filePath = record.get("filePath").asString(),
                    startLine = if (record.get("startLine").isNull) null else record.get("startLine").asInt(),
                    endLine = if (record.get("endLine").isNull) null else record.get("endLine").asInt(),
                    score = 1.0
                )
            }
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
    
    fun getChunkIdsForNode(nodeType: String, nodeId: String): List<Long> {
        driver.session().use { session ->
            val result = session.readTransaction { tx ->
                tx.run(
                    """
                    MATCH (n:$nodeType {id: ${'$'}nodeId})-[:HAS_CHUNK]->(ch:Chunk)
                    RETURN ch.id as chunkId
                    """.trimIndent(),
                    Values.parameters("nodeId", nodeId)
                )
            }
            
            return result.list { record -> record.get("chunkId").asLong() }
        }
    }
    
    fun getChunkIdsForStructure(query: String, limit: Int = 100): List<Long> {
        val matches = findAllStructure(query, limit)
        return matches.flatMap { match ->
            getChunkIdsForNode(match.nodeType, match.nodeId)
        }.distinct()
    }
}
