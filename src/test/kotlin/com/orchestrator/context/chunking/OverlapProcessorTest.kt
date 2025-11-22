package com.orchestrator.context.chunking

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class OverlapProcessorTest {

    private val estimator: (String) -> Int = { text ->
        text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
    }

    @Test
    fun `single chunk returns unchanged`() {
        val chunk = chunkOf(0, "Only sentence here.")

        val result = OverlapProcessor.addOverlap(listOf(chunk), overlapPercent = 50, estimateTokens = estimator)

        assertEquals(1, result.size)
        assertEquals(chunk.content, result.first().content)
    }

    @Test
    fun `applies bidirectional overlap for middle chunk`() {
        val left = chunkOf(0, "Alpha one. Alpha two.")
        val middle = chunkOf(1, "Bravo one. Bravo two.")
        val right = chunkOf(2, "Charlie one. Charlie two.")

        val result = OverlapProcessor.addOverlap(listOf(left, middle, right), overlapPercent = 100, estimateTokens = estimator)
        val overlapped = result[1].content

        assertTrue(overlapped.contains("Bravo one."), "Should preserve original content")
        assertTrue(
            overlapped.contains("Alpha") || overlapped.contains("Charlie"),
            "Should include at least one neighbor side"
        )
    }

    @Test
    fun `first chunk only overlaps forward`() {
        val first = chunkOf(0, "Start one. Start two.")
        val second = chunkOf(1, "Next one. Next two.")

        val result = OverlapProcessor.addOverlap(listOf(first, second), overlapPercent = 50, estimateTokens = estimator)
        val overlappedFirst = result.first().content
        val overlappedSecond = result.last().content

        assertTrue(overlappedFirst.contains("Next one."), "First chunk should include head of second")
        assertTrue(!overlappedFirst.contains("Start two. Next one. Start one."), "No duplicate ordering issues")
        assertTrue(overlappedSecond.contains("Start two.") || overlappedSecond.contains("Start one."), "Second chunk should include tail of first")
    }

    private fun chunkOf(ordinal: Int, text: String): Chunk =
        Chunk(
            id = ordinal.toLong(),
            fileId = 0,
            ordinal = ordinal,
            kind = ChunkKind.CODE_BLOCK,
            startLine = null,
            endLine = null,
            tokenEstimate = estimator(text),
            content = text,
            summary = null,
            createdAt = Instant.EPOCH
        )
}
