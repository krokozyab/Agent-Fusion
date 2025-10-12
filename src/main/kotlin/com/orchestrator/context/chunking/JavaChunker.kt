package com.orchestrator.context.chunking

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.*
import com.github.javaparser.ast.comments.JavadocComment
import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import java.time.Instant

class JavaChunker(private val maxTokens: Int = 600, private val overlapPercent: Int = 15) : SimpleChunker {
    
    private val parser = JavaParser()
    
    override fun chunk(content: String, filePath: String): List<Chunk> {
        val result = parser.parse(content)
        if (!result.isSuccessful) return emptyList()
        
        val cu = result.result.get()
        val chunks = mutableListOf<Chunk>()
        var ordinal = 0
        
        // Header chunk (package + imports)
        val header = buildHeader(cu)
        if (header.isNotBlank() && estimateTokens(header) <= 200) {
            chunks.add(createChunk(header, ChunkKind.CODE_HEADER, "header", ordinal++, null, null))
        }
        
        // Process types
        cu.types.forEach { type ->
            chunks.addAll(processType(type, ordinal, null))
            ordinal = chunks.size
        }
        
        return chunks
    }
    
    private fun buildHeader(cu: CompilationUnit): String {
        val sb = StringBuilder()
        cu.packageDeclaration.ifPresent { sb.append(it).append("\n\n") }
        cu.imports.forEach { sb.append(it).append("\n") }
        return sb.toString().trim()
    }
    
    private fun processType(type: TypeDeclaration<*>, startOrdinal: Int, parentLabel: String?): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var ordinal = startOrdinal
        
        val typeName = type.nameAsString
        val fullLabel = if (parentLabel != null) "$parentLabel.$typeName" else typeName
        
        // Type declaration chunk
        val typeKind = when (type) {
            is ClassOrInterfaceDeclaration -> if (type.isInterface) ChunkKind.CODE_INTERFACE else ChunkKind.CODE_CLASS
            is EnumDeclaration -> ChunkKind.CODE_ENUM
            else -> ChunkKind.CODE_CLASS
        }
        
        val typeText = buildTypeDeclaration(type)
        chunks.add(createChunk(typeText, typeKind, fullLabel, ordinal++, type.begin.get().line, type.end.get().line))
        
        // Process members
        type.members.forEach { member ->
            when (member) {
                is MethodDeclaration -> {
                    val methodText = buildMethodText(member)
                    val methodLabel = "$fullLabel.${member.nameAsString}(${member.parameters.joinToString { it.typeAsString }})"
                    chunks.addAll(splitIfNeeded(methodText, ChunkKind.CODE_METHOD, methodLabel, ordinal, member.begin.get().line, member.end.get().line))
                    ordinal = chunks.size
                }
                is ConstructorDeclaration -> {
                    val ctorText = buildConstructorText(member)
                    val ctorLabel = "$fullLabel.<init>(${member.parameters.joinToString { it.typeAsString }})"
                    chunks.addAll(splitIfNeeded(ctorText, ChunkKind.CODE_CONSTRUCTOR, ctorLabel, ordinal, member.begin.get().line, member.end.get().line))
                    ordinal = chunks.size
                }
                is ClassOrInterfaceDeclaration, is EnumDeclaration -> {
                    chunks.addAll(processType(member as TypeDeclaration<*>, ordinal, fullLabel))
                    ordinal = chunks.size
                }
                is InitializerDeclaration -> {
                    val initText = member.toString()
                    val initLabel = if (member.isStatic) "$fullLabel.<clinit>" else "$fullLabel.<init>"
                    chunks.add(createChunk(initText, ChunkKind.CODE_BLOCK, initLabel, ordinal++, member.begin.get().line, member.end.get().line))
                }
            }
        }
        
        return chunks
    }
    
    private fun buildTypeDeclaration(type: TypeDeclaration<*>): String {
        val sb = StringBuilder()
        type.comment.ifPresent { if (it is JavadocComment) sb.append(it).append("\n") }
        type.annotations.forEach { sb.append(it).append("\n") }
        sb.append(type.modifiers.joinToString(" ") { it.keyword.asString() })
        sb.append(" ")
        when (type) {
            is ClassOrInterfaceDeclaration -> sb.append(if (type.isInterface) "interface" else "class").append(" ").append(type.nameAsString)
            is EnumDeclaration -> sb.append("enum ").append(type.nameAsString)
        }
        sb.append(" { ... }")
        return sb.toString()
    }
    
    private fun buildMethodText(method: MethodDeclaration): String {
        val sb = StringBuilder()
        method.comment.ifPresent { if (it is JavadocComment) sb.append(it).append("\n") }
        method.annotations.forEach { sb.append(it).append("\n") }
        sb.append(method.modifiers.joinToString(" ") { it.keyword.asString() })
        sb.append(" ").append(method.typeAsString).append(" ").append(method.nameAsString)
        sb.append("(").append(method.parameters.joinToString(", ")).append(")")
        method.body.ifPresent { sb.append(" ").append(it) }
        return sb.toString()
    }
    
    private fun buildConstructorText(ctor: ConstructorDeclaration): String {
        val sb = StringBuilder()
        ctor.comment.ifPresent { if (it is JavadocComment) sb.append(it).append("\n") }
        ctor.annotations.forEach { sb.append(it).append("\n") }
        sb.append(ctor.modifiers.joinToString(" ") { it.keyword.asString() })
        sb.append(" ").append(ctor.nameAsString)
        sb.append("(").append(ctor.parameters.joinToString(", ")).append(")")
        sb.append(" ").append(ctor.body)
        return sb.toString()
    }
    
    private fun splitIfNeeded(text: String, kind: ChunkKind, label: String, ordinal: Int, startLine: Int, endLine: Int): List<Chunk> {
        val tokens = estimateTokens(text)
        if (tokens <= maxTokens) {
            return listOf(createChunk(text, kind, label, ordinal, startLine, endLine))
        }
        
        // Split by lines with overlap
        val lines = text.lines()
        val chunks = mutableListOf<Chunk>()
        val tokensPerLine = (tokens.toDouble() / lines.size).coerceAtLeast(1.0)
        val linesPerChunk = maxOf(1, (maxTokens / tokensPerLine).toInt())
        val overlapLines = maxOf(1, (linesPerChunk * (overlapPercent / 100.0)).toInt())
        
        var start = 0
        var chunkOrdinal = ordinal
        while (start < lines.size) {
            val end = (start + linesPerChunk).coerceAtMost(lines.size)
            val chunkText = lines.subList(start, end).joinToString("\n")
            chunks.add(createChunk(chunkText, kind, "$label[${chunkOrdinal - ordinal}]", chunkOrdinal++, startLine + start, startLine + end))
            start = (end - overlapLines).coerceAtLeast(start + 1)
            if (start >= lines.size) break
        }

        return chunks
    }
    
    private fun createChunk(text: String, kind: ChunkKind, label: String, ordinal: Int, startLine: Int?, endLine: Int?): Chunk {
        return Chunk(
            id = 0,
            fileId = 0,
            ordinal = ordinal,
            kind = kind,
            startLine = startLine?.takeIf { it > 0 },
            endLine = endLine?.takeIf { it > 0 },
            tokenEstimate = estimateTokens(text),
            content = text,
            summary = label,
            createdAt = Instant.now()
        )
    }

    private fun estimateTokens(text: String): Int = text.length / 4
}
