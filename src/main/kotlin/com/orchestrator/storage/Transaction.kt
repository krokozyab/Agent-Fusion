package com.orchestrator.storage

import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.Savepoint
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Simple coroutine-friendly transaction helper around the global Database connection.
 * - Automatically commits on success
 * - Automatically rolls back on error
 * - Detects nested transactions and uses JDBC savepoints
 * - Logs lifecycle events
 */
object Transaction {
    /** Coroutine context element to track transaction nesting per coroutine. */
    class TxContext(val depth: Int) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<TxContext>
    }

    /**
     * Execute [block] within a database transaction.
     * - Suspends safely; nesting information is carried via coroutine context (not ThreadLocal).
     * - Uses JDBC savepoints for nested transactions.
     */
    suspend fun <T> transaction(block: suspend (Connection) -> T): T {
        val conn = Database.getConnection()

        // Determine nesting level from coroutine context
        val currentCtx = coroutineContext[TxContext]
        val isOuter = currentCtx == null
        val newDepth = (currentCtx?.depth ?: 0) + 1

        var savepoint: Savepoint? = null
        val previousAutoCommit: Boolean? = if (isOuter) conn.autoCommit else null

        // Begin transaction or savepoint
        try {
            if (isOuter) {
                println("[TX] BEGIN (outer)")
                conn.autoCommit = false
            } else {
                savepoint = conn.setSavepoint("sp_tx_$newDepth")
                println("[TX] SAVEPOINT sp_tx_$newDepth (nested depth=$newDepth)")
            }
        } catch (e: Exception) {
            // If we failed to prepare transaction boundaries, try best-effort cleanup
            if (isOuter) {
                try { conn.autoCommit = previousAutoCommit ?: true } catch (_: Exception) {}
            }
            throw e
        }

        // Run the block within an updated coroutine context so nested calls can detect depth
        return try {
            val result = withContext(TxContext(newDepth)) { block(conn) }

            // Success path: commit or release savepoint
            if (isOuter) {
                conn.commit()
                println("[TX] COMMIT (outer)")
            } else {
                try {
                    if (savepoint != null) conn.releaseSavepoint(savepoint)
                    println("[TX] RELEASE SAVEPOINT sp_tx_$newDepth")
                } catch (e: Exception) {
                    // If release fails, rollback to savepoint as a safety net
                    try { if (savepoint != null) conn.rollback(savepoint) } catch (_: Exception) {}
                    throw e
                }
            }
            result
        } catch (t: Throwable) {
            // Error path: rollback
            try {
                if (isOuter) {
                    println("[TX] ROLLBACK (outer) due to ${t::class.simpleName}: ${t.message}")
                    conn.rollback()
                } else {
                    println("[TX] ROLLBACK TO SAVEPOINT sp_tx_$newDepth due to ${t::class.simpleName}: ${t.message}")
                    if (savepoint != null) conn.rollback(savepoint)
                }
            } catch (_: Exception) {
                // Swallow rollback errors but still rethrow original
            }
            throw t
        } finally {
            if (isOuter) {
                try {
                    conn.autoCommit = previousAutoCommit ?: true
                    println("[TX] END (outer)")
                } catch (_: Exception) {
                    // ignore
                }
            }
        }
    }
}
