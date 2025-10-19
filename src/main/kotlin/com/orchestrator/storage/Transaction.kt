package com.orchestrator.storage

import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.Savepoint
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Coroutine-aware transaction helper that cooperates with the pooled connection manager.
 * - Acquires a new connection for outermost transactions and reuses it for nested ones via coroutine context.
 * - Falls back to JDBC savepoints for nested transactions.
 * - Ensures connections are returned to the pool after outer transactions complete.
 */
object Transaction {
    /** Coroutine context element that propagates the active connection and depth. */
    class TxContext(val connection: Connection, val depth: Int) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<TxContext>
    }

    /**
     * Execute [block] within a database transaction boundary.
     */
    suspend fun <T> transaction(block: suspend (Connection) -> T): T {
        val currentCtx = coroutineContext[TxContext]
        val isOuter = currentCtx == null
        val newDepth = (currentCtx?.depth ?: 0) + 1

        val conn = if (isOuter) Database.getConnection() else currentCtx!!.connection
        var savepoint: Savepoint? = null
        val previousAutoCommit: Boolean? = if (isOuter) conn.autoCommit else null

        try {
            if (isOuter) {
                println("[TX] BEGIN (outer)")
                conn.autoCommit = false
            } else {
                savepoint = conn.setSavepoint("sp_tx_$newDepth")
                println("[TX] SAVEPOINT sp_tx_$newDepth (nested depth=$newDepth)")
            }
        } catch (e: Exception) {
            if (isOuter) {
                runCatching { conn.autoCommit = previousAutoCommit ?: true }
                runCatching { conn.close() }
            }
            throw e
        }

        return try {
            val result = withContext(TxContext(conn, newDepth)) { block(conn) }

            if (isOuter) {
                conn.commit()
                println("[TX] COMMIT (outer)")
            } else {
                try {
                    if (savepoint != null) conn.releaseSavepoint(savepoint)
                    println("[TX] RELEASE SAVEPOINT sp_tx_$newDepth")
                } catch (releaseError: Exception) {
                    runCatching { if (savepoint != null) conn.rollback(savepoint) }
                    throw releaseError
                }
            }
            result
        } catch (t: Throwable) {
            runCatching {
                if (isOuter) {
                    println("[TX] ROLLBACK (outer) due to ${t::class.simpleName}: ${t.message}")
                    conn.rollback()
                } else {
                    println("[TX] ROLLBACK TO SAVEPOINT sp_tx_$newDepth due to ${t::class.simpleName}: ${t.message}")
                    if (savepoint != null) conn.rollback(savepoint)
                }
            }
            throw t
        } finally {
            if (isOuter) {
                runCatching { conn.autoCommit = previousAutoCommit ?: true }
                println("[TX] END (outer)")
                runCatching { conn.close() }
            }
        }
    }
}
