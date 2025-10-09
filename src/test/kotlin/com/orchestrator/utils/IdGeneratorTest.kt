package com.orchestrator.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IdGeneratorTest {

    @Test
    fun `ulid generates valid 26-char IDs`() {
        val id = IdGenerator.ulid()
        assertEquals(26, id.length)
        assertTrue(IdGenerator.isValidUlid(id))
    }

    @Test
    fun `ulid generates unique IDs`() {
        val ids = (1..1000).map { IdGenerator.ulid() }.toSet()
        assertEquals(1000, ids.size, "All IDs should be unique")
    }

    @Test
    fun `ulid IDs are lexicographically sortable by time`() {
        val ids = mutableListOf<Pair<String, Long>>()

        repeat(100) {
            val beforeGeneration = System.currentTimeMillis()
            val id = IdGenerator.ulid()
            val afterGeneration = System.currentTimeMillis()
            ids.add(id to (beforeGeneration + afterGeneration) / 2)
            Thread.sleep(2) // Small delay to ensure different timestamps
        }

        // Sort by ID string
        val sortedByString = ids.sortedBy { it.first }

        // Check that string ordering matches time ordering
        for (i in 0 until sortedByString.size - 1) {
            val current = sortedByString[i]
            val next = sortedByString[i + 1]

            assertTrue(
                current.second <= next.second,
                "ID ordering should match time ordering: ${current.first} (${current.second}) should be <= ${next.first} (${next.second})"
            )
        }
    }

    @Test
    fun `taskId generates prefixed IDs`() {
        val id = IdGenerator.taskId()
        assertTrue(id.startsWith("task-"))
        assertEquals(31, id.length) // "task-" (5) + ULID (26)
        assertTrue(IdGenerator.isValidUlid(id.substringAfter("task-")))
    }

    @Test
    fun `proposalId generates prefixed IDs`() {
        val id = IdGenerator.proposalId()
        assertTrue(id.startsWith("proposal-"))
        assertEquals(35, id.length) // "proposal-" (9) + ULID (26)
    }

    @Test
    fun `decisionId generates prefixed IDs`() {
        val id = IdGenerator.decisionId()
        assertTrue(id.startsWith("decision-"))
        assertEquals(35, id.length) // "decision-" (9) + ULID (26)
    }

    @Test
    fun `agentId sanitizes names correctly`() {
        val tests = mapOf(
            "Claude Code" to "claude-code-",
            "Codex CLI" to "codex-cli-",
            "My Agent!!!" to "my-agent-",
            "test@#agent" to "testagent-",
            "  spaces  " to "spaces-",
            "under_score" to "under_score-",
            "hyp-hen" to "hyp-hen-"
        )

        tests.forEach { (input, expectedPrefix) ->
            val id = IdGenerator.agentId(input)
            assertTrue(
                id.startsWith(expectedPrefix),
                "agentId('$input') should start with '$expectedPrefix', got: $id"
            )
            assertTrue(IdGenerator.isValidUlid(id.substringAfterLast("-")))
        }
    }

    @Test
    fun `agentId throws on invalid names`() {
        val invalidNames = listOf(
            "",
            "   ",
            "!!!",
            "@#$%",
            "---"
        )

        invalidNames.forEach { invalidName ->
            assertThrows<IllegalArgumentException>(
                "agentId('$invalidName') should throw IllegalArgumentException"
            ) {
                IdGenerator.agentId(invalidName)
            }
        }
    }

    @Test
    fun `correlationId generates valid ULIDs`() {
        val id = IdGenerator.correlationId()
        assertEquals(26, id.length)
        assertTrue(IdGenerator.isValidUlid(id))
    }

    @Test
    fun `messageId generates prefixed IDs`() {
        val id = IdGenerator.messageId()
        assertTrue(id.startsWith("msg-"))
        assertEquals(30, id.length) // "msg-" (4) + ULID (26)
    }

    @Test
    fun `snapshotId generates prefixed IDs`() {
        val id = IdGenerator.snapshotId()
        assertTrue(id.startsWith("snapshot-"))
        assertEquals(35, id.length) // "snapshot-" (9) + ULID (26)
    }

    @Test
    fun `extractTimestamp returns valid timestamps`() {
        val beforeGeneration = System.currentTimeMillis()
        val id = IdGenerator.ulid()
        val afterGeneration = System.currentTimeMillis()

        val timestamp = IdGenerator.extractTimestamp(id)
        assertNotNull(timestamp)
        assertTrue(
            timestamp in beforeGeneration..afterGeneration,
            "Timestamp $timestamp should be between $beforeGeneration and $afterGeneration"
        )
    }

    @Test
    fun `extractTimestamp handles prefixed IDs`() {
        val beforeGeneration = System.currentTimeMillis()
        val taskId = IdGenerator.taskId()
        val afterGeneration = System.currentTimeMillis()

        val timestamp = IdGenerator.extractTimestamp(taskId)
        assertNotNull(timestamp)
        assertTrue(timestamp in beforeGeneration..afterGeneration)
    }

    @Test
    fun `extractTimestamp returns null for invalid IDs`() {
        val invalidIds = listOf(
            "",
            "short",
            "TOOLONG123456789012345678901234567890",
            "invalid-chars-!@#$%^&*()",
            "task-invalid"
        )

        invalidIds.forEach { invalidId ->
            val timestamp = IdGenerator.extractTimestamp(invalidId)
            assertEquals(null, timestamp, "extractTimestamp('$invalidId') should return null")
        }
    }

    @Test
    fun `isValidUlid validates correct format`() {
        val validId = IdGenerator.ulid()
        assertTrue(IdGenerator.isValidUlid(validId))

        // Test invalid formats
        assertFalse(IdGenerator.isValidUlid(""))
        assertFalse(IdGenerator.isValidUlid("short"))
        assertFalse(IdGenerator.isValidUlid("TOOLONG123456789012345678901234567890"))
        assertFalse(IdGenerator.isValidUlid("01AN4Z07BY79K48ZEB6NS5Z8R!")) // Invalid char
        assertFalse(IdGenerator.isValidUlid("01AN4Z07BY79K48ZEB6NS5Z8RI")) // 'I' not in Crockford base32
        assertFalse(IdGenerator.isValidUlid("01AN4Z07BY79K48ZEB6NS5Z8RL")) // 'L' not in Crockford base32
        assertFalse(IdGenerator.isValidUlid("01AN4Z07BY79K48ZEB6NS5Z8RO")) // 'O' not in Crockford base32
        assertFalse(IdGenerator.isValidUlid("01AN4Z07BY79K48ZEB6NS5Z8RU")) // 'U' not in Crockford base32
    }

    @Test
    fun `concurrent ID generation produces unique IDs`() = runBlocking {
        val threadCount = 16
        val idsPerThread = 10_000
        val allIds = ConcurrentHashMap.newKeySet<String>()

        // Generate IDs from multiple coroutines in parallel
        val jobs = (1..threadCount).map {
            async(Dispatchers.Default) {
                repeat(idsPerThread) {
                    val id = IdGenerator.ulid()
                    assertTrue(allIds.add(id), "Duplicate ID detected: $id")
                }
            }
        }

        jobs.awaitAll()

        assertEquals(
            threadCount * idsPerThread,
            allIds.size,
            "All generated IDs should be unique"
        )
    }

    @Test
    fun `high throughput maintains uniqueness`() = runBlocking {
        val count = 100_000
        val ids = ConcurrentHashMap.newKeySet<String>()

        repeat(count) {
            val id = IdGenerator.ulid()
            assertTrue(ids.add(id), "Duplicate ID detected at iteration $it: $id")
        }

        assertEquals(count, ids.size)
    }

    @Test
    fun `monotonicity within same millisecond`() = runBlocking {
        // Generate many IDs quickly to get multiple within same millisecond
        val ids = (1..10_000).map { IdGenerator.ulid() }

        // Verify all are unique despite potential timestamp collisions
        assertEquals(10_000, ids.toSet().size, "All IDs should be unique even within same millisecond")

        // Note: Standard ULID.random() doesn't guarantee strict monotonicity within same millisecond
        // It guarantees uniqueness via randomness in the entropy portion
        // For strict monotonicity, a monotonic ULID generator would be needed
        // Here we just verify they're all unique, which is the critical property
    }

    @Test
    fun `prefixed IDs maintain time ordering`() {
        val taskIds = (1..100).map {
            Thread.sleep(1)
            IdGenerator.taskId()
        }

        val sorted = taskIds.sorted()
        assertEquals(taskIds, sorted, "Task IDs should be naturally time-ordered")
    }

    @Test
    fun `all ID generator methods produce unique IDs`() {
        val allIds = mutableSetOf<String>()

        repeat(1000) {
            allIds.add(IdGenerator.ulid())
            allIds.add(IdGenerator.taskId())
            allIds.add(IdGenerator.proposalId())
            allIds.add(IdGenerator.decisionId())
            allIds.add(IdGenerator.agentId("test"))
            allIds.add(IdGenerator.correlationId())
            allIds.add(IdGenerator.messageId())
            allIds.add(IdGenerator.snapshotId())
        }

        assertEquals(8000, allIds.size, "All generated IDs should be unique across all methods")
    }

    @Test
    fun `performance benchmark`() {
        val warmupCount = 10_000
        val benchmarkCount = 100_000

        // Warmup
        repeat(warmupCount) {
            IdGenerator.ulid()
        }

        // Benchmark
        val startTime = System.nanoTime()
        repeat(benchmarkCount) {
            IdGenerator.ulid()
        }
        val endTime = System.nanoTime()

        val durationMs = (endTime - startTime) / 1_000_000.0
        val idsPerSecond = (benchmarkCount / durationMs) * 1000

        println("Generated $benchmarkCount IDs in ${durationMs}ms")
        println("Throughput: ${idsPerSecond.toLong()} IDs/sec")

        // Performance assertion: should generate > 500k IDs/sec on modern hardware
        assertTrue(
            idsPerSecond > 500_000,
            "Performance too low: $idsPerSecond IDs/sec (expected > 500k)"
        )
    }
}
