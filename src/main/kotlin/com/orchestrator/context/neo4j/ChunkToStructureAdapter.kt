package com.orchestrator.context.neo4j

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import java.nio.file.Path

/**
 * Converts existing Chunk objects (from JavaChunker, KotlinChunker, etc.) to CodeStructure for Neo4j indexing.
 * Reuses AST extraction already done by chunkers instead of re-parsing files.
 */
object ChunkToStructureAdapter {
    
    fun fromChunks(filePath: Path, chunks: List<Chunk>, language: String): CodeStructure? {
        val codeChunks = chunks.filter { it.kind.isCode() }
        if (codeChunks.isEmpty()) return null
        
        val classes = mutableListOf<ClassNode>()
        val functions = mutableListOf<FunctionNode>()
        
        // Group chunks by class
        val chunksByClass = codeChunks.groupBy { extractClassName(it) }
        
        chunksByClass.forEach { (className, classChunks) ->
            if (className != null) {
                val classChunk = classChunks.firstOrNull { it.kind == ChunkKind.CODE_CLASS }
                val methods = classChunks.filter { it.kind == ChunkKind.CODE_METHOD }.map { chunk ->
                    MethodNode(
                        id = "${className}.${chunk.summary ?: "unknown"}",
                        name = chunk.summary ?: "unknown",
                        startLine = chunk.startLine ?: 1,
                        endLine = chunk.endLine ?: 1,
                        parameters = emptyList(),
                        returnType = null,
                        signature = chunk.summary ?: ""
                    )
                }
                
                classes.add(ClassNode(
                    id = className,
                    name = className,
                    qualifiedName = className,
                    startLine = classChunk?.startLine ?: classChunks.minOfOrNull { it.startLine ?: 1 } ?: 1,
                    endLine = classChunk?.endLine ?: classChunks.maxOfOrNull { it.endLine ?: 1 } ?: 1,
                    methods = methods,
                    fields = emptyList()
                ))
            } else {
                classChunks.filter { it.kind == ChunkKind.CODE_FUNCTION }.forEach { chunk ->
                    functions.add(FunctionNode(
                        id = chunk.summary ?: "unknown",
                        name = chunk.summary ?: "unknown",
                        startLine = chunk.startLine ?: 1,
                        endLine = chunk.endLine ?: 1,
                        parameters = emptyList(),
                        returnType = null,
                        signature = chunk.summary ?: ""
                    ))
                }
            }
        }
        
        return CodeStructure(
            filePath = filePath.toString(),
            language = language,
            classes = classes,
            functions = functions,
            imports = emptyList()
        )
    }
    
    private fun extractClassName(chunk: Chunk): String? {
        val summary = chunk.summary ?: return null
        
        return when {
            chunk.kind == ChunkKind.CODE_CLASS || chunk.kind == ChunkKind.CODE_INTERFACE -> {
                summary.removePrefix("class ").removePrefix("interface ").removePrefix("object ").trim()
            }
            chunk.kind == ChunkKind.CODE_METHOD && summary.contains(".") -> {
                summary.substringBefore(".")
            }
            else -> null
        }
    }
    
    private fun ChunkKind.isCode(): Boolean = when (this) {
        ChunkKind.CODE_CLASS, ChunkKind.CODE_INTERFACE, ChunkKind.CODE_METHOD, ChunkKind.CODE_FUNCTION -> true
        else -> false
    }
}
