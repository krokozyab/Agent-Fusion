package com.orchestrator.context.chunking

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import org.yaml.snakeyaml.Yaml
import java.time.Instant

class YamlChunker(private val maxTokens: Int = 600) : SimpleChunker {
    
    private val yaml = Yaml()
    
    override fun chunk(content: String, filePath: String): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var ordinal = 0
        
        if (content.isBlank()) {
            return emptyList()
        }
        
        try {
            val data = yaml.load<Any>(content)
            
            when (data) {
                is Map<*, *> -> {
                    data.forEach { (key, value) ->
                        val keyStr = key.toString()
                        val valueYaml = toYamlString(value)
                        val fullText = "$keyStr:\n$valueYaml"
                        
                        if (estimateTokens(fullText) <= maxTokens) {
                            chunks.add(createChunk(fullText, keyStr, ordinal++, null, null))
                        } else {
                            // Split large values
                            chunks.addAll(splitLargeValue(keyStr, value, ordinal))
                            ordinal = chunks.size
                        }
                    }
                }
                is List<*> -> {
                    // Handle list at root level
                    data.forEachIndexed { index, item ->
                        val itemYaml = toYamlString(item)
                        val label = "[$index]"
                        chunks.add(createChunk(itemYaml, label, ordinal++, null, null))
                    }
                }
                else -> {
                    // Single value
                    chunks.add(createChunk(content, "root", ordinal++, null, null))
                }
            }
        } catch (e: Exception) {
            // If parsing fails, return whole content as single chunk
            chunks.add(createChunk(content, "root", 0, null, null))
        }
        
        return chunks
    }
    
    private fun splitLargeValue(keyPath: String, value: Any?, startOrdinal: Int): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var ordinal = startOrdinal
        
        when (value) {
            is Map<*, *> -> {
                value.forEach { (subKey, subValue) ->
                    val subKeyPath = "$keyPath.$subKey"
                    val subYaml = "$subKey:\n${toYamlString(subValue)}"
                    
                    if (estimateTokens(subYaml) <= maxTokens) {
                        chunks.add(createChunk(subYaml, subKeyPath, ordinal++, null, null))
                    } else {
                        chunks.addAll(splitLargeValue(subKeyPath, subValue, ordinal))
                        ordinal = chunks.size
                    }
                }
            }
            is List<*> -> {
                value.forEachIndexed { index, item ->
                    val itemPath = "$keyPath[$index]"
                    val itemYaml = toYamlString(item)
                    
                    if (estimateTokens(itemYaml) <= maxTokens) {
                        chunks.add(createChunk(itemYaml, itemPath, ordinal++, null, null))
                    } else {
                        chunks.addAll(splitLargeValue(itemPath, item, ordinal))
                        ordinal = chunks.size
                    }
                }
            }
            is String -> {
                // Split large string by lines
                val lines = value.lines()
                val linesPerChunk = maxOf(1, (maxTokens * 4) / (value.length / lines.size.coerceAtLeast(1)))
                
                var start = 0
                var chunkIndex = 0
                while (start < lines.size) {
                    val end = (start + linesPerChunk).coerceAtMost(lines.size)
                    val chunkText = lines.subList(start, end).joinToString("\n")
                    chunks.add(createChunk(chunkText, "$keyPath[$chunkIndex]", ordinal++, null, null))
                    start = end
                    chunkIndex++
                }
            }
            else -> {
                chunks.add(createChunk(toYamlString(value), keyPath, ordinal++, null, null))
            }
        }
        
        return chunks
    }
    
    private fun toYamlString(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> if (value.contains("\n")) "|\n  ${value.replace("\n", "\n  ")}" else value
            is Map<*, *> -> {
                value.entries.joinToString("\n") { (k, v) ->
                    val valueStr = toYamlString(v)
                    if (valueStr.contains("\n")) {
                        "$k:\n${valueStr.prependIndent("  ")}"
                    } else {
                        "$k: $valueStr"
                    }
                }
            }
            is List<*> -> {
                value.joinToString("\n") { "- ${toYamlString(it)}" }
            }
            else -> value.toString()
        }
    }
    
    private fun createChunk(text: String, label: String, ordinal: Int, startLine: Int?, endLine: Int?): Chunk {
        return Chunk(
            id = 0,
            fileId = 0,
            ordinal = ordinal,
            kind = ChunkKind.YAML_BLOCK,
            startLine = startLine,
            endLine = endLine,
            tokenEstimate = estimateTokens(text),
            content = text,
            summary = label,
            createdAt = Instant.now()
        )
    }
    
    private fun estimateTokens(text: String): Int = text.length / 4
}
