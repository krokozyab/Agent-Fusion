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
class JsonChunker(private val maxTokens: Int = 600) : SimpleChunker {

    private val json = Json { prettyPrint = true }

    override fun chunk(content: String, filePath: String): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var ordinal = 0

        if (content.isBlank()) {
            return emptyList()
        }

        val lines = content.lines()
        val totalLines = lines.size

        try {
            val rootElement = Json.parseToJsonElement(content)

            when (rootElement) {
                is JsonObject -> {
                    rootElement.forEach { (key, value) ->
                        val jsonText = buildJsonString(key, value)
                        val chunkLines = jsonText.lines().size

                        if (estimateTokens(jsonText) <= maxTokens) {
                            chunks.add(createChunk(jsonText, key, ordinal++, 1, chunkLines))
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
                        val chunkLines = itemJson.lines().size
                        chunks.add(createChunk(itemJson, label, ordinal++, 1, chunkLines))
                    }
                }
                else -> {
                    // Single primitive value
                    chunks.add(createChunk(content, "root", ordinal++, 1, totalLines))
                }
            }
        } catch (e: Exception) {
            // If parsing fails, return whole content as single chunk
            chunks.add(createChunk(content, "root", 0, 1, totalLines))
        }

        return chunks
    }

    private fun splitLargeValue(keyPath: String, value: JsonElement, startOrdinal: Int): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var ordinal = startOrdinal

        when (value) {
            is JsonObject -> {
                value.forEach { (subKey, subValue) ->
                    val subKeyPath = "$keyPath.$subKey"
                    val subJson = buildJsonString(subKey, subValue)
                    val subLines = subJson.lines().size

                    if (estimateTokens(subJson) <= maxTokens) {
                        chunks.add(createChunk(subJson, subKeyPath, ordinal++, 1, subLines))
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
                    val itemLines = itemJson.lines().size

                    if (estimateTokens(itemJson) <= maxTokens) {
                        chunks.add(createChunk(itemJson, itemPath, ordinal++, 1, itemLines))
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
                        chunks.add(createChunk(valueJson, keyPath, ordinal++, 1, 1))
                    } else {
                        val avgCharsPerLine = if (lines.isNotEmpty()) text.length / lines.size else 1
                        val linesPerChunk = maxOf(1, (maxTokens * 4) / avgCharsPerLine)

                        var start = 0
                        var chunkIndex = 0
                        while (start < lines.size) {
                            val end = (start + linesPerChunk).coerceAtMost(lines.size)
                            val chunkText = lines.subList(start, end).joinToString("\n")
                            val chunkJson = json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(chunkText))
                            val startLine = start + 1
                            val endLine = end
                            chunks.add(createChunk(chunkJson, "$keyPath[$chunkIndex]", ordinal++, startLine, endLine))
                            start = end
                            chunkIndex++
                        }
                    }
                } else {
                    val valueJson = json.encodeToString(JsonElement.serializer(), value)
                    val valueLines = valueJson.lines().size
                    chunks.add(createChunk(valueJson, keyPath, ordinal++, 1, valueLines))
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

    private fun createChunk(text: String, label: String, ordinal: Int, startLine: Int?, endLine: Int?): Chunk {
        return Chunk(
            id = 0,
            fileId = 0,
            ordinal = ordinal,
            kind = ChunkKind.JSON_BLOCK,
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
