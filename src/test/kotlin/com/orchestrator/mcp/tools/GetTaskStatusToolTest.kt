package com.orchestrator.mcp.tools

import com.orchestrator.domain.*
import com.orchestrator.storage.Database
import com.orchestrator.storage.repositories.TaskRepository
import kotlin.test.*
import java.time.Instant
import java.time.format.DateTimeParseException

class GetTaskStatusToolTest {

    @BeforeTest
    fun resetDb() {
        Database.withConnection { conn ->
            conn.createStatement().use { st ->
                // FK-safe cleanup order
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
    fun returns_status_and_metadata_for_existing_task() {
        val now = Instant.now()
        val due = now.plusSeconds(1800)
        val task = Task(
            id = TaskId("task-xyz"),
            title = "Demo",
            description = "Demo desc",
            type = TaskType.REVIEW,
            status = TaskStatus.IN_PROGRESS,
            routing = RoutingStrategy.CONSENSUS,
            assigneeIds = setOf(AgentId("a2"), AgentId("a1")),
            dependencies = setOf(),
            complexity = 4,
            risk = 6,
            createdAt = now,
            updatedAt = now.plusSeconds(60),
            dueAt = due,
            metadata = mapOf("k" to "v", "scope" to "unit-test")
        )
        TaskRepository.insert(task)

        val tool = GetTaskStatusTool()
        val result = tool.execute(GetTaskStatusTool.Params(taskId = task.id.value))

        assertEquals(task.id.value, result.taskId)
        assertEquals(task.status.name, result.status)
        assertEquals(task.type.name, result.type)
        assertEquals(task.routing.name, result.routing)
        // Assignees should be sorted
        assertEquals(listOf("a1", "a2"), result.assignees)
        // ISO-8601 instants
        assertTrue(result.createdAt.endsWith("Z"))
        assertNotNull(result.updatedAt)
        assertTrue(result.updatedAt!!.endsWith("Z"))
        assertNotNull(result.dueAt)
        assertTrue(result.dueAt!!.endsWith("Z"))
        // Parse back to ensure valid format
        val parseResult = runCatching {
            java.time.Instant.parse(result.createdAt)
            java.time.Instant.parse(result.updatedAt)
            java.time.Instant.parse(result.dueAt)
        }
        assertTrue(parseResult.isSuccess)
        // Metadata round trip
        assertEquals(task.metadata, result.metadata)
    }

    @Test
    fun throws_for_missing_task() {
        val tool = GetTaskStatusTool()
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(GetTaskStatusTool.Params(taskId = "no-such-task"))
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test
    fun rejects_blank_task_id() {
        val tool = GetTaskStatusTool()
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(GetTaskStatusTool.Params(taskId = "   "))
        }
        assertTrue(ex.message!!.contains("cannot be blank"))
    }
}
