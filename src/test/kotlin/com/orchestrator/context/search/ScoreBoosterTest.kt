package com.orchestrator.context.search

import com.orchestrator.context.config.BoostConfig
import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.domain.ContextSnippet
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ScoreBoosterTest {

    @Test
    fun `returns original snippets when no boosts configured`() {
        val booster = ScoreBooster(BoostConfig(pathPrefixes = emptyMap(), languages = emptyMap()))
        val snippets = listOf(createSnippet(score = 0.8))

        val result = booster.applyBoosts(snippets)

        assertEquals(0.8, result[0].score, 0.001)
    }

    @Test
    fun `applies path boost when path matches prefix`() {
        val booster = ScoreBooster(
            BoostConfig(pathPrefixes = mapOf("src/main" to 1.5), languages = emptyMap())
        )
        val snippets = listOf(
            createSnippet(filePath = "/project/src/main/App.kt", score = 0.6)
        )

        val result = booster.applyBoosts(snippets)

        assertEquals(0.9, result[0].score, 0.001) // 0.6 * 1.5
    }

    @Test
    fun `applies language boost when language matches`() {
        val booster = ScoreBooster(
            BoostConfig(pathPrefixes = emptyMap(), languages = mapOf("kotlin" to 1.2))
        )
        val snippets = listOf(
            createSnippet(language = "kotlin", score = 0.5)
        )

        val result = booster.applyBoosts(snippets)

        assertEquals(0.6, result[0].score, 0.001) // 0.5 * 1.2
    }

    @Test
    fun `applies both path and language boosts`() {
        val booster = ScoreBooster(
            BoostConfig(
                pathPrefixes = mapOf("src/main" to 1.5),
                languages = mapOf("kotlin" to 1.2)
            )
        )
        val snippets = listOf(
            createSnippet(
                filePath = "/project/src/main/App.kt",
                language = "kotlin",
                score = 0.5)
        )

        val result = booster.applyBoosts(snippets)

        assertEquals(0.9, result[0].score, 0.001) // 0.5 * 1.5 * 1.2 = 0.9
    }

    @Test
    fun `clamps boosted score to maximum 1_0`() {
        val booster = ScoreBooster(
            BoostConfig(pathPrefixes = mapOf("src/main" to 2.0), languages = emptyMap())
        )
        val snippets = listOf(
            createSnippet(filePath = "/project/src/main/App.kt", score = 0.8)
        )

        val result = booster.applyBoosts(snippets)

        assertEquals(1.0, result[0].score, 0.001) // 0.8 * 2.0 = 1.6, clamped to 1.0
    }

    @Test
    fun `uses longest matching path prefix`() {
        val booster = ScoreBooster(
            BoostConfig(
                pathPrefixes = mapOf(
                    "src" to 1.1,
                    "src/main" to 1.5
                ),
                languages = emptyMap()
            )
        )
        val snippets = listOf(
            createSnippet(filePath = "/project/src/main/App.kt", score = 0.6)
        )

        val result = booster.applyBoosts(snippets)

        assertEquals(0.9, result[0].score, 0.001) // Uses 1.5 (longer match), not 1.1
    }

    @Test
    fun `no boost when path does not match`() {
        val booster = ScoreBooster(
            BoostConfig(pathPrefixes = mapOf("src/main" to 1.5), languages = emptyMap())
        )
        val snippets = listOf(
            createSnippet(filePath = "/project/vendor/lib.kt", score = 0.6)
        )

        val result = booster.applyBoosts(snippets)

        assertEquals(0.6, result[0].score, 0.001) // No boost applied
    }

    @Test
    fun `no boost when language does not match`() {
        val booster = ScoreBooster(
            BoostConfig(pathPrefixes = emptyMap(), languages = mapOf("kotlin" to 1.2))
        )
        val snippets = listOf(
            createSnippet(language = "python", score = 0.5)
        )

        val result = booster.applyBoosts(snippets)

        assertEquals(0.5, result[0].score, 0.001) // No boost applied
    }

    @Test
    fun `handles null language gracefully`() {
        val booster = ScoreBooster(
            BoostConfig(pathPrefixes = emptyMap(), languages = mapOf("kotlin" to 1.2))
        )
        val snippets = listOf(
            createSnippet(language = null, score = 0.5)
        )

        val result = booster.applyBoosts(snippets)

        assertEquals(0.5, result[0].score, 0.001) // No boost applied
    }

    private fun createSnippet(
        filePath: String = "/test/file.kt",
        language: String? = "kotlin",
        score: Double = 0.8
    ) = ContextSnippet(
        chunkId = 1,
        score = score,
        filePath = filePath,
        label = null,
        kind = ChunkKind.CODE_FUNCTION,
        text = "test content",
        language = language,
        offsets = 1..10,
        metadata = emptyMap()
    )
}
