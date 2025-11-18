package com.orchestrator.context.neo4j

import com.orchestrator.context.domain.Chunk
import java.nio.file.Path

class DualStorageCoordinator(
    private val codeIndexer: CodeStructureIndexer,
    private val documentIndexer: DocumentStructureIndexer
) {
    
    fun indexDocument(filePath: Path, content: String, extension: String): IndexResult {
        val structure = DocumentStructureAdapter.extractStructure(filePath, content, extension) 
            ?: return IndexResult.Skipped("Unsupported file type")
        
        return try {
            documentIndexer.indexDocumentStructure(structure)
            IndexResult.Success(structure.sections.size)
        } catch (e: Exception) {
            IndexResult.Failed(e.message ?: "Unknown error")
        }
    }
    
    fun indexCode(structure: CodeStructure): IndexResult {
        return try {
            codeIndexer.indexCodeStructure(structure)
            val nodeCount = structure.classes.size + structure.functions.size
            IndexResult.Success(nodeCount)
        } catch (e: Exception) {
            IndexResult.Failed(e.message ?: "Unknown error")
        }
    }
    
    fun linkChunksToStructure(chunks: List<Chunk>, structure: DocumentStructure) {
        chunks.forEach { chunk ->
            val sectionId = findSectionForChunk(chunk, structure)
            if (sectionId != null) {
                documentIndexer.linkChunkToSection(chunk.id, sectionId)
            }
        }
    }
    
    fun linkChunksToCode(chunks: List<Chunk>, structure: CodeStructure) {
        chunks.forEach { chunk ->
            when {
                chunk.startLine != null -> {
                    val classId = findClassForLine(chunk.startLine, structure)
                    if (classId != null) {
                        codeIndexer.linkChunkToClass(chunk.id, classId)
                    }
                    
                    val methodId = findMethodForLine(chunk.startLine, structure)
                    if (methodId != null) {
                        codeIndexer.linkChunkToMethod(chunk.id, methodId)
                    }
                    
                    val functionId = findFunctionForLine(chunk.startLine, structure)
                    if (functionId != null) {
                        codeIndexer.linkChunkToFunction(chunk.id, functionId)
                    }
                }
            }
        }
    }
    
    fun deleteFile(filePath: Path, isCode: Boolean) {
        if (isCode) {
            codeIndexer.deleteCodeStructure(filePath.toString())
        } else {
            documentIndexer.deleteDocumentStructure(filePath.toString())
        }
    }
    
    private fun findSectionForChunk(chunk: Chunk, structure: DocumentStructure): String? {
        if (chunk.startLine == null) return structure.sections.firstOrNull()?.id
        
        return structure.sections
            .filter { section ->
                section.startLine != null && section.endLine != null &&
                chunk.startLine >= section.startLine && chunk.startLine <= section.endLine
            }
            .minByOrNull { it.startLine ?: Int.MAX_VALUE }
            ?.id
    }
    
    private fun findClassForLine(line: Int, structure: CodeStructure): String? {
        return structure.classes
            .firstOrNull { it.startLine <= line && it.endLine >= line }
            ?.id
    }
    
    private fun findMethodForLine(line: Int, structure: CodeStructure): String? {
        return structure.classes
            .flatMap { it.methods }
            .firstOrNull { it.startLine <= line && it.endLine >= line }
            ?.id
    }
    
    private fun findFunctionForLine(line: Int, structure: CodeStructure): String? {
        return structure.functions
            .firstOrNull { it.startLine <= line && it.endLine >= line }
            ?.id
    }
}

sealed class IndexResult {
    data class Success(val nodesCreated: Int) : IndexResult()
    data class Failed(val error: String) : IndexResult()
    data class Skipped(val reason: String) : IndexResult()
}
