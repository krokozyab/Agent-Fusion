package com.orchestrator.storage

import com.orchestrator.domain.*
import com.orchestrator.storage.repositories.TaskRepository
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Verifies that repository writes participate in an enclosing [Transaction.transaction] —
 * i.e. they commit/rollback together rather than each auto-committing on its own pooled
 * connection. This is the regression guard for the "fake transactions" bug.
 */
class TransactionTest {

    @BeforeTest
    fun setup() = cleanTasks()

    @AfterTest
    fun tearDown() = cleanTasks()

    private fun cleanTasks() {
        Database.withConnection { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("DELETE FROM context_snapshots")
                st.executeUpdate("DELETE FROM conversation_messages")
                st.executeUpdate("DELETE FROM decisions")
                st.executeUpdate("DELETE FROM proposals")
                st.executeUpdate("DELETE FROM metrics_timeseries")
                st.executeUpdate("DELETE FROM tasks")
            }
        }
    }

    @Test
    fun `successful transaction commits repository writes`() {
        runBlocking {
            val task = sampleTask("tx-commit")
            Transaction.transaction { _ ->
                TaskRepository.insert(task)
            }
            assertNotNull(TaskRepository.findById(task.id), "Committed task must be visible after transaction")
        }
    }

    @Test
    fun `failed transaction rolls back repository writes`() {
        runBlocking {
            val task = sampleTask("tx-rollback")

            assertFailsWith<IllegalStateException> {
                Transaction.transaction { _ ->
                    TaskRepository.insert(task)
                    // The row is written on the transaction connection but not yet committed.
                    // Throwing must roll the whole transaction back.
                    throw IllegalStateException("boom")
                }
            }

            assertNull(
                TaskRepository.findById(task.id),
                "Rolled-back task must NOT be persisted — repository write must join the transaction"
            )
        }
    }

    @Test
    fun `repository read inside transaction sees uncommitted write`() {
        runBlocking {
            val task = sampleTask("tx-read-your-writes")
            val foundInside = Transaction.transaction { _ ->
                TaskRepository.insert(task)
                // Same transaction connection => must observe its own uncommitted insert.
                TaskRepository.findById(task.id)
            }
            assertNotNull(foundInside, "A read inside the transaction must see the just-inserted row")
        }
    }

    private fun sampleTask(id: String): Task {
        val now = Instant.now()
        return Task(
            id = TaskId(id),
            title = "Title $id",
            description = "Desc $id",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.PENDING,
            routing = RoutingStrategy.SOLO,
            assigneeIds = setOf(AgentId("agent-1")),
            dependencies = emptySet(),
            complexity = 5,
            risk = 5,
            createdAt = now,
            updatedAt = null,
            dueAt = now.plusSeconds(3600),
            metadata = mapOf("p" to "q")
        )
    }
}
