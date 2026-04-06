package com.orchestrator.mcp.tools

import com.orchestrator.context.ContextRepository
import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.search.DiffResolver
import com.orchestrator.utils.Logger

/**
 * MCP Tool: get_impact_radius
 *
 * Answers "what might break if I change these lines?" by walking the code graph
 * backwards from the changed chunks: transitive callers/dependents (impact) plus
 * the tests that cover them (via COVERS edges emitted from test files).
 *
 * Unlike query_context, this tool does **no** embedding, ranking, or MMR — it's
 * a deterministic graph traversal. Results are packed under a token budget,
 * seeds first, then impact by score, then tests.
 *
 * Typical usage: pass the file paths (or file+line ranges) from a diff or
 * `git status` and feed the response into a review / pre-merge workflow.
 */
class GetImpactRadiusTool(
    private val diffResolver: DiffResolver = DiffResolver()
) {
    private val log = Logger.logger(this::class.qualifiedName!!)

    data class ChangeInput(
        val path: String,
        val startLine: Int? = null,
        val endLine: Int? = null
    )

    data class Params(
        /** Shorthand: whole-file impact for each path. */
        val paths: List<String> = emptyList(),
        /** Fine-grained: changed line ranges within specific files. */
        val changes: List<ChangeInput> = emptyList(),
        val maxDepth: Int = 2,
        val includeTests: Boolean = true,
        val tokenBudget: Int = 8_000,
        val maxImpactResults: Int = 200,
        val maxTestResults: Int = 100
    )

    data class ImpactChunk(
        val chunkId: Long,
        val filePath: String,
        val relativePath: String?,
        val language: String?,
        val startLine: Int?,
        val endLine: Int?,
        val label: String?,
        val kind: String,
        val text: String,
        val tokenEstimate: Int,
        /** "seed" | "impact" | "test" */
        val role: String,
        val depth: Int? = null,
        val linkType: String? = null,
        val propagatedScore: Double? = null,
        val droppedFromBudget: Boolean = false
    )

    data class Result(
        val seedCount: Int,
        val impactCount: Int,
        val testCount: Int,
        val droppedDueToBudget: Int,
        val tokensUsed: Int,
        val chunks: List<ImpactChunk>
    )

    fun execute(params: Params): Result {
        val regions = buildRegions(params)
        if (regions.isEmpty()) {
            return Result(0, 0, 0, 0, 0, emptyList())
        }

        val seedIds = diffResolver.resolveSeedChunks(regions)
        if (seedIds.isEmpty()) {
            log.debug("get_impact_radius: no seed chunks resolved from {} regions", regions.size)
            return Result(0, 0, 0, 0, 0, emptyList())
        }

        val depth = params.maxDepth.coerceAtLeast(1)

        val impactEdges = ContextRepository.traverseGraphReverse(
            seedChunkIds = seedIds,
            maxDepth = depth,
            defaultLinkScore = 0.8,
            maxResults = params.maxImpactResults.coerceAtLeast(1),
            linkTypes = IMPACT_EDGE_TYPES
        )

        val testEdges = if (params.includeTests) {
            ContextRepository.traverseGraphReverse(
                seedChunkIds = seedIds,
                maxDepth = 1,
                defaultLinkScore = 0.9,
                maxResults = params.maxTestResults.coerceAtLeast(1),
                linkTypes = TEST_EDGE_TYPES
            )
        } else emptyList()

        // Deterministic ordering: seeds > impact (by depth, then by score desc) > tests (by score desc).
        // Dedup: a chunk that is both an impact caller and also reachable via COVERS gets tagged as impact
        // because impact is more informative for review context.
        val seedSet = seedIds.toHashSet()
        val impactById = impactEdges
            .filter { it.chunkId !in seedSet }
            .groupBy { it.chunkId }
            .mapValues { (_, edges) ->
                // Best (shallowest, highest-scoring) edge for each chunk.
                edges.minWith(
                    compareBy<ContextRepository.LinkedChunk> { it.depth }
                        .thenByDescending { it.linkScore }
                )
            }
        val testById = testEdges
            .filter { it.chunkId !in seedSet && it.chunkId !in impactById }
            .groupBy { it.chunkId }
            .mapValues { (_, edges) -> edges.maxByOrNull { it.linkScore }!! }

        val allIds = LinkedHashSet<Long>().apply {
            addAll(seedIds)
            impactById.keys
                .sortedWith(
                    compareBy<Long> { impactById.getValue(it).depth }
                        .thenByDescending { impactById.getValue(it).linkScore }
                )
                .forEach { add(it) }
            testById.keys
                .sortedByDescending { testById.getValue(it).linkScore }
                .forEach { add(it) }
        }

        val chunkData = ContextRepository.getChunksByIds(allIds.toList())
            .associateBy { it.chunk.id }

        val budget = params.tokenBudget.coerceAtLeast(0)
        var tokensUsed = 0
        var dropped = 0

        val impactChunks = allIds.mapNotNull { id ->
            val data = chunkData[id] ?: return@mapNotNull null
            val chunk = data.chunk
            val tokens = estimateTokens(chunk)

            val role: String
            val edge: ContextRepository.LinkedChunk?
            when {
                id in seedSet -> { role = "seed"; edge = null }
                id in impactById -> { role = "impact"; edge = impactById[id] }
                else -> { role = "test"; edge = testById[id] }
            }

            val overBudget = budget > 0 && tokensUsed + tokens > budget && role != "seed"
            if (overBudget) {
                dropped++
                return@mapNotNull ImpactChunk(
                    chunkId = chunk.id,
                    filePath = data.filePath,
                    relativePath = data.relativePath,
                    language = data.language,
                    startLine = chunk.lineSpan?.first,
                    endLine = chunk.lineSpan?.last,
                    label = chunk.summary,
                    kind = chunk.kind.name,
                    text = "",  // dropped: header-only, no content
                    tokenEstimate = tokens,
                    role = role,
                    depth = edge?.depth,
                    linkType = edge?.linkType,
                    propagatedScore = edge?.linkScore,
                    droppedFromBudget = true
                )
            }

            tokensUsed += tokens
            ImpactChunk(
                chunkId = chunk.id,
                filePath = data.filePath,
                relativePath = data.relativePath,
                language = data.language,
                startLine = chunk.lineSpan?.first,
                endLine = chunk.lineSpan?.last,
                label = chunk.summary,
                kind = chunk.kind.name,
                text = chunk.content,
                tokenEstimate = tokens,
                role = role,
                depth = edge?.depth,
                linkType = edge?.linkType,
                propagatedScore = edge?.linkScore,
                droppedFromBudget = false
            )
        }

        return Result(
            seedCount = seedIds.size,
            impactCount = impactById.size,
            testCount = testById.size,
            droppedDueToBudget = dropped,
            tokensUsed = tokensUsed,
            chunks = impactChunks
        )
    }

    private fun buildRegions(params: Params): List<DiffResolver.ChangedRegion> {
        val out = ArrayList<DiffResolver.ChangedRegion>()
        params.paths.filter { it.isNotBlank() }.forEach {
            out += DiffResolver.ChangedRegion(path = it, lineRange = null)
        }
        params.changes.forEach { c ->
            if (c.path.isBlank()) return@forEach
            val range = if (c.startLine != null && c.endLine != null && c.endLine >= c.startLine) {
                c.startLine..c.endLine
            } else null
            out += DiffResolver.ChangedRegion(path = c.path, lineRange = range)
        }
        return out
    }

    private fun estimateTokens(chunk: Chunk): Int =
        chunk.tokenEstimate ?: (chunk.content.length / 4).coerceAtLeast(1)

    companion object {
        private val IMPACT_EDGE_TYPES = setOf("CALLS", "DEPENDS_ON", "MODIFIES")
        private val TEST_EDGE_TYPES = setOf("COVERS")

        const val JSON_SCHEMA: String = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "title": "get_impact_radius params",
          "type": "object",
          "properties": {
            "paths":   { "type": "array", "items": { "type": "string" } },
            "changes": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "path":      { "type": "string" },
                  "startLine": { "type": "integer", "minimum": 1 },
                  "endLine":   { "type": "integer", "minimum": 1 }
                },
                "required": ["path"],
                "additionalProperties": false
              }
            },
            "maxDepth":         { "type": "integer", "minimum": 1, "default": 2 },
            "includeTests":     { "type": "boolean", "default": true },
            "tokenBudget":      { "type": "integer", "minimum": 0, "default": 8000 },
            "maxImpactResults": { "type": "integer", "minimum": 1, "default": 200 },
            "maxTestResults":   { "type": "integer", "minimum": 1, "default": 100 }
          },
          "additionalProperties": false
        }
        """
    }
}
