package com.orchestrator.storage

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.Savepoint
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine-aware transaction helper that cooperates with the pooled connection manager.
 * - Acquires a new connection for outermost transactions and reuses it for nested ones via coroutine context.
 * - Falls back to JDBC savepoints for nested transactions.
 * - Ensures connections are returned to the pool after outer transactions complete.
 *
 * The active connection is mirrored into a [ThreadLocal] (via [ThreadContextElement]) so that
 * non-suspend repository code calling [Database.withConnection] joins the running transaction
 * instead of grabbing a fresh auto-commit connection from the pool. Without this, repository
 * writes inside a `transaction { ... }` block would commit independently and the outer
 * commit/rollback would be a no-op.
 */
object Transaction {
    /** Thread-bound view of the active transaction connection (see [TxContext]). */
    private val activeConnection = ThreadLocal<Connection?>()

    /** The connection of the transaction currently active on this thread, or null. */
    fun currentConnection(): Connection? = activeConnection.get()

    /**
     * Coroutine context element that propagates the active connection and depth, and mirrors
     * the connection into [activeConnection] on every thread the coroutine runs on.
     */
    class TxContext(val connection: Connection, val depth: Int) :
        AbstractCoroutineContextElement(Key), ThreadContextElement<Connection?> {
        companion object Key : CoroutineContext.Key<TxContext>

        override fun updateThreadContext(context: CoroutineContext): Connection? {
            val previous = activeConnection.get()
            activeConnection.set(connection)
            return previous
        }

        override fun restoreThreadContext(context: CoroutineContext, oldState: Connection?) {
            activeConnection.set(oldState)
        }
    }

    /**
     * Execute [block] within a database transaction boundary.
     */
    suspend fun <T> transaction(block: suspend (Connection) -> T): T {
        val currentCtx = currentTxContext()
        val isOuter = currentCtx == null
        val newDepth = (currentCtx?.depth ?: 0) + 1

        val conn = if (isOuter) Database.getConnection() else currentCtx!!.connection
        var savepoint: Savepoint? = null
        val previousAutoCommit: Boolean? = if (isOuter) conn.autoCommit else null

        try {
            if (isOuter) {
                conn.autoCommit = false
            } else {
                savepoint = conn.setSavepoint("sp_tx_$newDepth")
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
            } else {
                try {
                    if (savepoint != null) conn.releaseSavepoint(savepoint)
                } catch (releaseError: Exception) {
                    runCatching { if (savepoint != null) conn.rollback(savepoint) }
                    throw releaseError
                }
            }
            result
        } catch (t: Throwable) {
            runCatching {
                if (isOuter) {
                    conn.rollback()
                } else {
                    if (savepoint != null) conn.rollback(savepoint)
                }
            }
            throw t
        } finally {
            if (isOuter) {
                runCatching { conn.autoCommit = previousAutoCommit ?: true }
                runCatching { conn.close() }
            }
        }
    }

    private suspend fun currentTxContext(): TxContext? = kotlin.coroutines.coroutineContext[TxContext]
}
