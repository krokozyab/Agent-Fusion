package com.orchestrator.context.neo4j

import org.neo4j.driver.Values

class CodeStructureIndexer(private val driver: Neo4jDriverInterface) {

    fun indexCodeStructure(structure: CodeStructure) {
        // Create or update File node
        driver.executeInTransaction(
            """
            MERGE (f:File {path: ${'$'}path})
            SET f.language = ${'$'}language, f.fileType = 'CODE'
            """.trimIndent(),
            mapOf("path" to structure.filePath, "language" to structure.language)
        ) { _ -> Unit }

        // Create class nodes
        structure.classes.forEach { classNode ->
            createClassNode(classNode, structure.filePath)
        }

        // Create top-level function nodes
        structure.functions.forEach { functionNode ->
            createFunctionNode(functionNode, structure.filePath)
        }
    }

    fun linkChunkToClass(chunkId: Long, classId: String) {
        driver.executeInTransaction(
            """
            MATCH (c:Class {id: ${'$'}classId})
            MERGE (ch:Chunk {id: ${'$'}chunkId})
            MERGE (c)-[:HAS_CHUNK]->(ch)
            """.trimIndent(),
            mapOf("classId" to classId, "chunkId" to chunkId)
        ) { _ -> Unit }
    }

    fun linkChunkToMethod(chunkId: Long, methodId: String) {
        driver.executeInTransaction(
            """
            MATCH (m:Method {id: ${'$'}methodId})
            MERGE (ch:Chunk {id: ${'$'}chunkId})
            MERGE (m)-[:HAS_CHUNK]->(ch)
            """.trimIndent(),
            mapOf("methodId" to methodId, "chunkId" to chunkId)
        ) { _ -> Unit }
    }

    fun linkChunkToFunction(chunkId: Long, functionId: String) {
        driver.executeInTransaction(
            """
            MATCH (f:Function {id: ${'$'}functionId})
            MERGE (ch:Chunk {id: ${'$'}chunkId})
            MERGE (f)-[:HAS_CHUNK]->(ch)
            """.trimIndent(),
            mapOf("functionId" to functionId, "chunkId" to chunkId)
        ) { _ -> Unit }
    }

    fun deleteCodeStructure(filePath: String) {
        driver.executeInTransaction(
            """
            MATCH (f:File {path: ${'$'}path})
            OPTIONAL MATCH (f)-[:CONTAINS_CLASS]->(c:Class)
            OPTIONAL MATCH (c)-[:HAS_METHOD]->(m:Method)
            OPTIONAL MATCH (f)-[:CONTAINS_FUNCTION]->(fn:Function)
            DETACH DELETE f, c, m, fn
            """.trimIndent(),
            mapOf("path" to filePath)
        ) { _ -> Unit }
    }

    private fun createClassNode(classNode: ClassNode, filePath: String) {
        driver.executeInTransaction(
            """
            MATCH (f:File {path: ${'$'}filePath})
            MERGE (c:Class {id: ${'$'}id})
            SET c.name = ${'$'}name, c.qualifiedName = ${'$'}qualifiedName,
                c.startLine = ${'$'}startLine, c.endLine = ${'$'}endLine
            MERGE (f)-[:CONTAINS_CLASS]->(c)
            """.trimIndent(),
            mapOf(
                "filePath" to filePath,
                "id" to classNode.id,
                "name" to classNode.name,
                "qualifiedName" to classNode.qualifiedName,
                "startLine" to classNode.startLine,
                "endLine" to classNode.endLine
            )
        ) { _ -> Unit }

        // Create method nodes for this class
        classNode.methods.forEach { method ->
            createMethodNode(method, classNode.id)
        }
    }

    private fun createMethodNode(method: MethodNode, classId: String) {
        driver.executeInTransaction(
            """
            MATCH (c:Class {id: ${'$'}classId})
            MERGE (m:Method {id: ${'$'}id})
            SET m.name = ${'$'}name, m.signature = ${'$'}signature,
                m.startLine = ${'$'}startLine, m.endLine = ${'$'}endLine,
                m.returnType = ${'$'}returnType
            MERGE (c)-[:HAS_METHOD]->(m)
            """.trimIndent(),
            mapOf(
                "classId" to classId,
                "id" to method.id,
                "name" to method.name,
                "signature" to method.signature,
                "startLine" to method.startLine,
                "endLine" to method.endLine,
                "returnType" to method.returnType
            )
        ) { _ -> Unit }
    }

    private fun createFunctionNode(function: FunctionNode, filePath: String) {
        driver.executeInTransaction(
            """
            MATCH (f:File {path: ${'$'}filePath})
            MERGE (fn:Function {id: ${'$'}id})
            SET fn.name = ${'$'}name, fn.signature = ${'$'}signature,
                fn.startLine = ${'$'}startLine, fn.endLine = ${'$'}endLine,
                fn.returnType = ${'$'}returnType
            MERGE (f)-[:CONTAINS_FUNCTION]->(fn)
            """.trimIndent(),
            mapOf(
                "filePath" to filePath,
                "id" to function.id,
                "name" to function.name,
                "signature" to function.signature,
                "startLine" to function.startLine,
                "endLine" to function.endLine,
                "returnType" to function.returnType
            )
        ) { _ -> Unit }
    }
}
