package com.orchestrator.context.search

import com.orchestrator.context.storage.ContextDatabase
import com.orchestrator.utils.Logger

/**
 * Resolves "what changed" descriptions into the chunk IDs that overlap those
 * regions. Used by the impact-radius tool to seed graph traversal.
 *
 * Two input shapes are supported:
 *  - whole files (`ChangedRegion(path, null)`) → all chunks of the file
 *  - line ranges (`ChangedRegion(path, 10..42)`) → chunks whose `[start_line, end_line]`
 *    interval overlaps the requested range
 *
 * Path matching is permissive: the input is matched against both `rel_path`
 * and `abs_path` (and against the suffix of `abs_path` for relative inputs),
 * so callers may pass either form.
 */
class DiffResolver {

    private val log = Logger.logger(this::class.qualifiedName!!)

    data class ChangedRegion(
        val path: String,
        /** Inclusive line range; `null` means "the whole file". */
        val lineRange: IntRange? = null
    )

    /** Returns the deduped list of chunk IDs whose regions overlap the inputs. */
    fun resolveSeedChunks(regions: List<ChangedRegion>): List<Long> {
        if (regions.isEmpty()) return emptyList()
        return try {
            doResolve(regions)
        } catch (t: Throwable) {
            log.warn("DiffResolver failed: {}", t.message)
            emptyList()
        }
    }

    private fun doResolve(regions: List<ChangedRegion>): List<Long> {
        val seen = LinkedHashSet<Long>()
        ContextDatabase.withConnection { conn ->
            for (region in regions) {
                val normalized = region.path.replace('\\', '/').trim()
                if (normalized.isEmpty()) continue

                val sql = if (region.lineRange == null) {
                    """
                    SELECT c.chunk_id
                    FROM chunks c
                    JOIN file_state f ON f.file_id = c.file_id
                    WHERE f.is_deleted = FALSE
                      AND (f.rel_path = ? OR f.abs_path = ? OR f.abs_path LIKE ?)
                    ORDER BY c.ordinal
                    """.trimIndent()
                } else {
                    """
                    SELECT c.chunk_id
                    FROM chunks c
                    JOIN file_state f ON f.file_id = c.file_id
                    WHERE f.is_deleted = FALSE
                      AND (f.rel_path = ? OR f.abs_path = ? OR f.abs_path LIKE ?)
                      AND c.start_line IS NOT NULL
                      AND c.end_line   IS NOT NULL
                      AND c.start_line <= ?
                      AND c.end_line   >= ?
                    ORDER BY c.ordinal
                    """.trimIndent()
                }

                conn.prepareStatement(sql).use { ps ->
                    var idx = 1
                    ps.setString(idx++, normalized)
                    ps.setString(idx++, normalized)
                    // Tail match: lets users pass relative paths even when only abs_path is stored.
                    ps.setString(idx++, "%/$normalized")
                    if (region.lineRange != null) {
                        ps.setInt(idx++, region.lineRange.last)   // start_line <= end_of_change
                        ps.setInt(idx++, region.lineRange.first)  // end_line   >= start_of_change
                    }
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            seen.add(rs.getLong(1))
                        }
                    }
                }
            }
        }
        return seen.toList()
    }
}
