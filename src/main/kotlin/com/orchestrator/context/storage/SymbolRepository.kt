package com.orchestrator.context.storage

import com.orchestrator.context.domain.SymbolRecord
import com.orchestrator.context.domain.SymbolType
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/**
 * Persistence helper for the `symbols` table.
 */
object SymbolRepository {

    fun replaceForFile(fileId: Long, symbols: List<SymbolRecord>): List<SymbolRecord> =
        ContextDatabase.transaction { conn ->
            conn.prepareStatement("DELETE FROM symbols WHERE file_id = ?").use { ps ->
                ps.setLong(1, fileId)
                ps.executeUpdate()
            }

            if (symbols.isEmpty()) return@transaction emptyList()

            val persisted = ArrayList<SymbolRecord>(symbols.size)
            conn.prepareStatement(
                """
                INSERT INTO symbols (
                    symbol_id, file_id, chunk_id, symbol_type, name,
                    qualified_name, signature, language, start_line, end_line, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                symbols.forEach { symbol ->
                    val id = if (symbol.id > 0) symbol.id else nextId(conn)
                    var idx = 1
                    ps.setLong(idx++, id)
                    ps.setLong(idx++, symbol.fileId)
                    if (symbol.chunkId != null) ps.setLong(idx++, symbol.chunkId) else ps.setNull(idx++, java.sql.Types.BIGINT)
                    ps.setString(idx++, symbol.symbolType.name)
                    ps.setString(idx++, symbol.name)
                    if (symbol.qualifiedName != null) ps.setString(idx++, symbol.qualifiedName) else ps.setNull(idx++, java.sql.Types.VARCHAR)
                    if (symbol.signature != null) ps.setString(idx++, symbol.signature) else ps.setNull(idx++, java.sql.Types.VARCHAR)
                    if (symbol.language != null) ps.setString(idx++, symbol.language) else ps.setNull(idx++, java.sql.Types.VARCHAR)
                    if (symbol.startLine != null) ps.setInt(idx++, symbol.startLine) else ps.setNull(idx++, java.sql.Types.INTEGER)
                    if (symbol.endLine != null) ps.setInt(idx++, symbol.endLine) else ps.setNull(idx++, java.sql.Types.INTEGER)
                    ps.setTimestamp(idx, Timestamp.from(symbol.createdAt))
                    ps.addBatch()
                    persisted += symbol.copy(id = id)
                }
                ps.executeBatch()
            }
            persisted
        }

    fun findByFileId(fileId: Long): List<SymbolRecord> =
        ContextDatabase.withConnection { conn ->
            conn.prepareStatement("SELECT * FROM symbols WHERE file_id = ? ORDER BY start_line NULLS FIRST, name").use { ps ->
                ps.setLong(1, fileId)
                ps.executeQuery().use { rs ->
                    val results = mutableListOf<SymbolRecord>()
                    while (rs.next()) results += rs.toSymbolRecord()
                    results
                }
            }
        }

    private fun nextId(conn: java.sql.Connection): Long {
        conn.prepareStatement("SELECT nextval('symbols_seq')").use { ps ->
            ps.executeQuery().use { rs ->
                rs.next()
                return rs.getLong(1)
            }
        }
    }

    private fun ResultSet.toSymbolRecord(): SymbolRecord = SymbolRecord(
        id = getLong("symbol_id"),
        fileId = getLong("file_id"),
        chunkId = getNullableLong("chunk_id"),
        symbolType = SymbolType.valueOf(getString("symbol_type")),
        name = getString("name"),
        qualifiedName = getString("qualified_name"),
        signature = getString("signature"),
        language = getString("language"),
        startLine = getNullableInt("start_line"),
        endLine = getNullableInt("end_line"),
        createdAt = getTimestamp("created_at").toInstant()
    )

    private fun ResultSet.getNullableInt(column: String): Int? =
        getObject(column)?.let { (it as Number).toInt() }

    private fun ResultSet.getNullableLong(column: String): Long? =
        getObject(column)?.let { (it as Number).toLong() }
}
