package com.orchestrator.context.chunking

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import java.time.Instant

class KotlinChunker(private val maxTokens: Int = 600, private val overlapPercent: Int = 15) : SimpleChunker {
    
    override fun chunk(content: String, filePath: String): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var ordinal = 0
        
        // Extract header (package + imports)
        val header = extractHeader(content)
        if (header.isNotBlank() && estimateTokens(header) <= 200) {
            chunks.add(createChunk(header, ChunkKind.CODE_HEADER, "header", ordinal++, 1, header.lines().size))
        }
        
        // Extract top-level declarations
        chunks.addAll(extractDeclarations(content, ordinal))
        
        return chunks
    }
    
    private fun extractHeader(content: String): String {
        val lines = content.lines()
        val headerLines = mutableListOf<String>()
        
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("package ") || trimmed.startsWith("import ")) {
                headerLines.add(line)
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("/*")) {
                break
            }
        }
        
        return headerLines.joinToString("\n")
    }
    
    private fun extractDeclarations(content: String, startOrdinal: Int): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var ordinal = startOrdinal
        val lines = content.lines()
        
        // Regex patterns for Kotlin declarations
        val enumClassPattern = Regex("""^\s*((?:public|private|internal|protected)\s+)*enum\s+class\s+(\w+)""")
        val classPattern = Regex("""^\s*((?:public|private|internal|protected|abstract|open|final|sealed|data|inline|value|annotation|expect|actual)\s+)*(class|interface|object)\s+(\w+)""")
        val funPattern = Regex("""^\s*((?:public|private|internal|protected|inline|suspend|operator|infix|tailrec|override|open|abstract)\s+)*fun\s+(?:<[^>]+>\s+)?(\w+)\s*\(""")
        val propertyPattern = Regex("""^\s*((?:public|private|internal|protected|const|lateinit|override)\s+)*(val|var)\s+(\w+)""")
        
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            
            // Skip comments and blank lines
            if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                i++
                continue
            }
            
            // Check for enum class first (more specific)
            val enumMatch = enumClassPattern.find(line)
            if (enumMatch != null) {
                val name = enumMatch.groupValues[2]
                val (text, endLine) = extractBlock(lines, i)
                val kdoc = extractKDocBefore(lines, i)
                val fullText = if (kdoc.isNotBlank()) "$kdoc\n$text" else text
                
                chunks.addAll(splitIfNeeded(fullText, ChunkKind.CODE_ENUM, name, ordinal, i + 1, endLine + 1))
                ordinal = chunks.size
                i = endLine + 1
                continue
            }
            
            // Check for class/interface/object
            val classMatch = classPattern.find(line)
            if (classMatch != null) {
                val (_, _, declType, name) = classMatch.groupValues
                val (text, endLine) = extractBlock(lines, i)
                val kdoc = extractKDocBefore(lines, i)
                val fullText = if (kdoc.isNotBlank()) "$kdoc\n$text" else text
                
                val kind = when {
                    declType.contains("interface") -> ChunkKind.CODE_INTERFACE
                    else -> ChunkKind.CODE_CLASS
                }
                
                chunks.addAll(splitIfNeeded(fullText, kind, name, ordinal, i + 1, endLine + 1))
                ordinal = chunks.size
                i = endLine + 1
                continue
            }
            
            // Check for top-level function
            val funMatch = funPattern.find(line)
            if (funMatch != null) {
                val name = funMatch.groupValues[2]
                val (text, endLine) = extractBlock(lines, i)
                val kdoc = extractKDocBefore(lines, i)
                val fullText = if (kdoc.isNotBlank()) "$kdoc\n$text" else text
                
                chunks.addAll(splitIfNeeded(fullText, ChunkKind.CODE_FUNCTION, name, ordinal, i + 1, endLine + 1))
                ordinal = chunks.size
                i = endLine + 1
                continue
            }
            
            // Check for top-level property
            val propMatch = propertyPattern.find(line)
            if (propMatch != null && !line.contains("{")) {
                val name = propMatch.groupValues[3]
                val kdoc = extractKDocBefore(lines, i)
                val fullText = if (kdoc.isNotBlank()) "$kdoc\n$line" else line
                
                chunks.add(createChunk(fullText, ChunkKind.CODE_BLOCK, name, ordinal++, i + 1, i + 1))
                i++
                continue
            }
            
            i++
        }
        
        return chunks
    }
    
    private fun extractKDocBefore(lines: List<String>, lineIndex: Int): String {
        val kdocLines = mutableListOf<String>()
        var i = lineIndex - 1
        
        // Skip blank lines
        while (i >= 0 && lines[i].trim().isEmpty()) {
            i--
        }
        
        // Check for KDoc
        if (i >= 0 && lines[i].trim() == "*/") {
            val endIdx = i
            while (i >= 0) {
                kdocLines.add(0, lines[i])
                if (lines[i].trim().startsWith("/**")) {
                    return kdocLines.joinToString("\n")
                }
                i--
            }
        }
        
        return ""
    }
    
    private fun extractBlock(lines: List<String>, startLine: Int): Pair<String, Int> {
        val blockLines = mutableListOf<String>()
        var braceCount = 0
        var inBlock = false
        var i = startLine
        
        while (i < lines.size) {
            val line = lines[i]
            blockLines.add(line)
            
            for (char in line) {
                when (char) {
                    '{' -> {
                        braceCount++
                        inBlock = true
                    }
                    '}' -> {
                        braceCount--
                        if (inBlock && braceCount == 0) {
                            return Pair(blockLines.joinToString("\n"), i)
                        }
                    }
                }
            }
            
            // Handle single-line declarations without braces
            if (!inBlock && (line.contains("=") || line.trim().endsWith(")"))) {
                if (!line.trim().endsWith(",") && !line.trim().endsWith("(")) {
                    return Pair(blockLines.joinToString("\n"), i)
                }
            }
            
            i++
        }
        
        return Pair(blockLines.joinToString("\n"), i - 1)
    }
    
    private fun splitIfNeeded(text: String, kind: ChunkKind, label: String, ordinal: Int, startLine: Int, endLine: Int): List<Chunk> {
        val tokens = estimateTokens(text)
        if (tokens < maxTokens) {
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
