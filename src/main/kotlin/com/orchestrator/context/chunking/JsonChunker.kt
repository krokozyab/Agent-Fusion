package com.orchestrator.context.chunking

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import kotlinx.serialization.json.*
import java.time.Instant

/**
 * Chunks JSON files by parsing the structure and splitting by top-level keys,
 * nested paths, and array indices. Similar to YamlChunker but for JSON.
 *
 * Features:
 * - Parses JSON structure using kotlinx.serialization
 * - Chunks by top-level keys (e.g., "dependencies", "scripts")
 * - Handles nested objects with path notation (e.g., "config.database.host")
 * - Splits large arrays by index
 * - Respects token limits and splits large values
 * - Falls back to whole content if parsing fails
 */
class JsonChunker(
    private val maxTokens: Int = 600,
    private val overlapPercent: Int = 15
) : SimpleChunker {

    private val json = Json { prettyPrint = true }

    override fun chunk(content: String, filePath: String): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var ordinal = 0

        if (content.isBlank()) {
            return emptyList()
        }

        try {
            val rootElement = Json.parseToJsonElement(content)

            when (rootElement) {
                is JsonObject -> {
                    rootElement.forEach { (key, value) ->
                        val jsonText = buildJsonString(key, value)

                        if (estimateTokens(jsonText) <= maxTokens) {
                            chunks.add(createChunk(jsonText, key, ordinal++))
                        } else {
                            // Split large values
                            chunks.addAll(splitLargeValue(key, value, ordinal))
                            ordinal = chunks.size
                        }
                    }
                }
                is JsonArray -> {
                    // Handle array at root level
                    rootElement.forEachIndexed { index, item ->
                        val itemJson = json.encodeToString(JsonElement.serializer(), item)
                        val label = "[$index]"
                        chunks.add(createChunk(itemJson, label, ordinal++))
                    }
                }
                else -> {
                    // Single primitive value
                    chunks.add(createChunk(content, "root", ordinal++))
                }
            }
        } catch (e: Exception) {
            // If parsing fails, return whole content as single chunk
            chunks.add(createChunk(content, "root", 0))
        }

        return OverlapProcessor.addOverlap(chunks, overlapPercent, ::estimateTokens)
    }

    private fun splitLargeValue(keyPath: String, value: JsonElement, startOrdinal: Int): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var ordinal = startOrdinal

        when (value) {
            is JsonObject -> {
                value.forEach { (subKey, subValue) ->
                    val subKeyPath = "$keyPath.$subKey"
                    val subJson = buildJsonString(subKey, subValue)

                    if (estimateTokens(subJson) <= maxTokens) {
                        chunks.add(createChunk(subJson, subKeyPath, ordinal++))
                    } else {
                        chunks.addAll(splitLargeValue(subKeyPath, subValue, ordinal))
                        ordinal = chunks.size
                    }
                }
            }
            is JsonArray -> {
                value.forEachIndexed { index, item ->
                    val itemPath = "$keyPath[$index]"
                    val itemJson = json.encodeToString(JsonElement.serializer(), item)

                    if (estimateTokens(itemJson) <= maxTokens) {
                        chunks.add(createChunk(itemJson, itemPath, ordinal++))
                    } else {
                        chunks.addAll(splitLargeValue(itemPath, item, ordinal))
                        ordinal = chunks.size
                    }
                }
            }
            is JsonPrimitive -> {
                if (value.isString) {
                    // Split large string by lines
                    val text = value.content
                    val lines = text.lines()

                    if (lines.size == 1 || estimateTokens(text) <= maxTokens) {
                        val valueJson = json.encodeToString(JsonPrimitive.serializer(), value)
                        chunks.add(createChunk(valueJson, keyPath, ordinal++))
                    } else {
                        val avgCharsPerLine = if (lines.isNotEmpty()) text.length / lines.size else 1
                        val linesPerChunk = maxOf(1, (maxTokens * 4) / avgCharsPerLine)

                        var start = 0
                        var chunkIndex = 0
                        while (start < lines.size) {
                            val end = (start + linesPerChunk).coerceAtMost(lines.size)
                            val chunkText = lines.subList(start, end).joinToString("\n")
                            val chunkJson = json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(chunkText))
                            chunks.add(createChunk(chunkJson, "$keyPath[$chunkIndex]", ordinal++))
                            start = end
                            chunkIndex++
                        }
                    }
                } else {
                    val valueJson = json.encodeToString(JsonElement.serializer(), value)
                    chunks.add(createChunk(valueJson, keyPath, ordinal++))
                }
            }
        }

        return chunks
    }

    private fun buildJsonString(key: String, value: JsonElement): String {
        val valueJson = json.encodeToString(JsonElement.serializer(), value)
        return """
            |{
            |  "$key": $valueJson
            |}
        """.trimMargin()
    }

    private fun createChunk(text: String, label: String, ordinal: Int): Chunk {
        val path = ChunkPaths.path(ChunkKind.JSON_BLOCK, label)
        return Chunk(
            id = 0,
            fileId = 0,
            ordinal = ordinal,
            kind = ChunkKind.JSON_BLOCK,
            // Line numbers are deliberately null: chunk text is re-serialized from the parsed JSON
            // tree (kotlinx.serialization carries no source positions), so any 1..N range here would
            // be relative to the synthesized snippet, not the file. Fabricated ranges made
            // DiffResolver match every JSON chunk to a change at line 1; null correctly excludes
            // them from line-range overlap while keeping whole-file matching.
            startLine = null,
            endLine = null,
            tokenEstimate = estimateTokens(text),
            content = text,
            summary = label,
            createdAt = Instant.now(),
            chunkPath = path
        )
    }

    private fun estimateTokens(text: String): Int = text.length / 4
}
